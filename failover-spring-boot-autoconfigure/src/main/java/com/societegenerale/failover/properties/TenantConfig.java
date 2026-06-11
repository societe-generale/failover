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

package com.societegenerale.failover.properties;

import lombok.Data;

/**
 * Per-tenant configuration overrides (JDBC only).
 *
 * <h2>Effective table name (TABLE_PREFIX strategy)</h2>
 * <pre>
 *   effectivePrefix = tenantPrefix + globalPrefix
 *
 *   failover.store.jdbc.table-prefix = DEMO_
 *   failover.store.multitenant.tenants.acme.table-prefix = ACME_
 *   → effective table = ACME_DEMO_FAILOVER_STORE
 * </pre>
 *
 * <p>No DataSource configuration — the application owns and wires its own DataSources
 * (e.g. via Spring's {@code AbstractRoutingDataSource} for the SCHEMA strategy).
 *
 * @author Anand Manissery
 */
@Data
public class TenantConfig {

    /**
     * Tenant-specific table prefix prepended to the global {@code jdbc.table-prefix}.
     * Leave blank to use the global prefix only.
     */
    private String tablePrefix = "";

    /**
     * Schema (or database) name for the SCHEMA isolation strategy.
     *
     * <p>This field is a configuration placeholder — the framework never reads it.
     * Declare a custom {@code TenantStoreFactory} bean and read
     * {@code TenantContext.get()} (or this property via injected {@code FailoverProperties})
     * inside {@code create(tenantId)} to select the correct {@link javax.sql.DataSource}.
     *
     * <p>Example:
     * <pre>{@code
     * @Bean
     * public TenantStoreFactory<Object> jdbcTenantStoreFactory(...) {
     *     Map<String, JdbcTemplate> byTenant = Map.of(
     *         "acme",   new JdbcTemplate(acmeDataSource),
     *         "globex", new JdbcTemplate(globexDataSource));
     *     return tenantId -> {
     *         JdbcTemplate jdbc = byTenant.get(tenantId);
     *         var qr = new DefaultFailoverStoreQueryResolver("FAILOVER_STORE", ...);
     *         return new FailoverStoreJdbc<>(jdbc, qr, rowMapper);
     *     };
     * }
     * }</pre>
     */
    private String schema;
}