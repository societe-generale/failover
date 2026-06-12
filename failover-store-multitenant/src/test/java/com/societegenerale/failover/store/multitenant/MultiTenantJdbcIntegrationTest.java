/*
 * Copyright 2022-2026, Société Générale All rights reserved.
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
import com.societegenerale.failover.store.FailoverStoreJdbc;
import com.societegenerale.failover.store.mapper.ReferentialPayloadRowMapper;
import com.societegenerale.failover.store.resolver.DefaultDatabaseResolver;
import com.societegenerale.failover.store.resolver.DefaultFailoverStoreQueryResolver;
import com.societegenerale.failover.store.resolver.VarcharPayloadColumnResolver;
import com.societegenerale.failover.store.serializer.JsonSerializer;
import com.societegenerale.failover.store.serializer.Serializer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.util.function.UnaryOperator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Integration tests for {@link MultiTenantFailoverStore} backed by real
 * {@link FailoverStoreJdbc} instances against an embedded H2 database — no mocks, no Spring context.
 *
 * <p>Each tenant is isolated at the table level: tenant {@code acme} uses {@code ACME_FAILOVER_STORE}
 * and tenant {@code globex} uses {@code GLOBEX_FAILOVER_STORE}. Data written for one tenant is
 * never readable by another.
 *
 * <p>The embedded H2 database is started once per test class and both tables are truncated
 * before each test to ensure a clean state.
 *
 * @author Anand Manissery
 */
@DisplayName("MultiTenantFailoverStore — JDBC integration")
class MultiTenantJdbcIntegrationTest {

    private static EmbeddedDatabase embeddedDatabase;
    private static JdbcTemplate jdbcTemplate;
    private static Serializer serializer;
    private static RowMapper<ReferentialPayload<String>> rowMapper;

    private static final String NAME = "product-service";
    private static final String KEY  = "product-001";
    private static final Instant NOW = Instant.now();

    @BeforeAll
    static void setUpDatabase() {
        embeddedDatabase = new EmbeddedDatabaseBuilder()
                .setType(EmbeddedDatabaseType.H2)
                .addScript("classpath:multitenant-jdbc-schema.sql")
                .build();
        jdbcTemplate = new JdbcTemplate(embeddedDatabase);
        serializer   = new JsonSerializer(new JsonMapper());
        rowMapper    = new ReferentialPayloadRowMapper<>(new VarcharPayloadColumnResolver(), serializer);
    }

    @AfterAll
    static void tearDownDatabase() {
        embeddedDatabase.shutdown();
    }

    @BeforeEach
    void truncateTables() {
        jdbcTemplate.update("DELETE FROM ACME_FAILOVER_STORE");
        jdbcTemplate.update("DELETE FROM GLOBEX_FAILOVER_STORE");
    }

    @AfterEach
    void clearTenantContext() {
        TenantContext.clear();
    }

    // ── helpers ────────────────────────────────────────────────────────────────────

    /**
     * Builds a fresh {@link MultiTenantFailoverStore} wired to the shared embedded H2.
     * Each tenant gets its own {@link FailoverStoreJdbc} instance backed by a separate table
     * ({@code ACME_FAILOVER_STORE} / {@code GLOBEX_FAILOVER_STORE}).
     */
    private MultiTenantFailoverStore<String> buildStore() {
        var dbResolver = new DefaultDatabaseResolver(jdbcTemplate);
        TenantStoreFactory<String> jdbcFactory = tenantId -> {
            String prefix = tenantId.toUpperCase() + "_";
            var queryResolver = new DefaultFailoverStoreQueryResolver(
                    prefix, serializer, dbResolver, new VarcharPayloadColumnResolver());
            return new FailoverStoreJdbc<>(jdbcTemplate, queryResolver, rowMapper);
        };
        UnaryOperator<FailoverStore<String>> identity = raw -> raw;
        return new MultiTenantFailoverStore<>(TenantContext::get, jdbcFactory, identity, null);
    }

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

    // ── separate table per tenant ─────────────────────────────────────────────────

    @Nested
    @DisplayName("separate table per tenant")
    class SeparateTablePerTenant {

        @Test
        @DisplayName("acme data not visible to globex — separate JDBC tables")
        void acmeDataNotVisibleToGlobex() {
            var store = buildStore();
            withTenant("acme", () -> store.store(payload(KEY, "acme-val")));

            withTenant("globex", () ->
                    assertThat(store.find(NAME, KEY)).isEmpty());
        }

        @Test
        @DisplayName("globex data not visible to acme — separate JDBC tables")
        void globexDataNotVisibleToAcme() {
            var store = buildStore();
            withTenant("globex", () -> store.store(payload(KEY, "globex-val")));

            withTenant("acme", () ->
                    assertThat(store.find(NAME, KEY)).isEmpty());
        }

        @Test
        @DisplayName("same key stored by both tenants — each reads its own value")
        void sameKeyHoldsIndependentValues() {
            var store = buildStore();
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
            var store = buildStore();
            withTenant("acme",   () -> store.store(payload("key-1", "acme-1")));
            withTenant("acme",   () -> store.store(payload("key-2", "acme-2")));
            withTenant("globex", () -> store.store(payload("key-1", "globex-1")));

            withTenant("acme",   () -> assertThat(store.find(NAME, "key-1")).isPresent());
            withTenant("acme",   () -> assertThat(store.find(NAME, "key-2")).isPresent());
            withTenant("globex", () -> assertThat(store.find(NAME, "key-1")).isPresent());
            withTenant("globex", () -> assertThat(store.find(NAME, "key-2")).isEmpty());
        }

        @Test
        @DisplayName("delete() removes entry only from the target tenant's table")
        void deleteAffectsOnlyTargetTenantTable() {
            var store = buildStore();
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
        @DisplayName("cleanByExpiry without TenantContext does not throw — iterates all initialized stores")
        void cleanByExpiryWithoutContextDoesNotThrow() {
            var store = buildStore();
            withTenant("acme",   () -> store.store(payload(KEY, "acme-val")));
            withTenant("globex", () -> store.store(payload(KEY, "globex-val")));

            assertThatNoException().isThrownBy(() ->
                    store.cleanByExpiry(NOW.plusSeconds(3600)));
        }

        @Test
        @DisplayName("cleanByExpiry evicts expired entries from all tenant tables")
        void evictsExpiredEntriesFromAllTenants() {
            var store = buildStore();
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
        @DisplayName("expiry in one tenant does not remove live entries in the other")
        void expiryDoesNotCrossTenantsForLiveEntries() {
            var store = buildStore();
            Instant expiresSoon = NOW.plusSeconds(60);
            Instant expiresFar  = NOW.plusSeconds(7200);

            withTenant("acme",   () -> store.store(expiringPayload("exp-key", expiresSoon)));
            withTenant("globex", () -> store.store(expiringPayload("exp-key", expiresFar)));

            store.cleanByExpiry(NOW.plusSeconds(5400));

            withTenant("acme",   () -> assertThat(store.find(NAME, "exp-key")).isEmpty());
            withTenant("globex", () -> assertThat(store.find(NAME, "exp-key")).isPresent());
        }

        @Test
        @DisplayName("cleanByExpiry on empty store does not throw")
        void cleanByExpiryOnEmptyStoreDoesNotThrow() {
            assertThatNoException().isThrownBy(() ->
                    buildStore().cleanByExpiry(NOW.plusSeconds(3600)));
        }
    }
}