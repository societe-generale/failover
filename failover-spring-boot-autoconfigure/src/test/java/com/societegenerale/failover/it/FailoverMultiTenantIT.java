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

package com.societegenerale.failover.it;

import com.societegenerale.failover.core.FailoverHandler;
import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.it.domain.ThirdParty;
import com.societegenerale.failover.it.service.RemoteThirdPartyService;
import com.societegenerale.failover.it.service.ThirdPartyService;
import com.societegenerale.failover.it.service.ThirdPartyServiceController;
import com.societegenerale.failover.store.FailoverStoreJdbc;
import com.societegenerale.failover.store.multitenant.TenantContext;
import com.societegenerale.failover.store.multitenant.TenantResolver;
import com.societegenerale.failover.store.multitenant.TenantStoreFactory;
import com.societegenerale.failover.store.resolver.DatabaseResolver;
import com.societegenerale.failover.store.resolver.DefaultDatabaseResolver;
import com.societegenerale.failover.store.resolver.DefaultFailoverStoreQueryResolver;
import com.societegenerale.failover.store.resolver.PayloadColumnResolver;
import com.societegenerale.failover.store.serializer.Serializer;
import org.assertj.core.api.RecursiveComparisonAssert;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import org.springframework.test.context.TestPropertySource;

import javax.sql.DataSource;
import java.sql.Timestamp;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end integration tests for the multi-tenant failover store against a real H2 JDBC store.
 *
 * <h2>Two strategies under test</h2>
 * <ul>
 *   <li><b>TABLE_PREFIX</b> — each tenant writes to its own table
 *       ({@code ACME_DEMO_FAILOVER_STORE} / {@code GLOBEX_DEMO_FAILOVER_STORE}).
 *       One shared H2 database; table isolation.</li>
 *   <li><b>SCHEMA</b> — each tenant is routed to its own H2 database via a custom
 *       {@link TenantStoreFactory} that provides a dedicated {@link JdbcTemplate} per tenant.
 *       Both use {@code DEMO_FAILOVER_STORE} but on different physical databases,
 *       so {@code clean()} works correctly without needing a {@link TenantContext}.</li>
 * </ul>
 *
 * <p>This class is intentionally separate from {@link FailoverAppTestIT} to avoid
 * the outer {@code @BeforeEach} deleting {@code IT_FAILOVER_STORE}, which does not
 * exist in multi-tenant contexts.
 *
 * @author Anand Manissery
 */
class FailoverMultiTenantIT {

    private static final ThirdParty TP_1 = RemoteThirdPartyService.getCopyOf("1");
    private static final ThirdParty TP_2 = RemoteThirdPartyService.getCopyOf("2");

    private static RecursiveComparisonAssert<?> assertTp(ThirdParty actual) {
        return assertThat(actual)
                .usingRecursiveComparison()
                .ignoringFields("upToDate", "asOf", "metadata");
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 1 · Multi-Tenant Store — TABLE_PREFIX strategy
    // ═════════════════════════════════════════════════════════════════════════

    @Nested
    @SpringBootTest(classes = {FailoverScatterGatherITApplication.class, MultiTenantTablePrefixIT.MultiTenantConfig.class})
    @TestPropertySource(properties = {
            "failover.exception-policy=never_throw",
            "failover.store.type=jdbc",
            "failover.store.jdbc.table-prefix=DEMO_",
            "failover.store.async=false",
            "failover.store.multitenant.enabled=true",
            "failover.store.multitenant.strategy=TABLE_PREFIX",
            "failover.store.multitenant.tenants.acme.table-prefix=ACME_",
            "failover.store.multitenant.tenants.globex.table-prefix=GLOBEX_",
            "failover.package-to-scan=com.societegenerale.failover.it",
            "spring.datasource.url=jdbc:h2:mem:failover_mt_tprefix_it;DB_CLOSE_DELAY=-1",
            "spring.datasource.driver-class-name=org.h2.Driver",
            "spring.datasource.username=sa",
            "spring.datasource.password=",
            "spring.sql.init.schema-locations=classpath:multitenant-jdbc-table-prefix.sql",
            "spring.sql.init.mode=always"
    })
    @DisplayName("10 · Multi-Tenant Store — TABLE_PREFIX strategy (ACME_DEMO_ vs GLOBEX_DEMO_FAILOVER_STORE)")
    class MultiTenantTablePrefixIT {

        private static final String FAILOVER_NAME = "it-tp-single";
        private static final String ACME_TABLE = "ACME_DEMO_FAILOVER_STORE";
        private static final String GLOBEX_TABLE = "GLOBEX_DEMO_FAILOVER_STORE";

        @Autowired
        private ThirdPartyService mtService;

        @Autowired
        private ThirdPartyServiceController mtCtrl;

        @Autowired
        private JdbcTemplate mtJdbc;

        @Autowired
        private FailoverClock mtClock;

        @Autowired
        private FailoverHandler<Object> mtFailoverHandler;

        @TestConfiguration
        static class MultiTenantConfig {
            @Bean
            public TenantResolver tenantResolver() {
                return TenantContext::get;
            }
        }

        @BeforeEach
        void reset() {
            mtCtrl.reset();
            TenantContext.clear();
            mtJdbc.update("DELETE FROM " + ACME_TABLE);
            mtJdbc.update("DELETE FROM " + GLOBEX_TABLE);
        }

        @AfterEach
        void clearTenantContext() {
            TenantContext.clear();
        }

        // ── helpers ───────────────────────────────────────────────────────────

        private ThirdParty callAs(String tenant, String id) {
            TenantContext.set(tenant);
            try {
                return mtService.fetchOne(id);
            } finally {
                TenantContext.clear();
            }
        }

        private int rowsInTable(String table) {
            return mtJdbc.queryForObject(
                    "SELECT COUNT(*) FROM " + table + " WHERE FAILOVER_NAME = ?",
                    Integer.class, FAILOVER_NAME);
        }

        private void expireAllIn(String table) {
            mtJdbc.update(
                    "UPDATE " + table + " SET EXPIRE_ON = ? WHERE FAILOVER_NAME = ?",
                    Timestamp.from(mtClock.now().minusSeconds(3600)), FAILOVER_NAME);
        }

        // ── tests ─────────────────────────────────────────────────────────────

        @Test
        @DisplayName("acme store writes only to ACME_DEMO table — GLOBEX table stays empty")
        void acmeStoreWritesOnlyToAcmeTable() {
            callAs("acme", "1");

            assertThat(rowsInTable(ACME_TABLE)).isEqualTo(1);
            assertThat(rowsInTable(GLOBEX_TABLE)).isZero();
        }

        @Test
        @DisplayName("globex store writes only to GLOBEX_DEMO table — ACME table stays empty")
        void globexStoreWritesOnlyToGlobexTable() {
            callAs("globex", "1");

            assertThat(rowsInTable(GLOBEX_TABLE)).isEqualTo(1);
            assertThat(rowsInTable(ACME_TABLE)).isZero();
        }

        @Test
        @DisplayName("cross-tenant recovery returns null — globex cannot read acme's row")
        void crossTenantRecoveryReturnsNull() {
            callAs("acme", "1");   // stores ThirdParty-1 in ACME table

            mtCtrl.simulatePrimaryFailure();
            ThirdParty recovered = callAs("globex", "1");

            assertThat(recovered).isNull();
        }

        @Test
        @DisplayName("same failover key — each tenant independently stores and recovers its own copy")
        void sameKeyEachTenantRecoveryItsOwnCopy() {
            callAs("acme", "1");   // ThirdParty-1 → ACME table
            callAs("globex", "1");   // ThirdParty-1 → GLOBEX table

            mtJdbc.update("DELETE FROM " + ACME_TABLE);  // evict only ACME

            mtCtrl.simulatePrimaryFailure();
            assertThat(callAs("acme", "1")).isNull();       // ACME table is empty
            assertTp(callAs("globex", "1")).isEqualTo(TP_1);  // GLOBEX still has it
        }

        @Test
        @DisplayName("different IDs per tenant — each tenant recovers only its own entry")
        void differentIdsEachTenantRecoveryOwnEntry() {
            callAs("acme", "1");   // ThirdParty-1 → ACME table
            callAs("globex", "2");   // ThirdParty-2 → GLOBEX table

            mtCtrl.simulatePrimaryFailure();
            assertTp(callAs("acme", "1")).isEqualTo(TP_1);
            assertThat(callAs("acme", "2")).isNull();    // acme has no row for id "2"
            assertThat(callAs("globex", "1")).isNull();    // globex has no row for id "1"
            assertTp(callAs("globex", "2")).isEqualTo(TP_2);
        }

        @Test
        @DisplayName("clean() removes expired rows from all tenant tables in one sweep")
        void cleanRemovesExpiredRowsFromAllTenantTables() {
            callAs("acme", "1");
            callAs("globex", "1");
            expireAllIn(ACME_TABLE);
            expireAllIn(GLOBEX_TABLE);

            mtFailoverHandler.clean();

            assertThat(rowsInTable(ACME_TABLE)).isZero();
            assertThat(rowsInTable(GLOBEX_TABLE)).isZero();
        }

        @Test
        @DisplayName("clean() leaves non-expired tenant rows untouched")
        void cleanLeavesNonExpiredTenantRowsUntouched() {
            callAs("acme", "1");
            callAs("globex", "1");
            expireAllIn(ACME_TABLE);  // expire only ACME

            mtFailoverHandler.clean();

            assertThat(rowsInTable(ACME_TABLE)).isZero();      // removed
            assertThat(rowsInTable(GLOBEX_TABLE)).isEqualTo(1); // still live
        }
    }

    // ═════════════════════════════════════════════════════════════════════════
    // 2 · Multi-Tenant Store — SCHEMA strategy (separate H2 DB per tenant)
    // ═════════════════════════════════════════════════════════════════════════

    /**
     * SCHEMA strategy: each tenant is backed by a dedicated H2 database.
     *
     * <p>Isolation is achieved by a custom {@link TenantStoreFactory} that creates a separate
     * {@link JdbcTemplate} (and therefore a separate JDBC connection pool) for each tenant.
     * Because each {@link FailoverStoreJdbc} owns its own {@code JdbcTemplate}, the
     * {@code clean()} sweep does not need a {@link TenantContext} — each store queries its
     * own physical database directly.
     *
     * <p>A shared-JdbcTemplate + {@code AbstractRoutingDataSource} approach would NOT work for
     * {@code clean()}, because the routing key ({@link TenantContext#get()}) is {@code null}
     * on the scheduler thread, making all cleans target only the default schema.
     */
    @Nested
    @SpringBootTest(classes = {FailoverScatterGatherITApplication.class, MultiTenantSchemaIT.SchemaConfig.class})
    @TestPropertySource(properties = {
            "failover.exception-policy=never_throw",
            "failover.store.type=jdbc",
            "failover.store.jdbc.table-prefix=DEMO_",
            "failover.store.async=false",
            "failover.store.multitenant.enabled=true",
            "failover.store.multitenant.strategy=SCHEMA",
            "failover.package-to-scan=com.societegenerale.failover.it",
            "spring.sql.init.mode=never"  // each H2 DB is initialised in @TestConfiguration
    })
    @DisplayName("11 · Multi-Tenant Store — SCHEMA strategy (separate H2 database per tenant)")
    class MultiTenantSchemaIT {

        private static final String FAILOVER_NAME = "it-tp-single";
        private static final String TABLE = "DEMO_FAILOVER_STORE";

        @Autowired
        private ThirdPartyService schemaService;

        @Autowired
        private ThirdPartyServiceController schemaCtrl;

        @Autowired
        @Qualifier("acmeDataSource")
        private DataSource acmeDs;

        @Autowired
        @Qualifier("globexDataSource")
        private DataSource globexDs;

        @Autowired
        private FailoverClock schemaClock;

        @Autowired
        private FailoverHandler<Object> schemaFailoverHandler;

        /**
         * Provides:
         * <ul>
         *   <li>Two independent H2 databases (acme / globex), each pre-loaded with the
         *       {@code DEMO_FAILOVER_STORE} schema.</li>
         *   <li>A custom {@link TenantStoreFactory} that wires a dedicated {@link JdbcTemplate}
         *       per tenant — no routing DataSource needed.</li>
         *   <li>A {@link DatabaseResolver} bean to satisfy the autoconfiguration condition
         *       without requiring a shared {@link JdbcTemplate}.</li>
         * </ul>
         */
        @TestConfiguration
        static class SchemaConfig {

            @Bean
            public TenantResolver tenantResolver() {
                return TenantContext::get;
            }

            @Bean
            @Qualifier("acmeDataSource")
            DataSource acmeDataSource() {
                return new EmbeddedDatabaseBuilder()
                        .setType(EmbeddedDatabaseType.H2)
                        .setName("mt-schema-acme-it")
                        .addScript("classpath:multitenant-jdbc-schema-tenant.sql")
                        .build();
            }

            @Bean
            @Qualifier("globexDataSource")
            DataSource globexDataSource() {
                return new EmbeddedDatabaseBuilder()
                        .setType(EmbeddedDatabaseType.H2)
                        .setName("mt-schema-globex-it")
                        .addScript("classpath:multitenant-jdbc-schema-tenant.sql")
                        .build();
            }

            @Bean
            public DatabaseResolver databaseResolver(
                    @Qualifier("acmeDataSource") DataSource acmeDs) {
                return new DefaultDatabaseResolver(new JdbcTemplate(acmeDs));
            }

            @Bean
            public TenantStoreFactory<Object> jdbcTenantStoreFactory(
                    @Qualifier("acmeDataSource") DataSource acmeDs,
                    @Qualifier("globexDataSource") DataSource globexDs,
                    Serializer serializer,
                    PayloadColumnResolver payloadColumnResolver,
                    RowMapper<ReferentialPayload<Object>> rowMapper) {
                Map<String, JdbcTemplate> jdbcByTenant = Map.of(
                        "acme", new JdbcTemplate(acmeDs),
                        "globex", new JdbcTemplate(globexDs));
                return tenantId -> {
                    JdbcTemplate jdbc = jdbcByTenant.get(tenantId);
                    if (jdbc == null) throw new IllegalArgumentException("Unknown tenant: " + tenantId);
                    DatabaseResolver dbResolver = new DefaultDatabaseResolver(jdbc);
                    var qr = new DefaultFailoverStoreQueryResolver(
                            "DEMO_", serializer, dbResolver, payloadColumnResolver);
                    return new FailoverStoreJdbc<>(jdbc, qr, rowMapper);
                };
            }
        }

        @BeforeEach
        void reset() {
            schemaCtrl.reset();
            new JdbcTemplate(acmeDs).update("DELETE FROM " + TABLE);
            new JdbcTemplate(globexDs).update("DELETE FROM " + TABLE);
        }

        @AfterEach
        void clearTenantContext() {
            TenantContext.clear();
        }

        // ── helpers ───────────────────────────────────────────────────────────

        private ThirdParty callAs(String tenant, String id) {
            TenantContext.set(tenant);
            try {
                return schemaService.fetchOne(id);
            } finally {
                TenantContext.clear();
            }
        }

        private int rowsAs(String tenant) {
            DataSource ds = "acme".equals(tenant) ? acmeDs : globexDs;
            return new JdbcTemplate(ds).queryForObject(
                    "SELECT COUNT(*) FROM " + TABLE + " WHERE FAILOVER_NAME = ?",
                    Integer.class, FAILOVER_NAME);
        }

        private void expireAllAs(String tenant) {
            DataSource ds = "acme".equals(tenant) ? acmeDs : globexDs;
            new JdbcTemplate(ds).update(
                    "UPDATE " + TABLE + " SET EXPIRE_ON = ? WHERE FAILOVER_NAME = ?",
                    Timestamp.from(schemaClock.now().minusSeconds(3600)), FAILOVER_NAME);
        }

        // ── tests ─────────────────────────────────────────────────────────────

        @Test
        @DisplayName("acme store writes to acme H2 DB — globex H2 DB remains empty")
        void acmeStoreWritesOnlyToAcmeSchema() {
            callAs("acme", "1");

            assertThat(rowsAs("acme")).isEqualTo(1);
            assertThat(rowsAs("globex")).isZero();
        }

        @Test
        @DisplayName("globex store writes to globex H2 DB — acme H2 DB remains empty")
        void globexStoreWritesOnlyToGlobexSchema() {
            callAs("globex", "1");

            assertThat(rowsAs("globex")).isEqualTo(1);
            assertThat(rowsAs("acme")).isZero();
        }

        @Test
        @DisplayName("cross-schema recovery returns null — globex cannot read acme's DB")
        void crossSchemaRecoveryReturnsNull() {
            callAs("acme", "1");   // stores in acme H2 DB

            schemaCtrl.simulatePrimaryFailure();
            ThirdParty recovered = callAs("globex", "1");

            assertThat(recovered).isNull();
        }

        @Test
        @DisplayName("each tenant fails over from its own schema independently")
        void eachTenantFailsOverFromOwnSchema() {
            callAs("acme", "1");   // ThirdParty-1 → acme DB
            callAs("globex", "2");   // ThirdParty-2 → globex DB

            schemaCtrl.simulatePrimaryFailure();
            assertTp(callAs("acme", "1")).isEqualTo(TP_1);
            assertThat(callAs("acme", "2")).isNull();    // acme DB has no id "2"
            assertThat(callAs("globex", "1")).isNull();    // globex DB has no id "1"
            assertTp(callAs("globex", "2")).isEqualTo(TP_2);
        }

        @Test
        @DisplayName("expiry in one schema does not affect live entries in the other schema")
        void expiryIsolatedPerSchema() {
            callAs("acme", "1");
            callAs("globex", "1");

            expireAllAs("acme");  // expire only acme's row

            schemaCtrl.simulatePrimaryFailure();
            assertThat(callAs("acme", "1")).isNull();       // expired → null
            assertTp(callAs("globex", "1")).isEqualTo(TP_1);  // still valid
        }

        @Test
        @DisplayName("clean() removes expired rows from all tenant schemas — separate JdbcTemplate ensures correct routing")
        void cleanRemovesExpiredRowsFromAllSchemas() {
            callAs("acme", "1");
            callAs("globex", "1");
            expireAllAs("acme");
            expireAllAs("globex");

            schemaFailoverHandler.clean();

            assertThat(rowsAs("acme")).isZero();
            assertThat(rowsAs("globex")).isZero();
        }

        @Test
        @DisplayName("clean() leaves non-expired schema rows untouched")
        void cleanLeavesNonExpiredSchemaRowsUntouched() {
            callAs("acme", "1");
            callAs("globex", "1");
            expireAllAs("acme");   // expire only acme

            schemaFailoverHandler.clean();

            assertThat(rowsAs("acme")).isZero();      // removed
            assertThat(rowsAs("globex")).isEqualTo(1); // still live
        }
    }
}
