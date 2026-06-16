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
import com.societegenerale.failover.core.observable.publisher.CompositeObservablePublisher;
import com.societegenerale.failover.core.scanner.FailoverScanner;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.DefaultFailoverStore;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.properties.FailoverProperties;
import com.societegenerale.failover.properties.MultiTenant;
import com.societegenerale.failover.properties.StoreType;
import com.societegenerale.failover.store.async.FailoverStoreAsync;
import com.societegenerale.failover.store.caffeine.FailoverStoreCaffeine;
import com.societegenerale.failover.store.inmemory.FailoverStoreInmemory;
import com.societegenerale.failover.store.jdbc.FailoverStoreJdbc;
import com.societegenerale.failover.store.jdbc.mapper.ReferentialPayloadRowMapper;
import com.societegenerale.failover.store.multitenant.MultiTenantFailoverStore;
import com.societegenerale.failover.store.multitenant.TenantResolver;
import com.societegenerale.failover.store.multitenant.TenantStoreFactory;
import com.societegenerale.failover.store.jdbc.resolver.DatabaseResolver;
import com.societegenerale.failover.store.jdbc.resolver.DefaultDatabaseResolver;
import com.societegenerale.failover.store.jdbc.resolver.DefaultFailoverStoreQueryResolver;
import com.societegenerale.failover.store.jdbc.resolver.FailoverStoreQueryResolver;
import com.societegenerale.failover.store.jdbc.resolver.PayloadColumnResolver;
import com.societegenerale.failover.store.jdbc.resolver.VarcharPayloadColumnResolver;
import com.societegenerale.failover.store.jdbc.serializer.JsonSerializer;
import com.societegenerale.failover.store.jdbc.serializer.Serializer;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
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

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
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

    /**
     * Merges the operator-configured payload-class allowlist with the packages of scanner-discovered
     * payload types. JDK packages ({@code java.*}, {@code javax.*}, {@code jakarta.*}) are never added —
     * whitelisting them would re-open the deserialization-gadget surface this control closes.
     *
     * @param configured operator entries from {@code failover.store.allowed-payload-classes}
     * @param scanner    the failover scanner, or {@code null} if unavailable
     * @return the merged allowlist (exact names + package prefixes); empty means allow-all
     */
    static List<String> mergeAllowedPayloadClasses(List<String> configured, @Nullable FailoverScanner scanner) {
        Set<String> merged = new LinkedHashSet<>(configured);
        if (scanner != null) {
            for (Class<?> payloadType : scanner.findAllPayloadTypes()) {
                String packageName = payloadType.getPackageName();
                if (!packageName.isEmpty()
                        && !packageName.startsWith("java.")
                        && !packageName.startsWith("javax.")
                        && !packageName.startsWith("jakarta.")) {
                    merged.add(packageName);
                }
            }
        }
        return List.copyOf(merged);
    }

    // ── Store assembly ──────────────────────────────────────────────────────────

    /**
     * Assembles the single {@code failoverStore} bean from the raw {@link TenantStoreFactory} and the
     * decorator chain, driven by two properties — see the four modes in the class Javadoc.
     *
     * <p>The chain is built in one place, reading top-to-bottom in invocation order:
     * <ol>
     *   <li>{@code perTenantChain} wraps a raw store in {@link DefaultFailoverStore} (defensive copy,
     *       ADR 10) and, when {@code failover.store.async=true}, in {@link FailoverStoreAsync};</li>
     *   <li>when {@code failover.store.multitenant.enabled=true}, that chain becomes the per-tenant
     *       decorator inside an outermost {@link MultiTenantFailoverStore}; otherwise it is applied
     *       directly to the single-tenant raw store.</li>
     * </ol>
     *
     * <p>This replaces the four property-gated bean variants with one method (the
     * {@code async × multitenant} matrix is expressed in code, not annotations), keeping the
     * {@link ConditionalOnMissingBean} override so consumers can still replace the whole store.
     *
     * @param storeFactory          raw per-tenant store factory
     * @param props                 failover properties (async, multitenant, type/strategy)
     * @param taskExecutorProvider  lazy {@code failoverTaskExecutor}; resolved only when async
     * @param tenantResolverProvider lazy {@link TenantResolver}; resolved only in multi-tenant mode
     * @param observablePublisher   sink for async-failure metrics
     * @return the assembled {@link FailoverStore} chain
     */
    @Bean("failoverStore")
    @ConditionalOnBean(TenantStoreFactory.class)
    @ConditionalOnMissingBean(FailoverStore.class)
    public FailoverStore<Object> failoverStore(
            TenantStoreFactory<Object> storeFactory,
            FailoverProperties props,
            @Qualifier("failoverTaskExecutor") ObjectProvider<TaskExecutor> taskExecutorProvider,
            ObjectProvider<TenantResolver> tenantResolverProvider,
            CompositeObservablePublisher observablePublisher) {

        boolean async = props.getStore().isAsync();
        MultiTenant mt = props.getStore().getMultitenant();

        // Per-tenant chain (also the entire chain in single-tenant mode):
        //   DefaultFailoverStore(raw)            — defensive copy (ADR 10)
        //   wrapped in FailoverStoreAsync(...)   — only when async=true
        UnaryOperator<FailoverStore<Object>> perTenantChain = raw -> {
            FailoverStore<Object> store = new DefaultFailoverStore<>(raw);
            return async
                    ? new FailoverStoreAsync<>(store, taskExecutorProvider.getObject(), observablePublisher)
                    : store;
        };

        if (mt.isEnabled()) {
            TenantResolver tenantResolver = tenantResolverProvider.getObject();
            log.info("FailoverStore assembled: MultiTenantFailoverStore(per-tenant {}) — async={}, store.type={}, strategy={}.",
                    async ? "FailoverStoreAsync(DefaultFailoverStore(raw))" : "DefaultFailoverStore(raw)",
                    async, props.getStore().getType(), mt.getStrategy());
            var store = new MultiTenantFailoverStore<>(tenantResolver, storeFactory, perTenantChain, mt.getDefaultTenant());
            store.prewarm(mt.getTenants().keySet());
            return store;
        }

        log.info("FailoverStore assembled: {} — async={}.",
                async ? "FailoverStoreAsync(DefaultFailoverStore(raw))" : "DefaultFailoverStore(raw)", async);
        return perTenantChain.apply(storeFactory.create(TenantStoreFactory.SINGLE_TENANT_ID));
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
        public TenantStoreFactory<Object> inmemoryTenantStoreFactory(FailoverProperties properties) {
            int maxEntries = properties.getStore().getInmemory().getMaxEntries();
            log.warn("FailoverStore configured to FailoverStoreInmemory (maxEntries={}). We highly recommend to 'NOT to USE' FailoverStoreInmemory in PRODUCTION. Available options are : {{}}", maxEntries, StoreType.values());
            return tenantId -> new FailoverStoreInmemory<>(maxEntries);
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
        public TenantStoreFactory<Object> caffeineTenantStoreFactory(FailoverClock failoverClock, FailoverProperties properties) {
            long maxSize = properties.getStore().getCaffeine().getMaxSize();
            log.warn("FailoverStore configured to FailoverStoreCaffeine (maxSize={}). This will be based on caffeine cache and hence you will have some impact on heap for high volume failover storage. Available options are : {{}}", maxSize, StoreType.values());
            return tenantId -> new FailoverStoreCaffeine<>(failoverClock, maxSize);
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
         * Registers a {@link com.societegenerale.failover.store.jdbc.mapper.ReferentialPayloadRowMapper}
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
         *
         * <p>The deserialization allowlist is resolved lazily (after the scanner has run) by merging
         * two sources: the packages of every {@code @Failover} payload type discovered by
         * {@link FailoverScanner} (secure by default, zero config) and any explicit entries in
         * {@code failover.store.allowed-payload-classes}. If both are empty the restriction is
         * disabled (allow-all) for backward compatibility.
         */
        @Bean
        @ConditionalOnMissingBean
        public Serializer serializer(ObjectMapper objectMapper,
                                     FailoverProperties failoverProperties,
                                     ObjectProvider<FailoverScanner> failoverScannerProvider) {
            List<String> configured = failoverProperties.getStore().getAllowedPayloadClasses();
            return new JsonSerializer(objectMapper,
                    () -> mergeAllowedPayloadClasses(configured, failoverScannerProvider.getIfAvailable()));
        }

        /**
         * Registers a {@link com.societegenerale.failover.store.jdbc.resolver.VarcharPayloadColumnResolver}
         * (VARCHAR payload column) unless a {@link PayloadColumnResolver} bean is already present.
         */
        @Bean
        @ConditionalOnMissingBean
        public PayloadColumnResolver payloadColumnHandler() {
            return new VarcharPayloadColumnResolver();
        }

        /**
         * Registers a {@link com.societegenerale.failover.store.jdbc.resolver.DefaultDatabaseResolver}
         * unless a {@link DatabaseResolver} bean is already present.
         */
        @Bean
        @ConditionalOnMissingBean
        public DatabaseResolver databaseResolver(JdbcTemplate jdbcTemplate) {
            return new DefaultDatabaseResolver(jdbcTemplate);
        }

        /**
         * Registers a {@link com.societegenerale.failover.store.jdbc.resolver.DefaultFailoverStoreQueryResolver}
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