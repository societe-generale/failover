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
import com.societegenerale.failover.store.FailoverStoreInmemory;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Integration tests for {@link MultiTenantFailoverStore} backed by real
 * {@link FailoverStoreInmemory} instances — no mocks, no Spring context.
 *
 * <p>Verifies that two tenants hold separate, isolated stores and that no
 * data crosses tenant boundaries for any store operation.
 *
 * @author Anand Manissery
 */
@DisplayName("MultiTenantFailoverStore — InMemory integration")
class MultiTenantInmemoryIntegrationTest {

    private static final String NAME = "product-service";
    private static final String KEY  = "product-001";
    private static final Instant NOW = Instant.now();

    /**
     * Factory that creates a fresh {@link FailoverStoreInmemory} per tenant,
     * ensuring each tenant starts with an empty, independent store.
     */
    private final TenantStoreFactory<String> inmemoryFactory = tenantId -> new FailoverStoreInmemory<>();
    private final UnaryOperator<FailoverStore<String>> identity = raw -> raw;
    private final TenantResolver resolver = TenantContext::get;

    private final MultiTenantFailoverStore<String> store =
            new MultiTenantFailoverStore<>(resolver, inmemoryFactory, identity, null);

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

    private ReferentialPayload<String> expiringPayload(String key, Instant expireOn) {
        return new ReferentialPayload<>(NAME, key, true, NOW, expireOn, "value-" + key);
    }

    // ── separate store per tenant ─────────────────────────────────────────────────

    @Nested
    @DisplayName("separate store per tenant")
    class SeparateStorePerTenant {

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
        @DisplayName("multiple keys per tenant — cross-tenant reads all return empty")
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
        @DisplayName("delete() removes entry only from the target tenant's store")
        void deleteAffectsOnlyTargetTenant() {
            withTenant("acme",   () -> store.store(payload(KEY, "acme-val")));
            withTenant("globex", () -> store.store(payload(KEY, "globex-val")));

            withTenant("acme", () -> store.delete(payload(KEY, null)));

            withTenant("acme",   () -> assertThat(store.find(NAME, KEY)).isEmpty());
            withTenant("globex", () -> assertThat(store.find(NAME, KEY)).isPresent());
        }
    }

    // ── cleanByExpiry ─────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("cleanByExpiry")
    class CleanByExpiry {

        @Test
        @DisplayName("evicts expired entries from all tenant stores")
        void evictsExpiredEntriesFromAllTenants() {
            Instant expiresSoon = NOW.plusSeconds(60);
            Instant expiresFar  = NOW.plusSeconds(7200);

            withTenant("acme",   () -> store.store(expiringPayload("exp-key",  expiresSoon)));
            withTenant("acme",   () -> store.store(expiringPayload("live-key", expiresFar)));
            withTenant("globex", () -> store.store(expiringPayload("exp-key",  expiresSoon)));
            withTenant("globex", () -> store.store(expiringPayload("live-key", expiresFar)));

            store.cleanByExpiry(NOW.plusSeconds(120));

            withTenant("acme",   () -> assertThat(store.find(NAME, "exp-key")).isEmpty());
            withTenant("acme",   () -> assertThat(store.find(NAME, "live-key")).isPresent());
            withTenant("globex", () -> assertThat(store.find(NAME, "exp-key")).isEmpty());
            withTenant("globex", () -> assertThat(store.find(NAME, "live-key")).isPresent());
        }

        @Test
        @DisplayName("expiry in one tenant does not affect the other tenant's live entries")
        void expiryInOneTenantDoesNotAffectOther() {
            Instant expiresSoon = NOW.plusSeconds(60);
            Instant expiresFar  = NOW.plusSeconds(7200);

            withTenant("acme",   () -> store.store(expiringPayload(KEY, expiresSoon)));
            withTenant("globex", () -> store.store(expiringPayload(KEY, expiresFar)));

            store.cleanByExpiry(NOW.plusSeconds(5400));

            withTenant("acme",   () -> assertThat(store.find(NAME, KEY)).isEmpty());
            withTenant("globex", () -> assertThat(store.find(NAME, KEY)).isPresent());
        }
    }
}