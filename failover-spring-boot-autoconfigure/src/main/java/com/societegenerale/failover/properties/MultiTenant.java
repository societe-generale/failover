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
     * JDBC isolation strategy. Ignored by Caffeine and InMemory stores.
     * Default: {@code TABLE_PREFIX}.
     */
    private JdbcMultiTenantStrategy strategy = JdbcMultiTenantStrategy.TABLE_PREFIX;

    /**
     * Fallback tenant ID when the resolver returns {@code null}.
     * If left blank and the resolver returns {@code null}, a {@code FailoverStoreException} is thrown.
     */
    private String defaultTenant;

    /**
     * Per-tenant configuration overrides, keyed by tenant ID.
     * Caffeine and InMemory stores do not require per-tenant entries.
     */
    @NestedConfigurationProperty
    private Map<String, TenantConfig> tenants = new LinkedHashMap<>();

    public enum JdbcMultiTenantStrategy {
        /** Separate table per tenant: {@code tenantPrefix + globalPrefix + "FAILOVER_STORE"}. */
        TABLE_PREFIX,
        /**
         * Separate schema per tenant. The application provides an {@code AbstractRoutingDataSource}
         * that routes to the correct schema using {@code TenantContext.get()} as the lookup key.
         */
        SCHEMA
    }
}