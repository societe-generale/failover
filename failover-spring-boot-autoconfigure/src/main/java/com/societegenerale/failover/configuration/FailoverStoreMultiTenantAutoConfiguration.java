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

package com.societegenerale.failover.configuration;

import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.propagator.CompositeContextPropagator;
import com.societegenerale.failover.properties.FailoverProperties;
import com.societegenerale.failover.properties.MultiTenant;
import com.societegenerale.failover.properties.TenantConfig;
import com.societegenerale.failover.store.FailoverStoreCaffeine;
import com.societegenerale.failover.store.FailoverStoreJdbc;
import com.societegenerale.failover.store.FailoverStoreInmemory;
import com.societegenerale.failover.store.multitenant.TenantContextPropagator;
import com.societegenerale.failover.store.multitenant.TenantStoreFactory;
import com.societegenerale.failover.store.resolver.DatabaseResolver;
import com.societegenerale.failover.store.resolver.DefaultFailoverStoreQueryResolver;
import com.societegenerale.failover.store.resolver.PayloadColumnResolver;
import com.societegenerale.failover.store.serializer.Serializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

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

    /**
     * Registers {@link TenantContextPropagator} so that scatter/gather slice tasks dispatched to
     * executor threads carry the correct tenant ID. Picked up by {@link FailoverAutoConfiguration}
     * and composed into the {@link CompositeContextPropagator}.
     */
    @Bean
    @ConditionalOnMissingBean
    public TenantContextPropagator tenantContextPropagator() {
        return new TenantContextPropagator();
    }

    // ─── TenantStoreFactory (per store type) ─────────────────────────────────

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

    @Bean
    @ConditionalOnMissingBean(TenantStoreFactory.class)
    @ConditionalOnProperty(prefix = "failover.store", name = "type", havingValue = "caffeine")
    public TenantStoreFactory<Object> caffeineMultiTenantStoreFactory(FailoverClock failoverClock) {
        log.info("MultiTenant TenantStoreFactory: Caffeine (one cache per tenant)");
        return _ -> new FailoverStoreCaffeine<>(failoverClock);
    }

    @Bean
    @ConditionalOnMissingBean(TenantStoreFactory.class)
    @ConditionalOnProperty(prefix = "failover.store", name = "type",
                           havingValue = "inmemory", matchIfMissing = true)
    public TenantStoreFactory<Object> inmemoryMultiTenantStoreFactory() {
        log.info("MultiTenant TenantStoreFactory: InMemory (one map per tenant)");
        return _ -> new FailoverStoreInmemory<>();
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Computes the effective JDBC table prefix for a given tenant.
     *
     * <p>Rule: {@code effectivePrefix = tenantPrefix + globalPrefix}
     * <br>Example: {@code "ACME_" + "DEMO_" = "ACME_DEMO_"}  → table {@code ACME_DEMO_FAILOVER_STORE}
     */
    static String resolveJdbcPrefix(FailoverProperties props, String tenantId) {
        String globalPrefix = props.getStore().getJdbc().getTablePrefix();
        MultiTenant mt = props.getStore().getMultitenant();
        String tenantPrefix = mt.getTenants()
                .getOrDefault(tenantId, new TenantConfig())
                .getTablePrefix();
        return tenantPrefix + globalPrefix;
    }
}