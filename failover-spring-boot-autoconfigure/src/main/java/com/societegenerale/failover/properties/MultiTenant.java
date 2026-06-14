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

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.NestedConfigurationProperty;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Multitenant failover store configuration — a {@link Store}-level concern independent of
 * the backing store technology (JDBC, Caffeine, InMemory).
 *
 * <p>When multitenant is enabled the application must provide a {@code TenantResolver} bean
 * that returns the current tenant ID for each request (or thread). The library does not
 * supply built-in resolver implementations; tenant resolution is always an application concern.
 *
 * <p>Example YAML:
 * <pre>
 * failover:
 *   store:
 *     type: jdbc
 *     jdbc:
 *       table-prefix: DEMO_
 *     multitenant:
 *       enabled: true
 *       strategy: table-prefix
 *       default-tenant: default
 *       tenants:
 *         acme:
 *           table-prefix: ACME_   # effective table = ACME_DEMO_FAILOVER_STORE
 *         globex:
 *           table-prefix: GLOBEX_
 * </pre>
 *
 * @author Anand Manissery
 */
@Getter
@Setter
public class MultiTenant {

    /** Opt-in flag. Defaults to {@code false} — zero impact on existing deployments. */
    private boolean enabled = false;

    /**
     * Fallback tenant ID when the resolver returns {@code null}.
     * If left blank and the resolver returns {@code null}, a {@code FailoverStoreException} is thrown.
     */
    private String defaultTenant;

    /**
     * Reject (rather than silently route) tenants that are not present in {@link #tenants}.
     *
     * <p>In {@code TABLE_PREFIX} mode an unconfigured tenant has an empty table prefix, so it
     * resolves to the global {@code FAILOVER_STORE} table — and <em>every</em> unconfigured tenant
     * then shares that one table, silently breaking the isolation multitenancy exists to provide.
     * When {@code true}, resolving such a tenant throws a {@code FailoverStoreException}; when
     * {@code false} (default), it is allowed but a one-time {@code WARN} is logged per tenant ID.
     * The configured {@link #defaultTenant} is always exempt (routing it to the global table is intentional).
     */
    private boolean strict = false;

    /**
     * JDBC isolation strategy. Ignored by Caffeine and InMemory stores.
     * Default: {@code TABLE_PREFIX}.
     */
    private JdbcMultiTenantStrategy strategy = JdbcMultiTenantStrategy.TABLE_PREFIX;

    /**
     * Per-tenant configuration overrides, keyed by tenant ID.
     * Caffeine and InMemory stores do not require per-tenant entries.
     */
    @NestedConfigurationProperty
    private Map<String, TenantConfig> tenants = new LinkedHashMap<>();

    /** No-arg constructor for Spring property binding. */
    public MultiTenant() {}

    /**
     * Tenant isolation strategy for the JDBC store.
     * Ignored by Caffeine and InMemory stores.
     */
    public enum JdbcMultiTenantStrategy {
        /** Separate table per tenant: {@code tenantPrefix + globalPrefix + "FAILOVER_STORE"}. */
        TABLE_PREFIX,

        /**
         * Separate schema (or separate database) per tenant.
         *
         * <p>The auto-configured {@code TenantStoreFactory} is TABLE_PREFIX-only and does NOT
         * implement schema routing. For the SCHEMA strategy you must declare your own
         * {@code TenantStoreFactory} bean (the auto-configured one is
         * {@code @ConditionalOnMissingBean} so your bean will replace it).
         *
         * <p>The recommended implementation creates a dedicated {@link javax.sql.DataSource}
         * (and therefore a separate connection pool) per tenant, and maps
         * {@code tenantId → JdbcTemplate(tenantDataSource)} inside {@code TenantStoreFactory.create()}.
         * Each {@code FailoverStoreJdbc} then owns its connection — no shared routing needed.
         *
         * <p><b>Why not {@code AbstractRoutingDataSource}?</b>
         * {@code clean()} is invoked by the expiry-cleanup scheduler on its own thread where
         * {@code TenantContext.get()} is {@code null}. A routing DataSource would resolve
         * {@code null} to the default schema and clean only that tenant's rows.
         * Per-tenant DataSources eliminate this problem because each store queries its own
         * physical database directly with no dependency on {@code TenantContext}.
         */
        SCHEMA
    }
}