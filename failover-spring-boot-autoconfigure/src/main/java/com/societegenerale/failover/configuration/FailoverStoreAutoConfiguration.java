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
import com.societegenerale.failover.core.store.DefaultFailoverStore;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.properties.FailoverProperties;
import com.societegenerale.failover.properties.MultiTenant;
import com.societegenerale.failover.properties.StoreType;
import com.societegenerale.failover.store.FailoverStoreAsync;
import com.societegenerale.failover.store.FailoverStoreCaffeine;
import com.societegenerale.failover.store.FailoverStoreInmemory;
import com.societegenerale.failover.store.FailoverStoreJdbc;
import com.societegenerale.failover.store.mapper.ReferentialPayloadRowMapper;
import com.societegenerale.failover.store.multitenant.MultiTenantFailoverStore;
import com.societegenerale.failover.store.multitenant.TenantResolver;
import com.societegenerale.failover.store.multitenant.TenantStoreFactory;
import com.societegenerale.failover.store.resolver.DatabaseResolver;
import com.societegenerale.failover.store.resolver.DefaultDatabaseResolver;
import com.societegenerale.failover.store.resolver.DefaultFailoverStoreQueryResolver;
import com.societegenerale.failover.store.resolver.FailoverStoreQueryResolver;
import com.societegenerale.failover.store.resolver.PayloadColumnResolver;
import com.societegenerale.failover.store.resolver.VarcharPayloadColumnResolver;
import com.societegenerale.failover.store.serializer.JsonSerializer;
import com.societegenerale.failover.store.serializer.Serializer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import tools.jackson.databind.ObjectMapper;

import java.util.function.UnaryOperator;

/**
 * Central assembler that creates the single {@link FailoverStore}{@code <Object>} bean by
 * combining a {@link TenantStoreFactory} (raw store) with the standard decorator chain
 * ({@link DefaultFailoverStore} + optionally {@link FailoverStoreAsync} and/or {@link MultiTenantFailoverStore}).
 *
 * <p>Also owns all single-tenant {@link TenantStoreFactory} registrations (inmemory, caffeine,
 * jdbc) via inner {@link Configuration} classes, keeping all store configuration in one place.
 *
 * <h2>Decorator chain — four modes</h2>
 *
 * <table border="1">
 *   <caption>Decorator chain modes</caption>
 *   <tr><th>multitenant.enabled</th><th>async</th><th>Chain (outermost → innermost)</th></tr>
 *   <tr><td>{@code false} (default)</td><td>{@code true} (default)</td>
 *       <td>{@code FailoverStoreAsync → DefaultFailoverStore → raw}</td></tr>
 *   <tr><td>{@code false} (default)</td><td>{@code false}</td>
 *       <td>{@code DefaultFailoverStore → raw}</td></tr>
 *   <tr><td>{@code true}</td><td>{@code true} (default)</td>
 *       <td>{@code MultiTenantFailoverStore → (per tenant) FailoverStoreAsync → DefaultFailoverStore → raw}</td></tr>
 *   <tr><td>{@code true}</td><td>{@code false}</td>
 *       <td>{@code MultiTenantFailoverStore → (per tenant) DefaultFailoverStore → raw}</td></tr>
 * </table>
 *
 * <p>{@code MultiTenantFailoverStore} is always the outermost bean in multi-tenant mode.
 * It resolves the tenant on the calling thread before any executor boundary is crossed,
 * then delegates to the correct per-tenant decorated store.
 *
 * @author Anand Manissery
 */
@AutoConfiguration(after = {
        FailoverAutoConfiguration.class,
        FailoverStoreMultiTenantAutoConfiguration.class
})
@ConditionalOnExpression("${failover.enabled:true} eq true")
@EnableConfigurationProperties(FailoverProperties.class)
@Slf4j
public class FailoverStoreAutoConfiguration {

    /**
     * Default {@link TaskExecutor} for async store operations.
     *
     * <p>Applications can override by declaring a bean named {@code failoverTaskExecutor}.
     * Uses virtual threads when available (JDK 21+), otherwise platform threads.
     *
     * @return virtual-thread {@link SimpleAsyncTaskExecutor} named {@code failover-async-*}
     */
    @Bean("failoverTaskExecutor")
    @ConditionalOnBean(TenantStoreFactory.class)
    @ConditionalOnMissingBean(name = "failoverTaskExecutor")
    @ConditionalOnProperty(prefix = "failover.store", name = "async", havingValue = "true", matchIfMissing = true)
    public TaskExecutor failoverTaskExecutor() {
        var executor = new SimpleAsyncTaskExecutor("failover-async-");
        executor.setVirtualThreads(true);
        return executor;
    }

    // ── Single-tenant ───────────────────────────────────────────────────────────

    /**
     * Async store: {@code FailoverStoreAsync(DefaultFailoverStore(raw))}.
     * Active when {@code failover.store.async=true} (default) and multitenant disabled.
     *
     * @param storeFactory        raw store factory
     * @param failoverTaskExecutor executor for async write offloading
     * @return assembled async store chain
     */
    @Bean("failoverStore")
    @ConditionalOnBean(TenantStoreFactory.class)
    @ConditionalOnMissingBean(FailoverStore.class)
    @ConditionalOnProperty(prefix = "failover.store", name = "async", havingValue = "true", matchIfMissing = true)
    @ConditionalOnProperty(prefix = "failover.store.multitenant", name = "enabled", havingValue = "false", matchIfMissing = true)
    public FailoverStore<Object> asyncFailoverStore(
            TenantStoreFactory<Object> storeFactory,
            @Qualifier("failoverTaskExecutor") TaskExecutor failoverTaskExecutor) {
        log.info("FailoverStore assembled: FailoverStoreAsync(DefaultFailoverStore(raw)) — async=true.");
        return new FailoverStoreAsync<>(new DefaultFailoverStore<>(storeFactory.create(TenantStoreFactory.SINGLE_TENANT_ID)), failoverTaskExecutor);
    }

    /**
     * Sync store: {@code DefaultFailoverStore(raw)}.
     * Active when {@code failover.store.async=false} and multitenant disabled.
     *
     * @param storeFactory raw store factory
     * @return assembled sync store chain
     */
    @Bean("failoverStore")
    @ConditionalOnBean(TenantStoreFactory.class)
    @ConditionalOnMissingBean(FailoverStore.class)
    @ConditionalOnProperty(prefix = "failover.store", name = "async", havingValue = "false")
    @ConditionalOnProperty(prefix = "failover.store.multitenant", name = "enabled", havingValue = "false", matchIfMissing = true)
    public FailoverStore<Object> syncFailoverStore(TenantStoreFactory<Object> storeFactory) {
        log.info("FailoverStore assembled: DefaultFailoverStore(raw) — async=false.");
        return new DefaultFailoverStore<>(storeFactory.create(TenantStoreFactory.SINGLE_TENANT_ID));
    }

    // ── Multi-tenant ────────────────────────────────────────────────────────────

    /**
     * Async multi-tenant store: {@code MultiTenantFailoverStore} with per-tenant
     * {@code FailoverStoreAsync(DefaultFailoverStore(raw))} decorator.
     * Active when {@code failover.store.multitenant.enabled=true} and {@code failover.store.async=true} (default).
     *
     * @param tenantResolver       resolves the current tenant ID
     * @param rawFactory           creates a raw store per tenant
     * @param props                failover properties for strategy and default-tenant config
     * @param failoverTaskExecutor executor for async per-tenant write offloading
     * @return assembled multi-tenant async store
     */
    @Bean("failoverStore")
    @ConditionalOnBean(TenantStoreFactory.class)
    @ConditionalOnMissingBean(FailoverStore.class)
    @ConditionalOnProperty(prefix = "failover.store.multitenant", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "failover.store", name = "async", havingValue = "true", matchIfMissing = true)
    public FailoverStore<Object> asyncMultiTenantFailoverStore(
            TenantResolver tenantResolver,
            TenantStoreFactory<Object> rawFactory,
            FailoverProperties props,
            @Qualifier("failoverTaskExecutor") TaskExecutor failoverTaskExecutor) {
        MultiTenant mt = props.getStore().getMultitenant();
        log.info("FailoverStore assembled: MultiTenantFailoverStore (async=true, store.type={}, strategy={}).",
                 props.getStore().getType(), mt.getStrategy());
        UnaryOperator<FailoverStore<Object>> decorator =
                raw -> new FailoverStoreAsync<>(new DefaultFailoverStore<>(raw), failoverTaskExecutor);
        var store = new MultiTenantFailoverStore<>(tenantResolver, rawFactory, decorator, mt.getDefaultTenant());
        store.prewarm(mt.getTenants().keySet());
        return store;
    }

    /**
     * Sync multi-tenant store: {@code MultiTenantFailoverStore} with per-tenant
     * {@code DefaultFailoverStore(raw)} decorator.
     * Active when {@code failover.store.multitenant.enabled=true} and {@code failover.store.async=false}.
     *
     * @param tenantResolver resolves the current tenant ID
     * @param rawFactory     creates a raw store per tenant
     * @param props          failover properties for strategy and default-tenant config
     * @return assembled multi-tenant sync store
     */
    @Bean("failoverStore")
    @ConditionalOnBean(TenantStoreFactory.class)
    @ConditionalOnMissingBean(FailoverStore.class)
    @ConditionalOnProperty(prefix = "failover.store.multitenant", name = "enabled", havingValue = "true")
    @ConditionalOnProperty(prefix = "failover.store", name = "async", havingValue = "false")
    public FailoverStore<Object> syncMultiTenantFailoverStore(
            TenantResolver tenantResolver,
            TenantStoreFactory<Object> rawFactory,
            FailoverProperties props) {
        MultiTenant mt = props.getStore().getMultitenant();
        log.info("FailoverStore assembled: MultiTenantFailoverStore (async=false, store.type={}, strategy={}).",
                 props.getStore().getType(), mt.getStrategy());
        UnaryOperator<FailoverStore<Object>> decorator =
                DefaultFailoverStore::new;
        var store = new MultiTenantFailoverStore<>(tenantResolver, rawFactory, decorator, mt.getDefaultTenant());
        store.prewarm(mt.getTenants().keySet());
        return store;
    }

    // ── Store type configurations ─────────────────────────────────────────────

    @Configuration
    @ConditionalOnExpression("${failover.enabled:true} eq true")
    @ConditionalOnProperty(prefix = "failover.store", name = "type", havingValue = "inmemory", matchIfMissing = true)
    @ConditionalOnProperty(prefix = "failover.store.multitenant", name = "enabled", havingValue = "false", matchIfMissing = true)
    @Slf4j
    static class InmemoryStoreConfiguration {

        @Bean
        @ConditionalOnMissingBean(TenantStoreFactory.class)
        public TenantStoreFactory<Object> inmemoryTenantStoreFactory() {
            log.warn("FailoverStore configured to FailoverStoreInmemory. We highly recommend to 'NOT to USE' FailoverStoreInmemory in PRODUCTION. Available options are : {{}}", (Object) StoreType.values());
            return tenantId -> new FailoverStoreInmemory<>();
        }
    }

    @Configuration
    @ConditionalOnClass(name = {"com.github.benmanes.caffeine.cache.Caffeine"})
    @ConditionalOnProperty(prefix = "failover.store", name = "type", havingValue = "caffeine")
    @ConditionalOnProperty(prefix = "failover.store.multitenant", name = "enabled", havingValue = "false", matchIfMissing = true)
    @Slf4j
    static class CaffeineStoreConfiguration {

        /**
         * Declares a {@link TenantStoreFactory} that creates a {@link FailoverStoreCaffeine} per tenant.
         * Each tenant gets its own isolated Caffeine cache with independent per-entry expiry.
         */
        @Bean
        @ConditionalOnMissingBean(TenantStoreFactory.class)
        public TenantStoreFactory<Object> caffeineTenantStoreFactory(FailoverClock failoverClock) {
            log.warn("FailoverStore configured to FailoverStoreCaffeine. This will be based on caffeine cache and hence you will have some impact on heap for high volume failover storage. Available options are : {{}}", (Object) StoreType.values());
            return tenantId -> new FailoverStoreCaffeine<>(failoverClock);
        }
    }

    /**
     * Registers all beans required for the JDBC-backed failover store.
     *
     * <p>Activated when {@code failover.store.type=jdbc}.
     * Every bean is conditional on a missing bean of the same type, so applications can
     * override any individual component by declaring their own bean.
     */
    @Configuration
    @ConditionalOnClass(name = {"javax.sql.DataSource"})
    @ConditionalOnProperty(prefix = "failover.store", name = "type", havingValue = "jdbc")
    @Slf4j
    static class JdbcStoreConfiguration {

        /**
         * Registers a {@link com.societegenerale.failover.store.mapper.ReferentialPayloadRowMapper}
         * unless a {@code RowMapper<ReferentialPayload<Object>>} bean is already present.
         */
        @Bean
        @ConditionalOnMissingBean
        public RowMapper<ReferentialPayload<Object>> rowMapper(PayloadColumnResolver payloadColumnResolver, Serializer serializer) {
            return new ReferentialPayloadRowMapper<>(payloadColumnResolver, serializer);
        }

        /**
         * Registers a {@link JsonSerializer} backed by the application's {@link ObjectMapper}
         * unless a {@link Serializer} bean is already present.
         */
        @Bean
        @ConditionalOnMissingBean
        public Serializer serializer(ObjectMapper objectMapper) {
            return new JsonSerializer(objectMapper);
        }

        /**
         * Registers a {@link com.societegenerale.failover.store.resolver.VarcharPayloadColumnResolver}
         * (VARCHAR payload column) unless a {@link PayloadColumnResolver} bean is already present.
         */
        @Bean
        @ConditionalOnMissingBean
        public PayloadColumnResolver payloadColumnHandler() {
            return new VarcharPayloadColumnResolver();
        }

        /**
         * Registers a {@link com.societegenerale.failover.store.resolver.DefaultDatabaseResolver}
         * unless a {@link DatabaseResolver} bean is already present.
         */
        @Bean
        @ConditionalOnMissingBean
        public DatabaseResolver databaseResolver(JdbcTemplate jdbcTemplate) {
            return new DefaultDatabaseResolver(jdbcTemplate);
        }

        /**
         * Registers a {@link com.societegenerale.failover.store.resolver.DefaultFailoverStoreQueryResolver}
         * configured with the table prefix from {@link FailoverProperties},
         * unless a {@link FailoverStoreQueryResolver} bean is already present.
         */
        @Bean
        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "failover.store.multitenant", name = "enabled", havingValue = "false", matchIfMissing = true)
        public FailoverStoreQueryResolver failoverStoreQueryResolver(
                FailoverProperties failoverProperties,
                Serializer serializer,
                DatabaseResolver databaseResolver,
                PayloadColumnResolver payloadColumnResolver) {
            return new DefaultFailoverStoreQueryResolver(
                    failoverProperties.getStore().getJdbc().getTablePrefix(),
                    serializer,
                    databaseResolver,
                    payloadColumnResolver);
        }

        /**
         * Registers a {@link TenantStoreFactory} that creates a {@link FailoverStoreJdbc} per tenant.
         *
         * <p>In single-tenant mode the tenant ID is {@code "_single_"} and the globally configured
         * {@link FailoverStoreQueryResolver} (with its table prefix) is used. The multitenant
         * autoconfiguration will replace this factory with a tenant-aware one when enabled.
         */
        @Bean
        @ConditionalOnMissingBean(TenantStoreFactory.class)
        @ConditionalOnProperty(prefix = "failover.store.multitenant", name = "enabled", havingValue = "false", matchIfMissing = true)
        public TenantStoreFactory<Object> jdbcTenantStoreFactory(
                JdbcTemplate jdbcTemplate,
                FailoverStoreQueryResolver failoverStoreQueryResolver,
                RowMapper<ReferentialPayload<Object>> rowMapper) {
            log.info("FailoverStore configured to FailoverStoreJdbc.");
            return tenantId -> new FailoverStoreJdbc<>(jdbcTemplate, failoverStoreQueryResolver, rowMapper);
        }
    }
}