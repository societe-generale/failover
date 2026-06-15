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

package com.societegenerale.failover.configuration;

import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.DefaultFailoverStore;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.core.store.FailoverStoreException;
import com.societegenerale.failover.store.async.FailoverStoreAsync;
import com.societegenerale.failover.store.caffeine.FailoverStoreCaffeine;
import com.societegenerale.failover.store.inmemory.FailoverStoreInmemory;
import com.societegenerale.failover.properties.FailoverProperties;
import com.societegenerale.failover.properties.Jdbc;
import com.societegenerale.failover.properties.MultiTenant;
import com.societegenerale.failover.properties.Store;
import com.societegenerale.failover.properties.TenantConfig;
import com.societegenerale.failover.store.jdbc.FailoverStoreJdbc;
import com.societegenerale.failover.store.multitenant.FixedTenantResolver;
import com.societegenerale.failover.store.multitenant.MultiTenantFailoverStore;
import com.societegenerale.failover.store.multitenant.TenantContext;
import com.societegenerale.failover.store.multitenant.TenantContextTenantResolver;
import com.societegenerale.failover.store.multitenant.TenantResolver;
import com.societegenerale.failover.store.multitenant.TenantStoreFactory;
import com.societegenerale.failover.store.jdbc.resolver.DatabaseResolver;
import com.societegenerale.failover.store.jdbc.resolver.DefaultDatabaseResolver;
import com.societegenerale.failover.store.jdbc.resolver.DefaultFailoverStoreQueryResolver;
import com.societegenerale.failover.store.jdbc.resolver.PayloadColumnResolver;
import com.societegenerale.failover.store.jdbc.serializer.Serializer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Primary;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.jdbc.datasource.lookup.AbstractRoutingDataSource;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.time.Instant;
import java.util.Map;
import java.util.Set;

import org.springframework.test.util.ReflectionTestUtils;

import static com.societegenerale.failover.core.util.CastingUtils.cast;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class FailoverStoreMultiTenantAutoConfigurationTest {

    // ── @TestConfiguration classes ────────────────────────────────────────────

    @TestConfiguration
    static class TenantResolverConfig {
        @Bean
        public TenantResolver tenantResolver() {
            return new FixedTenantResolver("test-tenant");
        }
    }

    @TestConfiguration
    static class TenantContextConfig {
        @Bean
        TenantResolver tenantResolver() {
            return new TenantContextTenantResolver();
        }
    }

    /**
     * Wiring for the SCHEMA strategy: two H2 databases routed by an
     * {@code AbstractRoutingDataSource} keyed on {@link TenantContext#get()}.
     */
    @TestConfiguration
    static class RoutingDataSourceConfig {

        @Bean
        TenantResolver tenantResolver() {
            return new TenantContextTenantResolver();
        }

        @Bean
        @Qualifier("acmeDataSource")
        DataSource acmeDataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("schema-it-acme")
                    .addScript("classpath:multitenant-jdbc-schema-tenant.sql")
                    .build();
        }

        @Bean
        @Qualifier("globexDataSource")
        DataSource globexDataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("schema-it-globex")
                    .addScript("classpath:multitenant-jdbc-schema-tenant.sql")
                    .build();
        }

        @Bean
        @Primary
        DataSource routingDataSource(
                @Qualifier("acmeDataSource") DataSource acmeDataSource,
                @Qualifier("globexDataSource") DataSource globexDataSource) {
            AbstractRoutingDataSource routing = new AbstractRoutingDataSource() {
                @Override
                protected Object determineCurrentLookupKey() {
                    return TenantContext.get();
                }
            };
            routing.setTargetDataSources(Map.of("acme", acmeDataSource, "globex", globexDataSource));
            routing.setDefaultTargetDataSource(acmeDataSource);
            routing.afterPropertiesSet();
            return routing;
        }
    }

    /**
     * Wiring for the separate-datasource strategy: each tenant owns a dedicated
     * {@link JdbcTemplate} baked in at factory construction time via a custom
     * {@link TenantStoreFactory} that suppresses the library's autoconfigured factory.
     */
    @TestConfiguration
    static class SeparateDatasourceConfig {

        @Bean
        TenantResolver tenantResolver() {
            return new TenantContextTenantResolver();
        }

        @Bean
        @Primary
        @Qualifier("acmeDataSource")
        DataSource acmeDataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("separate-ds-acme")
                    .addScript("classpath:multitenant-jdbc-schema-tenant.sql")
                    .build();
        }

        @Bean
        @Qualifier("globexDataSource")
        DataSource globexDataSource() {
            return new EmbeddedDatabaseBuilder()
                    .setType(EmbeddedDatabaseType.H2)
                    .setName("separate-ds-globex")
                    .addScript("classpath:multitenant-jdbc-schema-tenant.sql")
                    .build();
        }

        @Bean
        TenantStoreFactory<Object> customJdbcTenantStoreFactory(
                @Qualifier("acmeDataSource")   DataSource acmeDs,
                @Qualifier("globexDataSource") DataSource globexDs,
                Serializer serializer,
                PayloadColumnResolver payloadColumnResolver,
                RowMapper<ReferentialPayload<Object>> rowMapper) {
            Map<String, JdbcTemplate> byTenant = Map.of(
                    "acme",   new JdbcTemplate(acmeDs),
                    "globex", new JdbcTemplate(globexDs)
            );
            return tenantId -> {
                JdbcTemplate jdbc = byTenant.get(tenantId);
                if (jdbc == null) throw new IllegalArgumentException("No datasource configured for tenant: " + tenantId);
                DatabaseResolver dbResolver = new DefaultDatabaseResolver(jdbc);
                var qr = new DefaultFailoverStoreQueryResolver("DEMO_", serializer, dbResolver, payloadColumnResolver);
                return new FailoverStoreJdbc<>(jdbc, qr, rowMapper);
            };
        }
    }

    // ── Unit tests — resolveJdbcPrefix ────────────────────────────────────────

    @Nested
    @DisplayName("resolveJdbcPrefix")
    class ResolveJdbcPrefix {

        @Test
        @DisplayName("tenantPrefix + globalPrefix are concatenated")
        void concatenatesTenantAndGlobalPrefix() {
            FailoverProperties props = propsWithJdbcAndTenants("DEMO_", Map.of("acme", tenantConfig("ACME_")));
            assertThat(FailoverStoreMultiTenantAutoConfiguration.resolveJdbcPrefix(props, "acme"))
                    .isEqualTo("ACME_DEMO_");
        }

        @Test
        @DisplayName("no tenant prefix — returns global prefix only")
        void noTenantPrefixReturnsGlobalOnly() {
            FailoverProperties props = propsWithJdbcAndTenants("DEMO_", Map.of("acme", tenantConfig("")));
            assertThat(FailoverStoreMultiTenantAutoConfiguration.resolveJdbcPrefix(props, "acme"))
                    .isEqualTo("DEMO_");
        }

        @Test
        @DisplayName("no global prefix — returns tenant prefix only")
        void noGlobalPrefixReturnsTenantOnly() {
            FailoverProperties props = propsWithJdbcAndTenants("", Map.of("acme", tenantConfig("ACME_")));
            assertThat(FailoverStoreMultiTenantAutoConfiguration.resolveJdbcPrefix(props, "acme"))
                    .isEqualTo("ACME_");
        }

        @Test
        @DisplayName("no prefixes at all — returns empty string")
        void noPrefixesReturnsEmpty() {
            FailoverProperties props = propsWithJdbcAndTenants("", Map.of());
            assertThat(FailoverStoreMultiTenantAutoConfiguration.resolveJdbcPrefix(props, "acme"))
                    .isEmpty();
        }

        @Test
        @DisplayName("unknown tenant — falls back to empty tenant prefix, global prefix used")
        void unknownTenantFallsBackToEmptyTenantPrefix() {
            FailoverProperties props = propsWithJdbcAndTenants("DEMO_", Map.of("acme", tenantConfig("ACME_")));
            assertThat(FailoverStoreMultiTenantAutoConfiguration.resolveJdbcPrefix(props, "unknown-tenant"))
                    .isEqualTo("DEMO_");
        }

        @Test
        @DisplayName("multiple tenants — each resolves its own prefix")
        void multipleTenantsResolveIndependently() {
            FailoverProperties props = propsWithJdbcAndTenants("DEMO_", Map.of(
                    "acme",   tenantConfig("ACME_"),
                    "globex", tenantConfig("GLOBEX_")
            ));
            assertThat(FailoverStoreMultiTenantAutoConfiguration.resolveJdbcPrefix(props, "acme"))
                    .isEqualTo("ACME_DEMO_");
            assertThat(FailoverStoreMultiTenantAutoConfiguration.resolveJdbcPrefix(props, "globex"))
                    .isEqualTo("GLOBEX_DEMO_");
        }

        @Test
        @DisplayName("strict mode — unknown tenant throws FailoverStoreException instead of sharing the global table")
        void strictModeRejectsUnknownTenant() {
            FailoverProperties props = propsWithJdbcAndTenants("DEMO_", Map.of("acme", tenantConfig("ACME_")));
            props.getStore().getMultitenant().setStrict(true);

            assertThatThrownBy(() -> FailoverStoreMultiTenantAutoConfiguration.resolveJdbcPrefix(props, "unknown-tenant"))
                    .isInstanceOf(FailoverStoreException.class)
                    .hasMessageContaining("unknown-tenant")
                    .hasMessageContaining("strict");
        }

        @Test
        @DisplayName("strict mode — configured tenant resolves normally")
        void strictModeAllowsConfiguredTenant() {
            FailoverProperties props = propsWithJdbcAndTenants("DEMO_", Map.of("acme", tenantConfig("ACME_")));
            props.getStore().getMultitenant().setStrict(true);
            assertThat(FailoverStoreMultiTenantAutoConfiguration.resolveJdbcPrefix(props, "acme"))
                    .isEqualTo("ACME_DEMO_");
        }

        @Test
        @DisplayName("strict mode — the configured default tenant is exempt and resolves to the global table")
        void strictModeExemptsDefaultTenant() {
            FailoverProperties props = propsWithJdbcAndTenants("DEMO_", Map.of("acme", tenantConfig("ACME_")));
            props.getStore().getMultitenant().setStrict(true);
            props.getStore().getMultitenant().setDefaultTenant("fallback");
            assertThat(FailoverStoreMultiTenantAutoConfiguration.resolveJdbcPrefix(props, "fallback"))
                    .isEqualTo("DEMO_");
        }

        @Test
        @DisplayName("non-strict — a null tenant id is not the default tenant; warns and resolves to the global table")
        void nonStrictNullTenantResolvesToGlobal() {
            FailoverProperties props = propsWithJdbcAndTenants("DEMO_", Map.of("acme", tenantConfig("ACME_")));
            props.getStore().getMultitenant().setDefaultTenant("acme");
            assertThat(FailoverStoreMultiTenantAutoConfiguration.resolveJdbcPrefix(props, null))
                    .isEqualTo("DEMO_");
        }

        @Test
        @DisplayName("non-strict — repeated resolution of the same unknown tenant warns once and stays consistent")
        void nonStrictRepeatedUnknownTenantWarnsOnce() {
            FailoverProperties props = propsWithJdbcAndTenants("DEMO_", Map.of("acme", tenantConfig("ACME_")));
            assertThat(FailoverStoreMultiTenantAutoConfiguration.resolveJdbcPrefix(props, "repeat-tenant")).isEqualTo("DEMO_");
            assertThat(FailoverStoreMultiTenantAutoConfiguration.resolveJdbcPrefix(props, "repeat-tenant")).isEqualTo("DEMO_");
        }

        private TenantConfig tenantConfig(String tablePrefix) {
            var cfg = new TenantConfig();
            cfg.setTablePrefix(tablePrefix);
            return cfg;
        }

        private FailoverProperties propsWithJdbcAndTenants(String globalPrefix, Map<String, TenantConfig> tenants) {
            var jdbc = new Jdbc();
            jdbc.setTablePrefix(globalPrefix);
            var mt = new MultiTenant();
            mt.setEnabled(true);
            mt.getTenants().putAll(tenants);
            var store = new Store();
            store.setJdbc(jdbc);
            store.setMultitenant(mt);
            var props = new FailoverProperties();
            props.setStore(store);
            return props;
        }
    }

    // ── Autoconfig wiring tests ───────────────────────────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class, TenantContextConfig.class})
    @TestPropertySource(properties = {
            "failover.store.multitenant.enabled=true",
            "failover.store.type=inmemory",
            "failover.store.async=false"
    })
    @DisplayName("when multitenant enabled with inmemory store")
    class WhenMultiTenantEnabledInmemory {

        static final String NAME = "ref-service";
        static final Instant NOW = Instant.now();

        @Autowired ApplicationContext applicationContext;
        @Autowired FailoverStore<Object> failoverStore;

        @AfterEach
        void tearDown() {
            TenantContext.clear();
        }

        void withTenant(String tenant, Runnable action) {
            TenantContext.set(tenant);
            try { action.run(); } finally { TenantContext.clear(); }
        }

        ReferentialPayload<Object> payload(String key, Object value, Instant expireOn) {
            return new ReferentialPayload<>(NAME, key, false, NOW, expireOn, value);
        }

        @Test
        @DisplayName("registers exactly one TenantStoreFactory")
        void registersExactlyOneTenantStoreFactory() {
            assertThat(applicationContext.getBeansOfType(TenantStoreFactory.class)).hasSize(1);
        }

        @Test
        @DisplayName("failoverStore is MultiTenantFailoverStore")
        void failoverStoreIsMultiTenant() {
            assertThat(applicationContext.getBean(FailoverStore.class))
                    .isInstanceOf(MultiTenantFailoverStore.class);
        }

        @Test
        @DisplayName("per-tenant chain is MultiTenantFailoverStore → DefaultFailoverStore → FailoverStoreInmemory (async=false)")
        @SuppressWarnings("unchecked")
        void perTenantChainIsDefaultWrappingInmemory() {
            MultiTenantFailoverStore<Object> multiTenant = cast(failoverStore);
            multiTenant.prewarm(Set.of("test-tenant"));
            Map<String, FailoverStore<Object>> stores =
                    (Map<String, FailoverStore<Object>>) ReflectionTestUtils.getField(multiTenant, "stores");
            FailoverStore<Object> tenantStore = requireNonNull(stores).get("test-tenant");
            assertThat(tenantStore)
                    .isInstanceOf(DefaultFailoverStore.class)
                    .isNotInstanceOf(FailoverStoreAsync.class);
            assertThat(requireNonNull(((DefaultFailoverStore<Object>) tenantStore).getFailoverStore()))
                    .isInstanceOf(FailoverStoreInmemory.class);
        }

        @Test
        @DisplayName("multitenant factory creates distinct store instances per tenant")
        void factoryCreatesDistinctStoresPerTenant() {
            TenantStoreFactory<Object> factory = applicationContext.getBean(TenantStoreFactory.class);
            assertThat(factory.create("acme")).isNotSameAs(factory.create("globex"));
        }

        @Nested
        @DisplayName("cleanByExpiry")
        class CleanByExpiry {

            @Test
            @DisplayName("evicts expired entries from all tenant stores without TenantContext")
            void evictsExpiredEntriesFromAllTenants() {
                //Given
                withTenant("acme",   () -> failoverStore.store(payload("exp-key",  "v", NOW.plusSeconds(60))));
                withTenant("acme",   () -> failoverStore.store(payload("live-key", "v", NOW.plusSeconds(7200))));
                withTenant("globex", () -> failoverStore.store(payload("exp-key",  "v", NOW.plusSeconds(60))));
                withTenant("globex", () -> failoverStore.store(payload("live-key", "v", NOW.plusSeconds(7200))));

                //When
                failoverStore.cleanByExpiry(NOW.plusSeconds(120));

                //Then
                withTenant("acme",   () -> assertThat(failoverStore.find(NAME, "exp-key")).isEmpty());
                withTenant("acme",   () -> assertThat(failoverStore.find(NAME, "live-key")).isPresent());
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, "exp-key")).isEmpty());
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, "live-key")).isPresent());
            }

            @Test
            @DisplayName("cleanByExpiry on empty store does not throw")
            void cleanByExpiryOnEmptyStoreDoesNotThrow() {
                assertThatNoException().isThrownBy(() -> failoverStore.cleanByExpiry(NOW.plusSeconds(3600)));
            }
        }
    }

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class, TenantContextConfig.class})
    @TestPropertySource(properties = {
            "failover.store.multitenant.enabled=true",
            "failover.store.type=caffeine",
            "failover.store.async=false"
    })
    @DisplayName("when multitenant enabled with caffeine store")
    class WhenMultiTenantEnabledCaffeine {

        static final String NAME = "ref-service";
        static final String KEY  = "ref-001";
        static final Instant NOW = Instant.now();

        @Autowired ApplicationContext applicationContext;
        @Autowired FailoverStore<Object> failoverStore;

        @AfterEach
        void tearDown() {
            TenantContext.clear();
        }

        void withTenant(String tenant, Runnable action) {
            TenantContext.set(tenant);
            try { action.run(); } finally { TenantContext.clear(); }
        }

        ReferentialPayload<Object> payload(String key, Object value, Instant expireOn) {
            return new ReferentialPayload<>(NAME, key, false, NOW, expireOn, value);
        }

        @Test
        @DisplayName("registers exactly one TenantStoreFactory")
        void registersExactlyOneTenantStoreFactory() {
            assertThat(applicationContext.getBeansOfType(TenantStoreFactory.class)).hasSize(1);
        }

        @Test
        @DisplayName("failoverStore is MultiTenantFailoverStore")
        void failoverStoreIsMultiTenant() {
            assertThat(applicationContext.getBean(FailoverStore.class))
                    .isInstanceOf(MultiTenantFailoverStore.class);
        }

        @Test
        @DisplayName("per-tenant chain is MultiTenantFailoverStore → DefaultFailoverStore → FailoverStoreCaffeine (async=false)")
        @SuppressWarnings("unchecked")
        void perTenantChainIsDefaultWrappingCaffeine() {
            MultiTenantFailoverStore<Object> multiTenant = cast(failoverStore);
            multiTenant.prewarm(Set.of("test-tenant"));
            Map<String, FailoverStore<Object>> stores =
                    (Map<String, FailoverStore<Object>>) ReflectionTestUtils.getField(multiTenant, "stores");
            FailoverStore<Object> tenantStore = requireNonNull(stores).get("test-tenant");
            assertThat(tenantStore)
                    .isInstanceOf(DefaultFailoverStore.class)
                    .isNotInstanceOf(FailoverStoreAsync.class);
            assertThat(requireNonNull(((DefaultFailoverStore<Object>) tenantStore).getFailoverStore()))
                    .isInstanceOf(FailoverStoreCaffeine.class);
        }

        @Test
        @DisplayName("multitenant factory creates distinct store instances per tenant")
        void factoryCreatesDistinctStoresPerTenant() {
            TenantStoreFactory<Object> factory = applicationContext.getBean(TenantStoreFactory.class);
            assertThat(factory.create("acme")).isNotSameAs(factory.create("globex"));
        }

        @Nested
        @DisplayName("cleanByExpiry — no-op for Caffeine")
        class CleanByExpiry {

            @Test
            @DisplayName("cleanByExpiry does not throw and per-tenant data remains — Caffeine manages eviction")
            void cleanByExpiryIsNoOpAndDataRemainsPerTenant() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "acme-val",   NOW.plusSeconds(3600))));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "globex-val", NOW.plusSeconds(3600))));
                assertThatNoException().isThrownBy(() -> failoverStore.cleanByExpiry(NOW.plusSeconds(3600)));
                withTenant("acme",   () -> assertThat(failoverStore.find(NAME, KEY))
                        .isPresent()
                        .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("acme-val")));
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, KEY))
                        .isPresent()
                        .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("globex-val")));
            }

            @Test
            @DisplayName("cleanByExpiry on empty store does not throw")
            void cleanByExpiryOnEmptyStoreDoesNotThrow() {
                assertThatNoException().isThrownBy(() -> failoverStore.cleanByExpiry(NOW.plusSeconds(3600)));
            }
        }
    }

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class, TenantContextConfig.class})
    @TestPropertySource(properties = {
            "failover.store.multitenant.enabled=true",
            "failover.store.type=jdbc",
            "failover.store.async=false",
            "failover.store.multitenant.strategy=table-prefix",
            "failover.store.multitenant.default-tenant=acme",
            "failover.store.jdbc.table-prefix=DEMO_",
            "failover.store.multitenant.tenants.acme.table-prefix=ACME_",
            "failover.store.multitenant.tenants.globex.table-prefix=GLOBEX_",
            "spring.sql.init.schema-locations=classpath:multitenant-jdbc-table-prefix.sql"
    })
    @DisplayName("when multitenant enabled with jdbc store")
    class WhenMultiTenantEnabledJdbc {

        static final String NAME = "order-service";
        static final Instant NOW = Instant.now();

        @Autowired ApplicationContext applicationContext;
        @Autowired FailoverStore<Object> failoverStore;
        @Autowired JdbcTemplate jdbcTemplate;

        @BeforeEach
        void setUp() {
            jdbcTemplate.update("DELETE FROM ACME_DEMO_FAILOVER_STORE");
            jdbcTemplate.update("DELETE FROM GLOBEX_DEMO_FAILOVER_STORE");
        }

        @AfterEach
        void tearDown() {
            TenantContext.clear();
        }

        void withTenant(String tenant, Runnable action) {
            TenantContext.set(tenant);
            try { action.run(); } finally { TenantContext.clear(); }
        }

        ReferentialPayload<Object> payload(String key, Object value, Instant expireOn) {
            return new ReferentialPayload<>(NAME, key, false, NOW, expireOn, value);
        }

        int countInTable(String table) {
            return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        }

        @Test
        @DisplayName("registers exactly one TenantStoreFactory")
        void registersExactlyOneTenantStoreFactory() {
            assertThat(applicationContext.getBeansOfType(TenantStoreFactory.class)).hasSize(1);
        }

        @Test
        @DisplayName("failoverStore is MultiTenantFailoverStore")
        void failoverStoreIsMultiTenant() {
            assertThat(applicationContext.getBean(FailoverStore.class))
                    .isInstanceOf(MultiTenantFailoverStore.class);
        }

        @Test
        @DisplayName("per-tenant chain is MultiTenantFailoverStore → DefaultFailoverStore → FailoverStoreJdbc (async=false)")
        @SuppressWarnings("unchecked")
        void perTenantChainIsDefaultWrappingJdbc() {
            MultiTenantFailoverStore<Object> multiTenant = cast(failoverStore);
            Map<String, FailoverStore<Object>> stores =
                    (Map<String, FailoverStore<Object>>) ReflectionTestUtils.getField(multiTenant, "stores");
            FailoverStore<Object> tenantStore = requireNonNull(stores).get("acme");
            assertThat(tenantStore)
                    .isInstanceOf(DefaultFailoverStore.class)
                    .isNotInstanceOf(FailoverStoreAsync.class);
            assertThat(requireNonNull(((DefaultFailoverStore<Object>) tenantStore).getFailoverStore()))
                    .isInstanceOf(FailoverStoreJdbc.class);
        }

        @Test
        @DisplayName("multitenant factory creates distinct store instances per tenant")
        void factoryCreatesDistinctStoresPerTenant() {
            TenantStoreFactory<Object> factory = applicationContext.getBean(TenantStoreFactory.class);
            assertThat(factory.create("acme")).isNotSameAs(factory.create("globex"));
        }

        @Nested
        @DisplayName("cleanByExpiry")
        class CleanByExpiry {

            @Test
            @DisplayName("evicts expired entries from all tenant tables without TenantContext")
            void evictsExpiredEntriesFromAllTenants() {
                withTenant("acme",   () -> failoverStore.store(payload("exp-a",  "v", NOW.plusSeconds(60))));
                withTenant("acme",   () -> failoverStore.store(payload("live-a", "v", NOW.plusSeconds(7200))));
                withTenant("globex", () -> failoverStore.store(payload("exp-g",  "v", NOW.plusSeconds(60))));
                withTenant("globex", () -> failoverStore.store(payload("live-g", "v", NOW.plusSeconds(7200))));
                failoverStore.cleanByExpiry(NOW.plusSeconds(1800));
                assertThat(countInTable("ACME_DEMO_FAILOVER_STORE")).isEqualTo(1);
                assertThat(countInTable("GLOBEX_DEMO_FAILOVER_STORE")).isEqualTo(1);
            }

            @Test
            @DisplayName("removes all expired rows when expiry covers all entries")
            void removesAllExpiredRows() {
                withTenant("acme",   () -> failoverStore.store(payload("exp-a", "v", NOW.plusSeconds(60))));
                withTenant("globex", () -> failoverStore.store(payload("exp-g", "v", NOW.plusSeconds(60))));
                failoverStore.cleanByExpiry(NOW.plusSeconds(1800));
                assertThat(countInTable("ACME_DEMO_FAILOVER_STORE")).isZero();
                assertThat(countInTable("GLOBEX_DEMO_FAILOVER_STORE")).isZero();
            }

            @Test
            @DisplayName("cleanByExpiry on empty store does not throw")
            void cleanByExpiryOnEmptyStoreDoesNotThrow() {
                assertThatNoException().isThrownBy(() -> failoverStore.cleanByExpiry(NOW.plusSeconds(3600)));
            }
        }
    }

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @TestPropertySource(properties = {
            "failover.store.multitenant.enabled=false"
    })
    @DisplayName("when multitenant disabled")
    class WhenMultiTenantDisabled {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("registers TenantStoreFactory from single-tenant autoconfig")
        void registersSingleTenantFactory() {
            assertThat(applicationContext.getBeansOfType(TenantStoreFactory.class)).hasSize(1);
        }

        @Test
        @DisplayName("failoverStore is NOT MultiTenantFailoverStore")
        void failoverStoreIsNotMultiTenant() {
            assertThat(applicationContext.getBean(FailoverStore.class))
                    .isNotInstanceOf(MultiTenantFailoverStore.class);
        }
    }

    // ── InMemory store — data behaviour ───────────────────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class, TenantContextConfig.class})
    @TestPropertySource(properties = {
            "failover.store.type=inmemory",
            "failover.store.async=false",
            "failover.store.multitenant.enabled=true",
            "failover.store.multitenant.tenants.acme.table-prefix=",
            "failover.store.multitenant.tenants.globex.table-prefix="
    })
    @DisplayName("inmemory store — multitenant")
    class InmemoryStore {

        static final String NAME = "product-service";
        static final String KEY  = "product-001";
        static final Instant NOW = Instant.now();

        @Autowired FailoverStore<Object> failoverStore;

        @AfterEach
        void tearDown() {
            for (String tenant : new String[]{"acme", "globex"}) {
                withTenant(tenant, () -> {
                    failoverStore.delete(payload(KEY,       null));
                    failoverStore.delete(payload("key-1",   null));
                    failoverStore.delete(payload("key-2",   null));
                    failoverStore.delete(payload("exp-key", null));
                    failoverStore.delete(payload("live-key",null));
                });
            }
            TenantContext.clear();
        }

        void withTenant(String tenant, Runnable action) {
            TenantContext.set(tenant);
            try { action.run(); } finally { TenantContext.clear(); }
        }

        ReferentialPayload<Object> payload(String key, Object value) {
            return new ReferentialPayload<>(NAME, key, false, NOW, NOW.plusSeconds(3600), value);
        }

        ReferentialPayload<Object> expiringPayload(String key, Instant expireOn) {
            return new ReferentialPayload<>(NAME, key, true, NOW, expireOn, "value-" + key);
        }

        @Nested
        @DisplayName("separate store per tenant")
        class SeparateStorePerTenant {

            @Test
            @DisplayName("acme data not readable by globex")
            void acmeDataNotReadableByGlobex() {
                withTenant("acme", () -> failoverStore.store(payload(KEY, "acme-val")));
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, KEY)).isEmpty());
            }

            @Test
            @DisplayName("globex data not readable by acme")
            void globexDataNotReadableByAcme() {
                withTenant("globex", () -> failoverStore.store(payload(KEY, "globex-val")));
                withTenant("acme", () -> assertThat(failoverStore.find(NAME, KEY)).isEmpty());
            }

            @Test
            @DisplayName("same key in both tenants held in independent maps")
            void sameKeyStoredByBothTenantsInIndependentMaps() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "acme-val")));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "globex-val")));
                withTenant("acme",   () -> assertThat(failoverStore.find(NAME, KEY))
                        .isPresent()
                        .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("acme-val")));
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, KEY))
                        .isPresent()
                        .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("globex-val")));
            }

            @Test
            @DisplayName("multiple keys per tenant are independent")
            void multipleKeysPerTenantAreIndependent() {
                withTenant("acme",   () -> failoverStore.store(payload("key-1", "acme-1")));
                withTenant("acme",   () -> failoverStore.store(payload("key-2", "acme-2")));
                withTenant("globex", () -> failoverStore.store(payload("key-1", "globex-1")));
                withTenant("acme",   () -> assertThat(failoverStore.find(NAME, "key-1"))
                        .isPresent()
                        .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("acme-1")));
                withTenant("acme",   () -> assertThat(failoverStore.find(NAME, "key-2"))
                        .isPresent()
                        .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("acme-2")));
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, "key-1"))
                        .isPresent()
                        .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("globex-1")));
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, "key-2")).isEmpty());
            }

            @Test
            @DisplayName("delete() removes entry only from the target tenant's map")
            void deleteRemovesEntryOnlyFromTargetTenantMap() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "acme-val")));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "globex-val")));
                withTenant("acme",   () -> failoverStore.delete(payload(KEY, null)));
                withTenant("acme",   () -> assertThat(failoverStore.find(NAME, KEY)).isEmpty());
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, KEY)).isPresent());
            }
        }

        @Nested
        @DisplayName("cleanByExpiry")
        class CleanByExpiry {

            @Test
            @DisplayName("cleanByExpiry without TenantContext does not throw")
            void cleanByExpiryWithoutContextDoesNotThrow() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "acme-val")));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "globex-val")));
                assertThatNoException().isThrownBy(() -> failoverStore.cleanByExpiry(NOW.plusSeconds(3600)));
            }

            @Test
            @DisplayName("cleanByExpiry evicts expired entries from all tenant stores")
            void evictsExpiredEntriesFromAllTenants() {
                withTenant("acme",   () -> failoverStore.store(expiringPayload("exp-key",  NOW.plusSeconds(60))));
                withTenant("acme",   () -> failoverStore.store(expiringPayload("live-key", NOW.plusSeconds(7200))));
                withTenant("globex", () -> failoverStore.store(expiringPayload("exp-key",  NOW.plusSeconds(60))));
                withTenant("globex", () -> failoverStore.store(expiringPayload("live-key", NOW.plusSeconds(7200))));
                failoverStore.cleanByExpiry(NOW.plusSeconds(120));
                withTenant("acme",   () -> assertThat(failoverStore.find(NAME, "exp-key")).isEmpty());
                withTenant("acme",   () -> assertThat(failoverStore.find(NAME, "live-key")).isPresent());
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, "exp-key")).isEmpty());
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, "live-key")).isPresent());
            }

            @Test
            @DisplayName("expiry in one tenant does not remove live entries in the other")
            void expiryDoesNotCrossTenantsForLiveEntries() {
                withTenant("acme",   () -> failoverStore.store(expiringPayload("exp-key", NOW.plusSeconds(60))));
                withTenant("globex", () -> failoverStore.store(expiringPayload("exp-key", NOW.plusSeconds(7200))));
                failoverStore.cleanByExpiry(NOW.plusSeconds(5400));
                withTenant("acme",   () -> assertThat(failoverStore.find(NAME, "exp-key")).isEmpty());
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, "exp-key")).isPresent());
            }

            @Test
            @DisplayName("cleanByExpiry on empty store does not throw")
            void cleanByExpiryOnEmptyStoreDoesNotThrow() {
                assertThatNoException().isThrownBy(() -> failoverStore.cleanByExpiry(NOW.plusSeconds(3600)));
            }
        }
    }

    // ── Caffeine store — data behaviour ───────────────────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class, TenantContextConfig.class})
    @TestPropertySource(properties = {
            "failover.store.type=caffeine",
            "failover.store.async=false",
            "failover.store.multitenant.enabled=true",
            "failover.store.multitenant.tenants.acme.table-prefix=",
            "failover.store.multitenant.tenants.globex.table-prefix="
    })
    @DisplayName("caffeine store — multitenant")
    class CaffeineStore {

        static final String NAME = "product-service";
        static final String KEY  = "product-001";
        static final Instant NOW = Instant.now();

        @Autowired FailoverStore<Object> failoverStore;

        @AfterEach
        void tearDown() {
            for (String tenant : new String[]{"acme", "globex"}) {
                withTenant(tenant, () -> {
                    failoverStore.delete(payload(KEY,      null));
                    failoverStore.delete(payload("key-1",  null));
                    failoverStore.delete(payload("key-2",  null));
                    failoverStore.delete(payload("exp",    null));
                    failoverStore.delete(payload("live",   null));
                });
            }
            TenantContext.clear();
        }

        void withTenant(String tenant, Runnable action) {
            TenantContext.set(tenant);
            try { action.run(); } finally { TenantContext.clear(); }
        }

        ReferentialPayload<Object> payload(String key, Object value) {
            return new ReferentialPayload<>(NAME, key, false, NOW, NOW.plusSeconds(3600), value);
        }

        @Nested
        @DisplayName("separate cache per tenant")
        class SeparateCachePerTenant {

            @Test
            @DisplayName("acme data not readable by globex")
            void acmeDataNotReadableByGlobex() {
                withTenant("acme", () -> failoverStore.store(payload(KEY, "acme-val")));
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, KEY)).isEmpty());
            }

            @Test
            @DisplayName("globex data not readable by acme")
            void globexDataNotReadableByAcme() {
                withTenant("globex", () -> failoverStore.store(payload(KEY, "globex-val")));
                withTenant("acme", () -> assertThat(failoverStore.find(NAME, KEY)).isEmpty());
            }

            @Test
            @DisplayName("same key in both tenants held in independent caches")
            void sameKeyStoredByBothTenantsInIndependentCaches() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "acme-val")));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "globex-val")));
                withTenant("acme",   () -> assertThat(failoverStore.find(NAME, KEY))
                        .isPresent()
                        .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("acme-val")));
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, KEY))
                        .isPresent()
                        .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("globex-val")));
            }

            @Test
            @DisplayName("multiple keys per tenant are independent")
            void multipleKeysPerTenantAreIndependent() {
                withTenant("acme",   () -> failoverStore.store(payload("key-1", "acme-1")));
                withTenant("acme",   () -> failoverStore.store(payload("key-2", "acme-2")));
                withTenant("globex", () -> failoverStore.store(payload("key-1", "globex-1")));
                withTenant("acme",   () -> assertThat(failoverStore.find(NAME, "key-1"))
                        .isPresent()
                        .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("acme-1")));
                withTenant("acme",   () -> assertThat(failoverStore.find(NAME, "key-2"))
                        .isPresent()
                        .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("acme-2")));
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, "key-1"))
                        .isPresent()
                        .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("globex-1")));
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, "key-2")).isEmpty());
            }

            @Test
            @DisplayName("delete() removes entry only from the target tenant's cache")
            void deleteRemovesEntryOnlyFromTargetTenantCache() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "acme-val")));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "globex-val")));
                withTenant("acme",   () -> failoverStore.delete(payload(KEY, null)));
                withTenant("acme",   () -> assertThat(failoverStore.find(NAME, KEY)).isEmpty());
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, KEY)).isPresent());
            }
        }

        @Nested
        @DisplayName("cleanByExpiry — no-op for Caffeine")
        class CleanByExpiryNoOp {

            @Test
            @DisplayName("cleanByExpiry without TenantContext does not throw")
            void cleanByExpiryWithoutContextDoesNotThrow() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "acme-val")));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "globex-val")));
                assertThatNoException().isThrownBy(() -> failoverStore.cleanByExpiry(NOW.plusSeconds(3600)));
            }

            @Test
            @DisplayName("data remains in cache after cleanByExpiry — eviction managed by Caffeine TTL")
            void dataRemainsAfterCleanByExpiry() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "acme-val")));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "globex-val")));
                failoverStore.cleanByExpiry(NOW.plusSeconds(3600));
                withTenant("acme",   () -> assertThat(failoverStore.find(NAME, KEY))
                        .isPresent()
                        .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("acme-val")));
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, KEY))
                        .isPresent()
                        .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("globex-val")));
            }

            @Test
            @DisplayName("cleanByExpiry on empty multitenant store does not throw")
            void cleanByExpiryOnEmptyStoreDoesNotThrow() {
                assertThatNoException().isThrownBy(() -> failoverStore.cleanByExpiry(NOW.plusSeconds(3600)));
            }
        }
    }

    // ── JDBC — table-prefix strategy ─────────────────────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class, TenantContextConfig.class})
    @TestPropertySource(properties = {
            "failover.store.type=jdbc",
            "failover.store.async=false",
            "failover.store.multitenant.enabled=true",
            "failover.store.multitenant.strategy=table-prefix",
            "failover.store.multitenant.default-tenant=acme",
            "failover.store.jdbc.table-prefix=DEMO_",
            "failover.store.multitenant.tenants.acme.table-prefix=ACME_",
            "failover.store.multitenant.tenants.globex.table-prefix=GLOBEX_",
            "spring.sql.init.schema-locations=classpath:multitenant-jdbc-table-prefix.sql"
    })
    @DisplayName("JDBC store — table-prefix strategy")
    class JdbcTablePrefixStore {

        static final String NAME = "order-service";
        static final String KEY  = "order-001";
        static final Instant NOW = Instant.now();

        @Autowired FailoverStore<Object> failoverStore;
        @Autowired JdbcTemplate jdbcTemplate;

        @BeforeEach
        void setUp() {
            jdbcTemplate.update("DELETE FROM ACME_DEMO_FAILOVER_STORE");
            jdbcTemplate.update("DELETE FROM GLOBEX_DEMO_FAILOVER_STORE");
        }

        @AfterEach
        void tearDown() {
            TenantContext.clear();
        }

        void withTenant(String tenant, Runnable action) {
            TenantContext.set(tenant);
            try { action.run(); } finally { TenantContext.clear(); }
        }

        ReferentialPayload<Object> payload(String key, Object value, Instant expireOn) {
            return new ReferentialPayload<>(NAME, key, false, NOW, expireOn, value);
        }

        int countInTable(String table) {
            return jdbcTemplate.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        }

        @Nested
        @DisplayName("prefix composition")
        class PrefixComposition {

            @Test
            @DisplayName("acme writes go to ACME_DEMO_FAILOVER_STORE (ACME_ + DEMO_)")
            void acmeWritesGoToAcmeDemoTable() {
                withTenant("acme", () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(3600))));
                assertThat(countInTable("ACME_DEMO_FAILOVER_STORE")).isEqualTo(1);
                assertThat(countInTable("GLOBEX_DEMO_FAILOVER_STORE")).isZero();
            }

            @Test
            @DisplayName("globex writes go to GLOBEX_DEMO_FAILOVER_STORE (GLOBEX_ + DEMO_)")
            void globexWritesGoToGlobexDemoTable() {
                withTenant("globex", () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(3600))));
                assertThat(countInTable("ACME_DEMO_FAILOVER_STORE")).isZero();
                assertThat(countInTable("GLOBEX_DEMO_FAILOVER_STORE")).isEqualTo(1);
            }
        }

        @Nested
        @DisplayName("data isolation")
        class DataIsolation {

            @Test
            @DisplayName("acme data not visible to globex")
            void acmeDataNotVisibleToGlobex() {
                withTenant("acme", () -> failoverStore.store(payload(KEY, "acme-val", NOW.plusSeconds(3600))));
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, KEY)).isEmpty());
            }

            @Test
            @DisplayName("globex data not visible to acme")
            void globexDataNotVisibleToAcme() {
                withTenant("globex", () -> failoverStore.store(payload(KEY, "globex-val", NOW.plusSeconds(3600))));
                withTenant("acme", () -> assertThat(failoverStore.find(NAME, KEY)).isEmpty());
            }

            @Test
            @DisplayName("same key stored by both tenants lands in separate tables")
            void sameKeyInBothTenantsLandsInSeparateTables() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "acme-val",   NOW.plusSeconds(3600))));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "globex-val", NOW.plusSeconds(3600))));
                assertThat(countInTable("ACME_DEMO_FAILOVER_STORE")).isEqualTo(1);
                assertThat(countInTable("GLOBEX_DEMO_FAILOVER_STORE")).isEqualTo(1);
            }

            @Test
            @DisplayName("find() returns correct row for each tenant")
            void findReturnsCorrectRowForEachTenant() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "acme-val",   NOW.plusSeconds(3600))));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "globex-val", NOW.plusSeconds(3600))));
                withTenant("acme",   () -> assertThat(failoverStore.find(NAME, KEY))
                        .isPresent()
                        .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("acme-val")));
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, KEY))
                        .isPresent()
                        .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("globex-val")));
            }

            @Test
            @DisplayName("delete() removes only the correct tenant's row")
            void deleteRemovesOnlyCorrectTenantRow() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(3600))));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(3600))));
                withTenant("acme",   () -> failoverStore.delete(payload(KEY, null, NOW.plusSeconds(3600))));
                assertThat(countInTable("ACME_DEMO_FAILOVER_STORE")).isZero();
                assertThat(countInTable("GLOBEX_DEMO_FAILOVER_STORE")).isEqualTo(1);
            }
        }

        @Nested
        @DisplayName("cleanByExpiry covers all tenants")
        class CleanByExpiry {

            @Test
            @DisplayName("removes expired rows from both tenant tables without TenantContext")
            void removesExpiredRowsFromBothTenantTablesWithoutContext() {
                withTenant("acme",   () -> failoverStore.store(payload("exp-a", "v", NOW.plusSeconds(60))));
                withTenant("globex", () -> failoverStore.store(payload("exp-g", "v", NOW.plusSeconds(60))));
                failoverStore.cleanByExpiry(NOW.plusSeconds(1800));
                assertThat(countInTable("ACME_DEMO_FAILOVER_STORE")).isZero();
                assertThat(countInTable("GLOBEX_DEMO_FAILOVER_STORE")).isZero();
            }

            @Test
            @DisplayName("preserves live rows while removing expired rows from both tables")
            void preservesLiveRowsWhileRemovingExpiredFromBothTables() {
                withTenant("acme",   () -> failoverStore.store(payload("exp",  "v", NOW.plusSeconds(60))));
                withTenant("acme",   () -> failoverStore.store(payload("live", "v", NOW.plusSeconds(7200))));
                withTenant("globex", () -> failoverStore.store(payload("exp",  "v", NOW.plusSeconds(60))));
                withTenant("globex", () -> failoverStore.store(payload("live", "v", NOW.plusSeconds(7200))));
                failoverStore.cleanByExpiry(NOW.plusSeconds(1800));
                assertThat(countInTable("ACME_DEMO_FAILOVER_STORE")).isEqualTo(1);
                assertThat(countInTable("GLOBEX_DEMO_FAILOVER_STORE")).isEqualTo(1);
            }

            @Test
            @DisplayName("does not clean globex when only acme has expired rows")
            void doesNotCleanGlobexWhenOnlyAcmeHasExpiredRows() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(60))));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(7200))));
                failoverStore.cleanByExpiry(NOW.plusSeconds(1800));
                assertThat(countInTable("ACME_DEMO_FAILOVER_STORE")).isZero();
                assertThat(countInTable("GLOBEX_DEMO_FAILOVER_STORE")).isEqualTo(1);
            }

            @Test
            @DisplayName("cleanByExpiry is a no-op when no rows are expired")
            void noOpWhenNoRowsAreExpired() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(3600))));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(3600))));
                failoverStore.cleanByExpiry(NOW.minusSeconds(1));
                assertThat(countInTable("ACME_DEMO_FAILOVER_STORE")).isEqualTo(1);
                assertThat(countInTable("GLOBEX_DEMO_FAILOVER_STORE")).isEqualTo(1);
            }
        }
    }

    // ── JDBC — schema strategy (AbstractRoutingDataSource) ────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class, RoutingDataSourceConfig.class})
    @TestPropertySource(properties = {
            "failover.store.type=jdbc",
            "failover.store.async=false",
            "failover.store.multitenant.enabled=true",
            "failover.store.multitenant.strategy=schema",
            "failover.store.jdbc.table-prefix=DEMO_",
            "failover.store.multitenant.tenants.acme.schema=acme",
            "failover.store.multitenant.tenants.globex.schema=globex",
            "spring.sql.init.mode=never"
    })
    @DisplayName("JDBC store — schema strategy (AbstractRoutingDataSource)")
    class JdbcSchemaStore {

        static final String NAME = "payment-service";
        static final String KEY  = "ref-001";
        static final Instant NOW = Instant.now();

        @Autowired FailoverStore<Object> failoverStore;
        @Autowired @Qualifier("acmeDataSource")   DataSource acmeDataSource;
        @Autowired @Qualifier("globexDataSource") DataSource globexDataSource;

        JdbcTemplate acmeJdbc;
        JdbcTemplate globexJdbc;

        @BeforeEach
        void setUp() {
            acmeJdbc   = new JdbcTemplate(acmeDataSource);
            globexJdbc = new JdbcTemplate(globexDataSource);
            acmeJdbc.update("DELETE FROM DEMO_FAILOVER_STORE");
            globexJdbc.update("DELETE FROM DEMO_FAILOVER_STORE");
        }

        @AfterEach
        void tearDown() {
            TenantContext.clear();
        }

        void withTenant(String tenant, Runnable action) {
            TenantContext.set(tenant);
            try { action.run(); } finally { TenantContext.clear(); }
        }

        ReferentialPayload<Object> payload(String key, Object value, Instant expireOn) {
            return new ReferentialPayload<>(NAME, key, false, NOW, expireOn, value);
        }

        int countInAcme()   { return acmeJdbc.queryForObject(  "SELECT COUNT(*) FROM DEMO_FAILOVER_STORE", Integer.class); }
        int countInGlobex() { return globexJdbc.queryForObject("SELECT COUNT(*) FROM DEMO_FAILOVER_STORE", Integer.class); }

        @Test
        @DisplayName("routing datasource consumed transparently — no library change required")
        void routingDataSourceConsumedTransparently() {
            withTenant("acme",   () -> failoverStore.store(payload(KEY, "acme-value",   NOW.plusSeconds(3600))));
            withTenant("globex", () -> failoverStore.store(payload(KEY, "globex-value", NOW.plusSeconds(3600))));
            assertThat(countInAcme()).isEqualTo(1);
            assertThat(countInGlobex()).isEqualTo(1);
        }

        @Nested
        @DisplayName("prefix composition")
        class PrefixComposition {

            @Test
            @DisplayName("SCHEMA strategy uses global prefix only — both tenants share DEMO_FAILOVER_STORE table name")
            void bothTenantsUseGlobalPrefixTable() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(3600))));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(3600))));
                assertThat(countInAcme()).isEqualTo(1);
                assertThat(countInGlobex()).isEqualTo(1);
            }

            @Test
            @DisplayName("no per-tenant prefix applied — acme does not write to ACME_DEMO_FAILOVER_STORE")
            void noPerTenantPrefixApplied() {
                withTenant("acme", () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(3600))));
                assertThat(countInAcme()).isEqualTo(1);
                assertThat(acmeJdbc.queryForObject(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'ACME_DEMO_FAILOVER_STORE'",
                        Integer.class)).isZero();
            }

            @Test
            @DisplayName("global prefix DEMO_ is applied — table is DEMO_FAILOVER_STORE not FAILOVER_STORE")
            void globalPrefixApplied() {
                withTenant("acme", () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(3600))));
                assertThat(acmeJdbc.queryForObject(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'DEMO_FAILOVER_STORE'",
                        Integer.class)).isEqualTo(1);
            }
        }

        @Nested
        @DisplayName("data isolation")
        class DataIsolation {

            @Test
            @DisplayName("acme data not visible to globex")
            void acmeDataNotVisibleToGlobex() {
                withTenant("acme", () -> failoverStore.store(payload(KEY, "acme-val", NOW.plusSeconds(3600))));
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, KEY)).isEmpty());
            }

            @Test
            @DisplayName("globex data not visible to acme")
            void globexDataNotVisibleToAcme() {
                withTenant("globex", () -> failoverStore.store(payload(KEY, "globex-val", NOW.plusSeconds(3600))));
                withTenant("acme", () -> assertThat(failoverStore.find(NAME, KEY)).isEmpty());
            }

            @Test
            @DisplayName("same key stored by both tenants lands in separate databases")
            void sameKeyInBothTenantsLandsInSeparateDatabases() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "acme-val",   NOW.plusSeconds(3600))));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "globex-val", NOW.plusSeconds(3600))));
                assertThat(countInAcme()).isEqualTo(1);
                assertThat(countInGlobex()).isEqualTo(1);
            }

            @Test
            @DisplayName("find() returns correct row per tenant via routed JdbcTemplate")
            void findReturnsCorrectRowPerTenant() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "acme-val",   NOW.plusSeconds(3600))));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "globex-val", NOW.plusSeconds(3600))));
                withTenant("acme",   () -> assertThat(failoverStore.find(NAME, KEY))
                        .isPresent()
                        .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("acme-val")));
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, KEY))
                        .isPresent()
                        .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("globex-val")));
            }

            @Test
            @DisplayName("delete() removes row from the correct schema only")
            void deleteRemovesRowFromCorrectSchemaOnly() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(3600))));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(3600))));
                withTenant("acme",   () -> failoverStore.delete(payload(KEY, null, NOW.plusSeconds(3600))));
                assertThat(countInAcme()).isZero();
                assertThat(countInGlobex()).isEqualTo(1);
            }
        }

        @Nested
        @DisplayName("cleanByExpiry")
        class CleanByExpiry {

            @Test
            @DisplayName("with explicit TenantContext routes cleanup to the correct schema only")
            void cleanByExpiryWithExplicitContextRoutesCorrectly() {
                withTenant("acme",   () -> failoverStore.store(payload("exp",  "v", NOW.plusSeconds(60))));
                withTenant("acme",   () -> failoverStore.store(payload("live", "v", NOW.plusSeconds(7200))));
                withTenant("globex", () -> failoverStore.store(payload("exp",  "v", NOW.plusSeconds(60))));
                withTenant("acme",   () -> failoverStore.cleanByExpiry(NOW.plusSeconds(1800)));
                assertThat(countInAcme()).isEqualTo(1);
                assertThat(countInGlobex()).isEqualTo(1);
            }

            @Test
            @DisplayName("covers all tenants when called per tenant with context")
            void cleanByExpiryCoversAllTenantsWhenCalledPerTenant() {
                withTenant("acme",   () -> failoverStore.store(payload("exp", "v", NOW.plusSeconds(60))));
                withTenant("globex", () -> failoverStore.store(payload("exp", "v", NOW.plusSeconds(60))));
                for (String tenant : new String[]{"acme", "globex"}) {
                    withTenant(tenant, () -> failoverStore.cleanByExpiry(NOW.plusSeconds(1800)));
                }
                assertThat(countInAcme()).isZero();
                assertThat(countInGlobex()).isZero();
            }

            @Test
            @DisplayName("preserves live rows while removing expired rows per tenant")
            void preservesLiveRowsWhileRemovingExpired() {
                withTenant("acme",   () -> failoverStore.store(payload("exp",  "v", NOW.plusSeconds(60))));
                withTenant("acme",   () -> failoverStore.store(payload("live", "v", NOW.plusSeconds(7200))));
                withTenant("globex", () -> failoverStore.store(payload("exp",  "v", NOW.plusSeconds(60))));
                withTenant("globex", () -> failoverStore.store(payload("live", "v", NOW.plusSeconds(7200))));
                for (String tenant : new String[]{"acme", "globex"}) {
                    withTenant(tenant, () -> failoverStore.cleanByExpiry(NOW.plusSeconds(1800)));
                }
                assertThat(countInAcme()).isEqualTo(1);
                assertThat(countInGlobex()).isEqualTo(1);
            }

            @Test
            @DisplayName("cleanByExpiry is a no-op when no rows are expired")
            void noOpWhenNoRowsAreExpired() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(3600))));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(3600))));
                for (String tenant : new String[]{"acme", "globex"}) {
                    withTenant(tenant, () -> failoverStore.cleanByExpiry(NOW.minusSeconds(1)));
                }
                assertThat(countInAcme()).isEqualTo(1);
                assertThat(countInGlobex()).isEqualTo(1);
            }
        }
    }

    // ── JDBC — separate datasource per tenant ─────────────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class, SeparateDatasourceConfig.class})
    @TestPropertySource(properties = {
            "failover.store.type=jdbc",
            "failover.store.async=false",
            "failover.store.multitenant.enabled=true",
            "failover.store.jdbc.table-prefix=DEMO_",
            "failover.store.multitenant.tenants.acme.table-prefix=",
            "failover.store.multitenant.tenants.globex.table-prefix=",
            "spring.sql.init.mode=never"
    })
    @DisplayName("JDBC store — separate datasource per tenant")
    class JdbcSeparateDatasourceStore {

        static final String NAME = "inventory-service";
        static final String KEY  = "sku-001";
        static final Instant NOW = Instant.now();

        @Autowired FailoverStore<Object> failoverStore;
        @Autowired @Qualifier("acmeDataSource")   DataSource acmeDataSource;
        @Autowired @Qualifier("globexDataSource") DataSource globexDataSource;

        JdbcTemplate acmeJdbc;
        JdbcTemplate globexJdbc;

        @BeforeEach
        void setUp() {
            acmeJdbc   = new JdbcTemplate(acmeDataSource);
            globexJdbc = new JdbcTemplate(globexDataSource);
            acmeJdbc.update("DELETE FROM DEMO_FAILOVER_STORE");
            globexJdbc.update("DELETE FROM DEMO_FAILOVER_STORE");
        }

        @AfterEach
        void tearDown() {
            TenantContext.clear();
        }

        void withTenant(String tenant, Runnable action) {
            TenantContext.set(tenant);
            try { action.run(); } finally { TenantContext.clear(); }
        }

        ReferentialPayload<Object> payload(String key, Object value, Instant expireOn) {
            return new ReferentialPayload<>(NAME, key, false, NOW, expireOn, value);
        }

        int countInAcme()   { return acmeJdbc.queryForObject(  "SELECT COUNT(*) FROM DEMO_FAILOVER_STORE", Integer.class); }
        int countInGlobex() { return globexJdbc.queryForObject("SELECT COUNT(*) FROM DEMO_FAILOVER_STORE", Integer.class); }

        @Nested
        @DisplayName("prefix composition")
        class PrefixComposition {

            @Test
            @DisplayName("global DEMO_ prefix — both tenants use DEMO_FAILOVER_STORE in their own DB")
            void bothTenantsUseDemoTable() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(3600))));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(3600))));
                assertThat(countInAcme()).isEqualTo(1);
                assertThat(countInGlobex()).isEqualTo(1);
            }

            @Test
            @DisplayName("no per-tenant prefix — acme table is DEMO_FAILOVER_STORE not ACME_DEMO_FAILOVER_STORE")
            void noPerTenantPrefixInTableName() {
                withTenant("acme", () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(3600))));
                assertThat(acmeJdbc.queryForObject(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'DEMO_FAILOVER_STORE'",
                        Integer.class)).isEqualTo(1);
                assertThat(acmeJdbc.queryForObject(
                        "SELECT COUNT(*) FROM INFORMATION_SCHEMA.TABLES WHERE TABLE_NAME = 'ACME_DEMO_FAILOVER_STORE'",
                        Integer.class)).isZero();
            }
        }

        @Nested
        @DisplayName("data isolation")
        class DataIsolation {

            @Test
            @DisplayName("acme data not visible to globex")
            void acmeDataNotVisibleToGlobex() {
                withTenant("acme", () -> failoverStore.store(payload(KEY, "acme-val", NOW.plusSeconds(3600))));
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, KEY)).isEmpty());
            }

            @Test
            @DisplayName("globex data not visible to acme")
            void globexDataNotVisibleToAcme() {
                withTenant("globex", () -> failoverStore.store(payload(KEY, "globex-val", NOW.plusSeconds(3600))));
                withTenant("acme", () -> assertThat(failoverStore.find(NAME, KEY)).isEmpty());
            }

            @Test
            @DisplayName("same key stored by both tenants lands in separate databases")
            void sameKeyInBothTenantsLandsInSeparateDatabases() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "acme-val",   NOW.plusSeconds(3600))));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "globex-val", NOW.plusSeconds(3600))));
                assertThat(countInAcme()).isEqualTo(1);
                assertThat(countInGlobex()).isEqualTo(1);
            }

            @Test
            @DisplayName("find() returns correct row per tenant")
            void findReturnsCorrectRowPerTenant() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "acme-val",   NOW.plusSeconds(3600))));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "globex-val", NOW.plusSeconds(3600))));
                withTenant("acme",   () -> assertThat(failoverStore.find(NAME, KEY))
                        .isPresent()
                        .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("acme-val")));
                withTenant("globex", () -> assertThat(failoverStore.find(NAME, KEY))
                        .isPresent()
                        .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("globex-val")));
            }

            @Test
            @DisplayName("delete() removes row from the correct tenant's database only")
            void deleteRemovesRowFromCorrectDatabaseOnly() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(3600))));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(3600))));
                withTenant("acme",   () -> failoverStore.delete(payload(KEY, null, NOW.plusSeconds(3600))));
                assertThat(countInAcme()).isZero();
                assertThat(countInGlobex()).isEqualTo(1);
            }
        }

        @Nested
        @DisplayName("cleanByExpiry — no TenantContext required (datasource baked in per tenant)")
        class CleanByExpiry {

            @Test
            @DisplayName("cleanByExpiry without TenantContext removes expired rows from both databases")
            void cleanByExpiryWithoutContextCoversAllTenants() {
                withTenant("acme",   () -> failoverStore.store(payload("exp-a", "v", NOW.plusSeconds(60))));
                withTenant("globex", () -> failoverStore.store(payload("exp-g", "v", NOW.plusSeconds(60))));
                failoverStore.cleanByExpiry(NOW.plusSeconds(1800));
                assertThat(countInAcme()).isZero();
                assertThat(countInGlobex()).isZero();
            }

            @Test
            @DisplayName("preserves live rows while removing expired rows across both databases")
            void preservesLiveRowsWhileRemovingExpired() {
                withTenant("acme",   () -> failoverStore.store(payload("exp",  "v", NOW.plusSeconds(60))));
                withTenant("acme",   () -> failoverStore.store(payload("live", "v", NOW.plusSeconds(7200))));
                withTenant("globex", () -> failoverStore.store(payload("exp",  "v", NOW.plusSeconds(60))));
                withTenant("globex", () -> failoverStore.store(payload("live", "v", NOW.plusSeconds(7200))));
                failoverStore.cleanByExpiry(NOW.plusSeconds(1800));
                assertThat(countInAcme()).isEqualTo(1);
                assertThat(countInGlobex()).isEqualTo(1);
            }

            @Test
            @DisplayName("does not clean globex when only acme has expired rows")
            void doesNotCleanGlobexWhenOnlyAcmeHasExpiredRows() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(60))));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(7200))));
                failoverStore.cleanByExpiry(NOW.plusSeconds(1800));
                assertThat(countInAcme()).isZero();
                assertThat(countInGlobex()).isEqualTo(1);
            }

            @Test
            @DisplayName("cleanByExpiry is a no-op when no rows are expired")
            void noOpWhenNoRowsAreExpired() {
                withTenant("acme",   () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(3600))));
                withTenant("globex", () -> failoverStore.store(payload(KEY, "v", NOW.plusSeconds(3600))));
                failoverStore.cleanByExpiry(NOW.minusSeconds(1));
                assertThat(countInAcme()).isEqualTo(1);
                assertThat(countInGlobex()).isEqualTo(1);
            }
        }
    }
}
