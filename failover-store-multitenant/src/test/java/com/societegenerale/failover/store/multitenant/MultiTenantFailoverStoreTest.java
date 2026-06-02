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
import com.societegenerale.failover.core.store.FailoverStoreException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MultiTenantFailoverStoreTest {

    @Mock private FailoverStore<String> acmeStore;
    @Mock private FailoverStore<String> globexStore;
    @Mock private ReferentialPayload<String> payload;

    /** Identity decorator — no wrapping, delegates straight through for easy verification. */
    private final UnaryOperator<FailoverStore<String>> identity = raw -> raw;

    private TenantStoreFactory<String> twoTenantFactory() {
        return tenantId -> switch (tenantId) {
            case "acme"   -> acmeStore;
            case "globex" -> globexStore;
            default       -> throw new IllegalArgumentException("Unknown tenant: " + tenantId);
        };
    }

    @Nested
    @DisplayName("tenant routing")
    class TenantRouting {

        @Test
        @DisplayName("store() routes to current tenant")
        void storeRoutesToTenant() {
            var store = new MultiTenantFailoverStore<>(() -> "acme", twoTenantFactory(), identity, null);
            store.store(payload);
            verify(acmeStore).store(payload);
            verifyNoInteractions(globexStore);
        }

        @Test
        @DisplayName("delete() routes to current tenant")
        void deleteRoutesToTenant() {
            var store = new MultiTenantFailoverStore<>(() -> "acme", twoTenantFactory(), identity, null);
            store.delete(payload);
            verify(acmeStore).delete(payload);
            verifyNoInteractions(globexStore);
        }

        @Test
        @DisplayName("find() routes to current tenant")
        void findRoutesToTenant() {
            when(acmeStore.find("name", "key")).thenReturn(Optional.empty());
            var store = new MultiTenantFailoverStore<>(() -> "acme", twoTenantFactory(), identity, null);
            store.find("name", "key");
            verify(acmeStore).find("name", "key");
            verifyNoInteractions(globexStore);
        }

        @Test
        @DisplayName("different tenants resolved on successive calls route independently")
        void differentTenantsResolveIndependently() {
            String[] current = {"acme"};
            var store = new MultiTenantFailoverStore<>(() -> current[0], twoTenantFactory(), identity, null);

            store.store(payload);
            current[0] = "globex";
            store.store(payload);

            verify(acmeStore).store(payload);
            verify(globexStore).store(payload);
        }
    }

    @Nested
    @DisplayName("cleanByExpiry")
    class CleanByExpiry {

        @Test
        @DisplayName("covers all pre-warmed tenant stores")
        void coversAllPrewarmedTenants() {
            var store = new MultiTenantFailoverStore<>(() -> null, twoTenantFactory(), identity, "acme");
            store.prewarm(Set.of("acme", "globex"));

            LocalDateTime expiry = LocalDateTime.now();
            store.cleanByExpiry(expiry);

            verify(acmeStore).cleanByExpiry(expiry);
            verify(globexStore).cleanByExpiry(expiry);
        }

        @Test
        @DisplayName("covers only initialized tenants — lazy tenants skipped")
        void coversOnlyInitializedTenants() {
            var store = new MultiTenantFailoverStore<>(() -> "acme", twoTenantFactory(), identity, null);
            store.store(payload); // only acme accessed

            LocalDateTime expiry = LocalDateTime.now();
            store.cleanByExpiry(expiry);

            verify(acmeStore).cleanByExpiry(expiry);
            verifyNoInteractions(globexStore);
        }
    }

    @Nested
    @DisplayName("prewarm")
    class Prewarm {

        @Test
        @DisplayName("initializes all listed tenants")
        void initializesListedTenants() {
            var store = new MultiTenantFailoverStore<>(() -> null, twoTenantFactory(), identity, "acme");
            store.prewarm(Set.of("acme", "globex"));

            LocalDateTime expiry = LocalDateTime.now();
            store.cleanByExpiry(expiry);

            verify(acmeStore).cleanByExpiry(expiry);
            verify(globexStore).cleanByExpiry(expiry);
        }

        @Test
        @DisplayName("prewarm with empty set is a no-op")
        void prewarmEmptySetDoesNothing() {
            var store = new MultiTenantFailoverStore<>(() -> "acme", _ -> acmeStore, identity, null);
            store.prewarm(Set.of());
            store.cleanByExpiry(LocalDateTime.now());
            verifyNoInteractions(acmeStore);
        }
    }

    @Nested
    @DisplayName("null-tenant fallback and error")
    class NullTenantFallback {

        @Test
        @DisplayName("falls back to defaultTenant when resolver returns null")
        void usesDefaultTenantWhenResolverReturnsNull() {
            var store = new MultiTenantFailoverStore<>(() -> null, _ -> acmeStore, identity, "acme");
            store.store(payload);
            verify(acmeStore).store(payload);
        }

        @Test
        @DisplayName("throws FailoverStoreException when resolver and defaultTenant both null")
        void throwsWhenBothNullTenant() {
            var store = new MultiTenantFailoverStore<>(() -> null, _ -> acmeStore, identity, null);
            assertThatThrownBy(() -> store.store(payload))
                    .isInstanceOf(FailoverStoreException.class)
                    .hasMessageContaining("No tenant resolved and no default-tenant configured");
        }
    }

    @Nested
    @DisplayName("decorator application")
    class DecoratorApplication {

        @Test
        @DisplayName("decorator applied once per tenant — not on repeated access")
        void decoratorAppliedOncePerTenant() {
            int[] count = {0};
            UnaryOperator<FailoverStore<String>> countingDecorator = raw -> {
                count[0]++;
                return raw;
            };
            var store = new MultiTenantFailoverStore<>(() -> "acme", _ -> acmeStore, countingDecorator, null);

            store.store(payload);
            store.store(payload);

            assertThat(count[0]).isEqualTo(1);
        }

        @Test
        @DisplayName("decorator is applied per distinct tenant")
        void decoratorAppliedPerTenant() {
            int[] count = {0};
            UnaryOperator<FailoverStore<String>> countingDecorator = raw -> {
                count[0]++;
                return raw;
            };
            String[] current = {"acme"};
            var store = new MultiTenantFailoverStore<>(() -> current[0], twoTenantFactory(), countingDecorator, null);

            store.store(payload);     // acme — first access
            current[0] = "globex";
            store.store(payload);     // globex — first access
            store.store(payload);     // globex — second access, no new decorator call

            assertThat(count[0]).isEqualTo(2);
        }
    }
}