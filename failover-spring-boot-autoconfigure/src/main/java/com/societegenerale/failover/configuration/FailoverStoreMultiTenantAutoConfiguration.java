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

import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.propagator.CompositeContextPropagator;
import com.societegenerale.failover.core.store.FailoverStoreException;
import com.societegenerale.failover.properties.FailoverProperties;
import com.societegenerale.failover.properties.MultiTenant;
import com.societegenerale.failover.properties.TenantConfig;
import com.societegenerale.failover.store.caffeine.FailoverStoreCaffeine;
import com.societegenerale.failover.store.jdbc.FailoverStoreJdbc;
import com.societegenerale.failover.store.inmemory.FailoverStoreInmemory;
import com.societegenerale.failover.store.multitenant.TenantContextPropagator;
import com.societegenerale.failover.store.multitenant.TenantStoreFactory;
import com.societegenerale.failover.store.jdbc.resolver.DatabaseResolver;
import com.societegenerale.failover.store.jdbc.resolver.DefaultFailoverStoreQueryResolver;
import com.societegenerale.failover.store.jdbc.resolver.PayloadColumnResolver;
import com.societegenerale.failover.store.jdbc.serializer.Serializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Autoconfiguration that activates when {@code failover.store.multitenant.enabled=true}.
 *
 * <p>Registers a multitenant-aware {@link TenantStoreFactory} for the configured store type.
 * The application is responsible for providing a {@code TenantResolver} bean that returns
 * the current tenant ID — this library does not supply built-in resolver implementations
 * (header, security-context, etc.) because tenant resolution is always an application concern.
 *
 * @author Anand Manissery
 */
@AutoConfiguration(after = {
        FailoverAutoConfiguration.class
})
@ConditionalOnExpression("${failover.enabled:true} eq true and ${failover.store.multitenant.enabled:false} eq true")
@EnableConfigurationProperties(FailoverProperties.class)
@Slf4j
public class FailoverStoreMultiTenantAutoConfiguration {

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /** Tenants already warned about (once each) to avoid log spam under load. */
    private static final Set<String> WARNED_UNCONFIGURED_TENANTS = ConcurrentHashMap.newKeySet();


    /**
     * Registers {@link TenantContextPropagator} so that scatter/gather slice tasks dispatched to
     * executor threads carry the correct tenant ID. Picked up by {@link FailoverAutoConfiguration}
     * and composed into the {@link CompositeContextPropagator}.
     *
     * @return {@link TenantContextPropagator} that captures and restores the tenant ID across thread boundaries
     */
    @Bean
    @ConditionalOnMissingBean
    public TenantContextPropagator tenantContextPropagator() {
        return new TenantContextPropagator();
    }

    // ─── TenantStoreFactory (per store type) ─────────────────────────────────

    /**
     * Auto-configured JDBC {@link TenantStoreFactory} for the {@code TABLE_PREFIX} strategy.
     *
     * <p>All tenants share the application's single {@link JdbcTemplate}. Per-tenant isolation
     * is achieved purely via a different table name
     * ({@code tenantPrefix + globalPrefix + "FAILOVER_STORE"}).
     *
     * <p><b>SCHEMA strategy requires a custom {@link TenantStoreFactory}.</b>
     * This bean is {@code @ConditionalOnMissingBean(TenantStoreFactory.class)} — declare your
     * own {@code TenantStoreFactory} bean and it will replace this one. In the SCHEMA factory,
     * map each {@code tenantId} to a dedicated {@link JdbcTemplate} backed by that tenant's
     * own {@link javax.sql.DataSource}. See {@link com.societegenerale.failover.properties.TenantConfig}
     * ({@code schema} field) for a usage example.
     *
     * @param props                 failover properties containing per-tenant configuration
     * @param jdbcTemplate          shared JDBC template (all tenants use the same DataSource in TABLE_PREFIX mode)
     * @param serializer            payload serializer
     * @param databaseResolver      detects the database product for merge dialect selection
     * @param payloadColumnResolver determines the SQL type of the PAYLOAD column
     * @param rowMapper             maps result-set rows to {@link com.societegenerale.failover.core.payload.ReferentialPayload}
     * @return per-tenant JDBC store factory using the TABLE_PREFIX strategy
     */
    @Bean
    @ConditionalOnMissingBean(TenantStoreFactory.class)
    @ConditionalOnProperty(prefix = "failover.store", name = "type", havingValue = "jdbc")
    public TenantStoreFactory<Object> jdbcMultiTenantStoreFactory(
            FailoverProperties props,
            JdbcTemplate jdbcTemplate,
            Serializer serializer,
            DatabaseResolver databaseResolver,
            PayloadColumnResolver payloadColumnResolver,
            RowMapper<ReferentialPayload<Object>> rowMapper) {
        log.info("MultiTenant TenantStoreFactory: JDBC (strategy={})", props.getStore().getMultitenant().getStrategy());
        return tenantId -> {
            String effectivePrefix = resolveJdbcPrefix(props, tenantId);
            var qr = new DefaultFailoverStoreQueryResolver(effectivePrefix, serializer, databaseResolver, payloadColumnResolver);
            return new FailoverStoreJdbc<>(jdbcTemplate, qr, rowMapper);
        };
    }

    /**
     * Registers a per-tenant Caffeine store factory (one isolated cache per tenant).
     *
     * @param failoverClock clock used by each per-tenant Caffeine cache for expiry
     * @return per-tenant Caffeine store factory
     */
    @Bean
    @ConditionalOnMissingBean(TenantStoreFactory.class)
    @ConditionalOnProperty(prefix = "failover.store", name = "type", havingValue = "caffeine")
    public TenantStoreFactory<Object> caffeineMultiTenantStoreFactory(FailoverClock failoverClock, FailoverProperties properties) {
        long maxSize = properties.getStore().getCaffeine().getMaxSize();
        log.info("MultiTenant TenantStoreFactory: Caffeine (one cache per tenant, maxSize={})", maxSize);
        return tenantId -> new FailoverStoreCaffeine<>(failoverClock, maxSize);
    }

    /**
     * Registers a per-tenant in-memory store factory (one independent map per tenant).
     *
     * @return per-tenant in-memory store factory
     */
    @Bean
    @ConditionalOnMissingBean(TenantStoreFactory.class)
    @ConditionalOnProperty(prefix = "failover.store", name = "type",
                           havingValue = "inmemory", matchIfMissing = true)
    public TenantStoreFactory<Object> inmemoryMultiTenantStoreFactory(FailoverProperties properties) {
        int maxEntries = properties.getStore().getInmemory().getMaxEntries();
        log.info("MultiTenant TenantStoreFactory: InMemory (one map per tenant, maxEntries={})", maxEntries);
        return tenantId -> new FailoverStoreInmemory<>(maxEntries);
    }

    /**
     * Computes the effective JDBC table prefix for a given tenant.
     *
     * <p>Rule: {@code effectivePrefix = tenantPrefix + globalPrefix}
     * <br>Example: {@code "ACME_" + "DEMO_" = "ACME_DEMO_"}  → table {@code ACME_DEMO_FAILOVER_STORE}
     *
     * <p>A tenant absent from {@code failover.store.multitenant.tenants} has no prefix and would
     * resolve to the shared global table, silently co-mingling its data with every other
     * unconfigured tenant. Such a tenant is rejected when {@code multitenant.strict=true}, otherwise
     * allowed with a one-time WARN. The configured {@code defaultTenant} is exempt (its routing to
     * the global table is intentional).
     */
    static String resolveJdbcPrefix(FailoverProperties props, String tenantId) {
        String globalPrefix = props.getStore().getJdbc().getTablePrefix();
        MultiTenant mt = props.getStore().getMultitenant();
        guardUnconfiguredTenant(mt, tenantId, globalPrefix);
        String tenantPrefix = mt.getTenants()
                .getOrDefault(tenantId, new TenantConfig())
                .getTablePrefix();
        return tenantPrefix + globalPrefix;
    }

    private static void guardUnconfiguredTenant(MultiTenant mt, String tenantId, String globalPrefix) {
        boolean isDefaultTenant = tenantId != null && tenantId.equals(mt.getDefaultTenant());
        if (isDefaultTenant || mt.getTenants().containsKey(tenantId)) {
            return;
        }
        String msg = "Tenant '%s' is not configured in failover.store.multitenant.tenants; in TABLE_PREFIX mode it resolves to the shared global table '%sFAILOVER_STORE', co-mingling its data with every other unconfigured tenant."
                .formatted(tenantId, globalPrefix);
        if (mt.isStrict()) {
            throw new FailoverStoreException(msg + " Refusing to route it (failover.store.multitenant.strict=true).");
        }
        // tenantId may be null here; the warned-set is a ConcurrentHashMap key-set and rejects null,
        // so short-circuit and warn unconditionally for the (abnormal) null case.
        if (tenantId == null || WARNED_UNCONFIGURED_TENANTS.add(tenantId)) {
            log.warn("{} Configure the tenant, or set failover.store.multitenant.strict=true to fail fast.", msg);
        }
    }
}