/*
 * Copyright 2022-2023, Société Générale All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.societegenerale.failover.store.multitenant;

import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.store.FailoverStoreCaffeine;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Integration tests for {@link MultiTenantFailoverStore} backed by real
 * {@link FailoverStoreCaffeine} instances — no mocks, no Spring context.
 *
 * <p>Verifies that two tenants hold separate, isolated Caffeine caches and that no
 * data crosses tenant boundaries for any store operation.
 *
 * <h2>Caffeine-specific behaviour</h2>
 * <ul>
 *   <li>Expiry is managed automatically by Caffeine's per-entry TTL; {@link FailoverStore#cleanByExpiry}
 *       is a no-op and never throws.</li>
 *   <li>Data written with a far-future {@code expireOn} persists for the lifetime of the cache
 *       and is not removed by {@code cleanByExpiry}.</li>
 * </ul>
 *
 * @author Anand Manissery
 */
@DisplayName("MultiTenantFailoverStore — Caffeine integration")
class MultiTenantCaffeineIntegrationTest {

    private static final String NAME = "product-service";
    private static final String KEY  = "product-001";
    private static final Instant NOW = Instant.now();

    /**
     * Factory that creates a fresh {@link FailoverStoreCaffeine} per tenant.
     * Uses a fixed clock anchored to {@code NOW} so TTL computation is deterministic.
     */
    private final TenantStoreFactory<String> caffeineFactory = _ -> new FailoverStoreCaffeine<>(() -> NOW);
    private final UnaryOperator<FailoverStore<String>> identity = raw -> raw;
    private final TenantResolver resolver = TenantContext::get;

    private final MultiTenantFailoverStore<String> store =
            new MultiTenantFailoverStore<>(resolver, caffeineFactory, identity, null);

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    private void withTenant(String tenant, Runnable action) {
        TenantContext.set(tenant);
        try { action.run(); }
        finally { TenantContext.clear(); }
    }

    private ReferentialPayload<String> payload(String key, String value) {
        return new ReferentialPayload<>(NAME, key, true, NOW, NOW.plusSeconds(3600), value);
    }

    // ── separate cache per tenant ─────────────────────────────────────────────────

    @Nested
    @DisplayName("separate cache per tenant")
    class SeparateCachePerTenant {

        @Test
        @DisplayName("acme data not visible to globex")
        void acmeDataNotVisibleToGlobex() {
            withTenant("acme", () -> store.store(payload(KEY, "acme-val")));

            withTenant("globex", () ->
                    assertThat(store.find(NAME, KEY)).isEmpty());
        }

        @Test
        @DisplayName("globex data not visible to acme")
        void globexDataNotVisibleToAcme() {
            withTenant("globex", () -> store.store(payload(KEY, "globex-val")));

            withTenant("acme", () ->
                    assertThat(store.find(NAME, KEY)).isEmpty());
        }

        @Test
        @DisplayName("same key stored by both tenants — each reads its own value")
        void sameKeyHoldsIndependentValues() {
            withTenant("acme",   () -> store.store(payload(KEY, "acme-val")));
            withTenant("globex", () -> store.store(payload(KEY, "globex-val")));

            withTenant("acme",   () ->
                    assertThat(store.find(NAME, KEY)).isPresent()
                            .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("acme-val")));
            withTenant("globex", () ->
                    assertThat(store.find(NAME, KEY)).isPresent()
                            .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("globex-val")));
        }

        @Test
        @DisplayName("multiple keys per tenant — cross-tenant reads return empty")
        void multipleKeysPerTenantNoCrossRead() {
            withTenant("acme",   () -> store.store(payload("key-1", "acme-1")));
            withTenant("acme",   () -> store.store(payload("key-2", "acme-2")));
            withTenant("globex", () -> store.store(payload("key-1", "globex-1")));

            withTenant("acme",   () -> assertThat(store.find(NAME, "key-1")).isPresent());
            withTenant("acme",   () -> assertThat(store.find(NAME, "key-2")).isPresent());
            withTenant("globex", () -> assertThat(store.find(NAME, "key-1")).isPresent());
            withTenant("globex", () -> assertThat(store.find(NAME, "key-2")).isEmpty());
        }

        @Test
        @DisplayName("delete() removes entry only from the target tenant's cache")
        void deleteAffectsOnlyTargetTenant() {
            withTenant("acme",   () -> store.store(payload(KEY, "acme-val")));
            withTenant("globex", () -> store.store(payload(KEY, "globex-val")));

            withTenant("acme", () -> store.delete(payload(KEY, null)));

            withTenant("acme",   () -> assertThat(store.find(NAME, KEY)).isEmpty());
            withTenant("globex", () -> assertThat(store.find(NAME, KEY)).isPresent());
        }
    }

    // ── cleanByExpiry — no-op for Caffeine ────────────────────────────────────────

    @Nested
    @DisplayName("cleanByExpiry — no-op for Caffeine")
    class CleanByExpiry {

        @Test
        @DisplayName("cleanByExpiry without TenantContext does not throw — iterates all initialized stores")
        void cleanByExpiryWithoutContextDoesNotThrow() {
            withTenant("acme",   () -> store.store(payload(KEY, "acme-val")));
            withTenant("globex", () -> store.store(payload(KEY, "globex-val")));

            assertThatNoException().isThrownBy(() ->
                    store.cleanByExpiry(NOW.plusSeconds(3600)));
        }

        @Test
        @DisplayName("data remains in cache after cleanByExpiry — eviction is managed by Caffeine TTL")
        void dataRemainsAfterCleanByExpiry() {
            withTenant("acme",   () -> store.store(payload(KEY, "acme-val")));
            withTenant("globex", () -> store.store(payload(KEY, "globex-val")));

            store.cleanByExpiry(NOW.plusSeconds(3600));

            withTenant("acme",   () -> assertThat(store.find(NAME, KEY)).isPresent());
            withTenant("globex", () -> assertThat(store.find(NAME, KEY)).isPresent());
        }

        @Test
        @DisplayName("cleanByExpiry on empty store does not throw")
        void cleanByExpiryOnEmptyStoreDoesNotThrow() {
            assertThatNoException().isThrownBy(() ->
                    store.cleanByExpiry(NOW.plusSeconds(3600)));
        }
    }
}