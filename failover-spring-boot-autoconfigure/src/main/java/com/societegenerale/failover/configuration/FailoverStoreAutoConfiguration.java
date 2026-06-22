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
import com.societegenerale.failover.properties.Jdbc;
import com.societegenerale.failover.properties.MultiTenant;
import com.societegenerale.failover.properties.StoreType;
import com.societegenerale.failover.store.async.BoundedTaskExecutor;
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
import com.societegenerale.failover.store.jdbc.serializer.cipher.Base64PayloadCipher;
import com.societegenerale.failover.store.jdbc.serializer.cipher.EncryptingSerializer;
import com.societegenerale.failover.store.jdbc.serializer.cipher.PayloadCipher;
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

import java.util.Arrays;
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
     * <p>Unbounded by default. When {@code failover.store.async-executor.concurrency-limit > 0} the
     * virtual-thread executor is wrapped in a {@link BoundedTaskExecutor} that caps concurrently
     * in-flight writes and applies the configured {@code rejection-policy} on overload (audit R-2);
     * accepted tasks still run on virtual threads.
     *
     * @return virtual-thread {@link SimpleAsyncTaskExecutor} (optionally bounded) named {@code failover-async-*}
     */
    @Bean("failoverTaskExecutor")
    @ConditionalOnBean(TenantStoreFactory.class)
    @ConditionalOnMissingBean(name = "failoverTaskExecutor")
    @ConditionalOnProperty(prefix = "failover.store", name = "async", havingValue = "true", matchIfMissing = true)
    public TaskExecutor failoverTaskExecutor(FailoverProperties properties) {
        var executor = new SimpleAsyncTaskExecutor("failover-async-");
        executor.setVirtualThreads(true);
        var asyncExecutor = properties.getStore().getAsyncExecutor();
        if (asyncExecutor.getConcurrencyLimit() > 0) {
            log.info("Failover async store executor bounded: concurrencyLimit={}, rejectionPolicy={}.",
                    asyncExecutor.getConcurrencyLimit(), asyncExecutor.getRejectionPolicy());
            return new BoundedTaskExecutor(executor, asyncExecutor.getConcurrencyLimit(),
                    asyncExecutor.getRejectionPolicy(), "failover-async");
        }
        return executor;
    }

    /**
     * Merges the operator-configured payload-class allowlist with the scanner-discovered payload types.
     *
     * <p><b>Exact class names, not package prefixes (audit I-02).</b> Each discovered {@code @Failover}
     * payload type is added by its <em>exact</em> fully-qualified class name, granting deserialization
     * rights to precisely that type rather than to every class sharing its package. This shrinks the
     * deserialization-gadget surface: a malicious class that merely lives in the same package as a real
     * payload is no longer implicitly trusted.
     *
     * <p>JDK / platform packages ({@code java.*}, {@code javax.*}, {@code jakarta.*}) are never added —
     * whitelisting them would re-open the very gadget surface this control closes.
     *
     * <p>Operators may still configure broader <em>package-prefix</em> grants explicitly via
     * {@code failover.store.jdbc.allowed-payload-classes} (needed for polymorphic payloads whose stored
     * runtime subtype differs from the declared return type the scanner sees). Those broad grants are
     * logged with a WARN at resolution time (see {@code JsonSerializer}).
     *
     * @param configured operator entries from {@code failover.store.jdbc.allowed-payload-classes}
     *                   (exact class names or package prefixes)
     * @param scanner    the failover scanner, or {@code null} if unavailable
     * @return the merged allowlist (exact class names from the scanner + operator entries); empty means allow-all
     */
    static List<String> mergeAllowedPayloadClasses(List<String> configured, @Nullable FailoverScanner scanner) {
        Set<String> merged = new LinkedHashSet<>(configured);
        if (scanner != null) {
            for (Class<?> payloadType : scanner.findAllPayloadTypes()) {
                String packageName = payloadType.getPackageName();
                if (packageName.startsWith("java.")
                        || packageName.startsWith("javax.")
                        || packageName.startsWith("jakarta.")) {
                    continue; // never whitelist JDK / platform types — gadget surface
                }
                merged.add(payloadType.getName()); // exact FQCN, not the package prefix (I-02)
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
            log.warn("FailoverStore configured to FailoverStoreInmemory (maxEntries={}). This store is NON-DURABLE: data is per-instance and lost on restart, so it provides NO failover protection in production. "
                    + "RECOMMENDED: use 'failover.store.type=jdbc' (durable, shared across instances) for production; 'caffeine' is acceptable only for a single-node deployment that tolerates data loss on restart. "
                    + "Available options are {}. See docs: Configuration > Store Types.", maxEntries, Arrays.toString(StoreType.values()));
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
            log.warn("FailoverStore configured to FailoverStoreCaffeine (maxSize={}). This store is NON-DURABLE and NOT shared across instances: data is per-instance, lost on restart, and high-volume storage impacts heap. "
                    + "Acceptable for a SINGLE-NODE deployment that tolerates data loss on restart. RECOMMENDED for production / multi-instance (clustered) deployments: use 'failover.store.type=jdbc' (durable, shared state). "
                    + "Available options are {}. See docs: Configuration > Store Types.", maxSize, Arrays.toString(StoreType.values()));
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
         * Built-in {@link Base64PayloadCipher} (id {@code "b64"}). Registered unless the application
         * already declares its own {@code Base64PayloadCipher}, so the {@code b64} envelope is always
         * decryptable for reads. <b>Encoding only — not encryption.</b> Declare a {@link PayloadCipher}
         * bean with a real algorithm for actual confidentiality.
         */
        @Bean
        @ConditionalOnMissingBean(Base64PayloadCipher.class)
        public Base64PayloadCipher base64PayloadCipher() {
            return new Base64PayloadCipher();
        }

        /**
         * Registers a {@link JsonSerializer} backed by the application's {@link ObjectMapper}
         * unless a {@link Serializer} bean is already present, wrapped in an
         * {@link EncryptingSerializer} so {@code PAYLOAD} values can be encrypted at rest.
         *
         * <p>The deserialization allowlist is resolved lazily (after the scanner has run) by merging
         * two sources: the exact class name of every {@code @Failover} payload type discovered by
         * {@link FailoverScanner} (secure by default, zero config — audit I-02) and any explicit
         * entries in {@code failover.store.jdbc.allowed-payload-classes}. If both are empty the restriction
         * is disabled (allow-all) for backward compatibility.
         *
         * <p>All {@link PayloadCipher} beans (the built-in {@code b64} plus any the application
         * declares) are registered for <b>reads</b>, so a store may hold a mix of plaintext and
         * differently-enciphered rows. Writes are encrypted only when
         * {@code failover.store.jdbc.encryption.enabled=true}, using the cipher whose id matches
         * {@code failover.store.jdbc.encryption.cipher}.
         */
        @Bean
        @ConditionalOnMissingBean
        public Serializer serializer(ObjectMapper objectMapper,
                                     FailoverProperties failoverProperties,
                                     ObjectProvider<FailoverScanner> failoverScannerProvider,
                                     ObjectProvider<PayloadCipher> payloadCipherProvider) {
            List<String> configured = failoverProperties.getStore().getJdbc().getAllowedPayloadClasses();
            boolean strictAllowlist = failoverProperties.getStore().getJdbc().isStrictAllowlist();
            Serializer jsonSerializer = new JsonSerializer(objectMapper,
                    () -> mergeAllowedPayloadClasses(configured, failoverScannerProvider.getIfAvailable()),
                    strictAllowlist);

            List<PayloadCipher> ciphers = payloadCipherProvider.orderedStream().toList();
            Jdbc.Encryption encryption = failoverProperties.getStore().getJdbc().getEncryption();
            PayloadCipher writeCipher = resolveWriteCipher(ciphers, encryption);
            return new EncryptingSerializer(jsonSerializer, ciphers, writeCipher);
        }

        /**
         * Resolves the active write cipher when encryption is enabled, or {@code null} (write plaintext)
         * when disabled. Fails fast if the configured cipher id is not among the registered ciphers, and
         * WARNs loudly when the active cipher is the encode-only Base64 default.
         */
        private PayloadCipher resolveWriteCipher(List<PayloadCipher> ciphers, Jdbc.Encryption encryption) {
            if (!encryption.isEnabled()) {
                log.info("Failover JDBC payload encryption is DISABLED (writes are plaintext); {} cipher(s) registered for reads: {}.",
                        ciphers.size(), ciphers.stream().map(PayloadCipher::id).toList());
                return null;
            }
            PayloadCipher writeCipher = ciphers.stream()
                    .filter(c -> c.id().equals(encryption.getCipher()))
                    .findFirst()
                    .orElseThrow(() -> new IllegalStateException(
                            "failover.store.jdbc.encryption.enabled=true but no PayloadCipher bean has id '"
                                    + encryption.getCipher() + "'. Registered ids: " + ciphers.stream().map(PayloadCipher::id).toList()
                                    + ". Declare a PayloadCipher bean with that id, or correct failover.store.jdbc.encryption.cipher."));
            if (writeCipher instanceof Base64PayloadCipher) {
                log.warn("Failover JDBC payload encryption is ENABLED with the Base64 cipher ('{}') — this is ENCODING, NOT ENCRYPTION, and provides no confidentiality. "
                        + "Declare a PayloadCipher bean with a real algorithm (e.g. AES-GCM) and set failover.store.jdbc.encryption.cipher to its id.", Base64PayloadCipher.ID);
            } else {
                log.info("Failover JDBC payload encryption is ENABLED; new writes use cipher '{}'.", writeCipher.id());
            }
            return writeCipher;
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