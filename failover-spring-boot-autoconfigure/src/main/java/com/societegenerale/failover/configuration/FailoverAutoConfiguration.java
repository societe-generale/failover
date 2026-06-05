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

import com.societegenerale.failover.aspect.FailoverAspect;
import com.societegenerale.failover.core.*;
import com.societegenerale.failover.core.clock.DefaultFailoverClock;
import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.exception.MethodExceptionHandler;
import com.societegenerale.failover.core.exception.policy.MethodExceptionPolicy;
import com.societegenerale.failover.core.exception.policy.NeverRethrowMethodExceptionPolicy;
import com.societegenerale.failover.core.exception.policy.RethrowIfNoRecoveryMethodExceptionPolicy;
import com.societegenerale.failover.core.expiry.*;
import com.societegenerale.failover.core.key.*;
import com.societegenerale.failover.core.payload.DefaultPayloadEnricher;
import com.societegenerale.failover.core.payload.PassThroughRecoveredPayloadHandler;
import com.societegenerale.failover.core.payload.PayloadEnricher;
import com.societegenerale.failover.core.payload.RecoveredPayloadHandler;
import com.societegenerale.failover.core.payload.splitter.BeanFactoryPayloadSplitterLookup;
import com.societegenerale.failover.core.payload.splitter.PayloadSplitterLookup;
import com.societegenerale.failover.core.propagator.CompositeContextPropagator;
import com.societegenerale.failover.core.propagator.ContextPropagator;
import com.societegenerale.failover.core.propagator.MdcContextPropagator;
import com.societegenerale.failover.propagator.MicrometerContextPropagator;
import com.societegenerale.failover.store.multitenant.TenantContextPropagator;
import com.societegenerale.failover.core.report.*;
import com.societegenerale.failover.core.report.manifest.*;
import com.societegenerale.failover.core.scanner.DefaultFailoverScanner;
import com.societegenerale.failover.core.scanner.FailoverScanner;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.properties.ExceptionPolicy;
import com.societegenerale.failover.properties.FailoverProperties;
import com.societegenerale.failover.properties.FailoverType;
import com.societegenerale.failover.scheduler.ExpiryCleanupScheduler;
import com.societegenerale.failover.scheduler.ReportScheduler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.EnableAspectJAutoProxy;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

/**
 * Root Spring Boot autoconfiguration for the failover framework.
 *
 * <p>Activates when {@code failover.enabled=true} (the default). Registers all core
 * failover infrastructure beans: AOP aspect, expiry policy, key generators, payload
 * enricher, recovered-payload handler, method-exception policy, schedulers, and the
 * in-memory store factory (when no other store type is configured).
 *
 * <p>Store-type-specific beans (Caffeine, JDBC) are registered by their own
 * auto-configurations ({@code FailoverCaffeineStoreAutoConfiguration},
 * {@code FailoverJdbcStoreAutoConfiguration}). The final assembled {@code FailoverStore}
 * bean is produced by {@code FailoverStoreAutoConfiguration}.
 *
 * @author Anand Manissery
 */
@AutoConfiguration
@ConditionalOnExpression("${failover.enabled:true} eq true")
@EnableConfigurationProperties(FailoverProperties.class)
@Slf4j
@EnableAspectJAutoProxy
public class FailoverAutoConfiguration {

    /** No-arg constructor for Spring autoconfiguration instantiation. */
    public FailoverAutoConfiguration() {}

    /**
     * Creates the default failover clock bean.
     *
     * @return default {@link com.societegenerale.failover.core.clock.DefaultFailoverClock}
     */
    @ConditionalOnMissingBean
    @Bean
    public FailoverClock failoverClock() {
        return new DefaultFailoverClock();
    }

    /**
     * Creates the default key generator bean.
     *
     * @return {@link DefaultKeyGenerator} using MD5-based fixed-length key hashing
     */
    @ConditionalOnMissingBean(name = "defaultKeyGenerator")
    @Bean(name = "defaultKeyGenerator")
    public KeyGenerator defaultKeyGenerator() {
        return new DefaultKeyGenerator();
    }

    /**
     * Creates the key-generator lookup bean.
     *
     * @return {@link BeanFactoryKeyGeneratorLookup} resolving named key-generator beans
     */
    @ConditionalOnMissingBean
    @Bean
    public KeyGeneratorLookup keyGeneratorLookup() {
        return new BeanFactoryKeyGeneratorLookup();
    }

    /**
     * Creates the composite failover key generator.
     *
     * @param defaultKeyGenerator fallback key generator when no named override is found
     * @param keyGeneratorLookup  lookup that resolves per-{@code @Failover} named key generators
     * @return composite {@link FailoverKeyGenerator} that delegates to named generators or the default
     */
    @ConditionalOnMissingBean(name = "failoverKeyGenerator")
    @Bean(name = "failoverKeyGenerator")
    public KeyGenerator failoverKeyGenerator(@Qualifier("defaultKeyGenerator") KeyGenerator defaultKeyGenerator, KeyGeneratorLookup keyGeneratorLookup) {
        return new FailoverKeyGenerator(defaultKeyGenerator, keyGeneratorLookup);
    }

    /**
     * Creates the failover expiry extractor bean.
     *
     * @return {@link BeanFactoryFailoverExpiryExtractor} resolving named expiry-policy beans
     */
    @ConditionalOnMissingBean
    @Bean
    public FailoverExpiryExtractor failoverExpiryExtractor() {
        return new BeanFactoryFailoverExpiryExtractor();
    }

    /**
     * Creates the default expiry policy bean.
     *
     * @param clock                  clock used to compute expiry timestamps
     * @param failoverExpiryExtractor extractor that reads expiry duration from {@code @Failover}
     * @return default expiry policy based on the annotation's configured duration
     */
    @ConditionalOnMissingBean(name = "defaultExpiryPolicy")
    @Bean
    public ExpiryPolicy<Object> defaultExpiryPolicy(FailoverClock clock, FailoverExpiryExtractor failoverExpiryExtractor) {
        return new DefaultExpiryPolicy<>(clock, failoverExpiryExtractor);
    }

    /**
     * Creates the expiry-policy lookup bean.
     *
     * @return {@link BeanFactoryExpiryPolicyLookup} resolving named expiry-policy beans
     */
    @ConditionalOnMissingBean
    @Bean
    public ExpiryPolicyLookup<Object> expiryPolicyLookup() {
        return new BeanFactoryExpiryPolicyLookup<>();
    }

    /**
     * Creates the composite failover expiry policy.
     *
     * @param defaultExpiryPolicy fallback expiry policy when no named override is found
     * @param expiryPolicyLookup  lookup that resolves per-{@code @Failover} named expiry policies
     * @return composite expiry policy that delegates to named policies or the default
     */
    @ConditionalOnMissingBean(name = "failoverExpiryPolicy")
    @Bean
    public ExpiryPolicy<Object> failoverExpiryPolicy(@Qualifier("defaultExpiryPolicy") ExpiryPolicy<Object> defaultExpiryPolicy, ExpiryPolicyLookup<Object> expiryPolicyLookup) {
        return new FailoverExpiryPolicy<>(defaultExpiryPolicy, expiryPolicyLookup);
    }

    /**
     * Creates the default payload enricher bean.
     *
     * @return default pass-through {@link DefaultPayloadEnricher}
     */
    @ConditionalOnMissingBean
    @Bean
    public PayloadEnricher<Object> payloadEnricher() {
        return new DefaultPayloadEnricher<>();
    }

    /**
     * Creates the payload-splitter lookup bean.
     *
     * @return {@link BeanFactoryPayloadSplitterLookup} resolving named splitter beans
     */
    @ConditionalOnMissingBean
    @Bean
    public PayloadSplitterLookup<Object, Object> payloadSplitterLookup() {
        return new BeanFactoryPayloadSplitterLookup<>();
    }

    /**
     * Composes the active {@link ContextPropagator} from available propagator beans:
     * <ol>
     *   <li>{@link TenantContextPropagator} — present when {@code failover.store.multitenant.enabled=true}</li>
     *   <li>{@link MicrometerContextPropagator} — present when {@code io.micrometer.tracing.Tracer} is on classpath and a {@code Tracer} bean exists</li>
     *   <li>{@link MdcContextPropagator} — always included</li>
     * </ol>
     * Declare your own {@code ContextPropagator} bean to replace this composition entirely.
     *
     * @param tenantPropagatorProvider    optional {@link TenantContextPropagator} bean
     * @param micrometerPropagatorProvider optional {@link MicrometerContextPropagator} bean
     * @return a single propagator or a {@link CompositeContextPropagator} when multiple are present
     */
    @ConditionalOnMissingBean(name = "contextPropagator")
    @Bean
    public ContextPropagator contextPropagator(
            ObjectProvider<TenantContextPropagator> tenantPropagatorProvider,
            ObjectProvider<MicrometerContextPropagator> micrometerPropagatorProvider) {
        List<ContextPropagator> propagators = new java.util.ArrayList<>();
        tenantPropagatorProvider.ifAvailable(propagators::add);
        micrometerPropagatorProvider.ifAvailable(propagators::add);
        propagators.add(new MdcContextPropagator());
        return propagators.size() == 1
                ? propagators.getFirst()
                : CompositeContextPropagator.of(propagators);
    }

    /**
     * Virtual-thread executor for parallel slice dispatch in scatter/gather operations.
     * Activated only when {@code failover.scatter.parallel=true}.
     *
     * <p>Override by declaring a bean named {@code scatterGatherExecutor}.
     *
     * @return virtual-thread {@link SimpleAsyncTaskExecutor} named {@code failover-scatter-*}
     */
    @ConditionalOnMissingBean(name = "scatterGatherExecutor")
    @ConditionalOnProperty(prefix = "failover.scatter", name = "parallel", havingValue = "true")
    @Bean(name = "scatterGatherExecutor")
    public TaskExecutor scatterGatherExecutor() {
        var executor = new SimpleAsyncTaskExecutor("failover-scatter-");
        executor.setVirtualThreads(true);
        log.info("ScatterGather executor configured with virtual threads (failover.scatter.parallel=true).");
        return executor;
    }

    /**
     * Registers a scanner that locates all {@code @Failover}-annotated methods in the configured package.
     *
     * @param failoverProperties properties providing the base package to scan
     * @return {@link DefaultFailoverScanner} that scans for {@code @Failover}-annotated methods
     */
    @ConditionalOnMissingBean
    @Bean
    public FailoverScanner failoverScanner(FailoverProperties failoverProperties) {
        return new DefaultFailoverScanner(failoverProperties.getPackageToScan());
    }

    /** Registers the default no-op {@link RecoveredPayloadHandler} that returns the payload unchanged.
     * @return pass-through {@link PassThroughRecoveredPayloadHandler} */
    @ConditionalOnMissingBean
    @Bean
    public RecoveredPayloadHandler recoveredPayloadHandler() {
        return new PassThroughRecoveredPayloadHandler();
    }

    /**
     * Registers a {@link ReportPublisher} that writes failover reports to SLF4J.
     *
     * @param clock clock used to timestamp report events
     * @return {@link LoggerReportPublisher}
     */
    @Bean
    public ReportPublisher loggerReportPublisher(FailoverClock clock) {
        return new LoggerReportPublisher(clock);
    }

    /**
     * Registers a {@link ReportPublisher} that records failover metrics (counters/gauges).
     *
     * @param clock clock used to timestamp metrics events
     * @return {@link MetricsReportPublisher}
     */
    @Bean
    public ReportPublisher metricsReportPublisher(FailoverClock clock) {
        return new MetricsReportPublisher(clock);
    }

    /**
     * Registers a composite publisher that broadcasts to every {@link ReportPublisher} in the context.
     *
     * @param reportPublishers all {@link ReportPublisher} beans in the context
     * @return composite publisher
     */
    @ConditionalOnMissingBean
    @Bean
    public CompositeReportPublisher compositeReportPublisher(List<ReportPublisher> reportPublishers) {
        return new CompositeReportPublisher(reportPublishers);
    }

    /**
     * Assembles the full {@link FailoverHandler} decorator chain:
     * {@code AdvancedFailoverHandler(ScatterGatherFailoverHandler(DefaultFailoverHandler))}.
     *
     * @param keyGenerator               composite key generator
     * @param expiryPolicy               composite expiry policy
     * @param clock                      failover clock
     * @param failoverStore              the assembled store (async/sync, single/multi-tenant)
     * @param payloadEnricher            enriches payloads on store
     * @param recoveredPayloadHandler    handles recovered (or null) payloads
     * @param reportPublisher            composite report publisher
     * @param failoverExpiryExtractor    reads expiry from {@code @Failover}
     * @param payloadSplitterLookup      looks up named splitter beans
     * @param contextPropagator          propagates thread context to scatter executor threads
     * @param scatterGatherExecutorProvider optional executor for parallel scatter (null = sequential)
     * @return assembled {@link AdvancedFailoverHandler}
     */
    @ConditionalOnMissingBean
    @Bean
    public FailoverHandler<Object> failoverHandler(
            @Qualifier("failoverKeyGenerator") KeyGenerator keyGenerator,
            @Qualifier("failoverExpiryPolicy") ExpiryPolicy<Object> expiryPolicy,
            FailoverClock clock,
            FailoverStore<Object> failoverStore,
            PayloadEnricher<Object> payloadEnricher,
            RecoveredPayloadHandler recoveredPayloadHandler,
            CompositeReportPublisher reportPublisher,
            FailoverExpiryExtractor failoverExpiryExtractor,
            PayloadSplitterLookup<Object,Object> payloadSplitterLookup,
            @Qualifier("contextPropagator") ContextPropagator contextPropagator,
            @Qualifier("scatterGatherExecutor") ObjectProvider<TaskExecutor> scatterGatherExecutorProvider) {
        var defaultHandler = new DefaultFailoverHandler<>(keyGenerator, clock, failoverStore, expiryPolicy, payloadEnricher);
        var scatterHandler = new ScatterGatherFailoverHandler<>(defaultHandler, defaultHandler, payloadSplitterLookup,
                scatterGatherExecutorProvider.getIfAvailable(), contextPropagator);
        return new AdvancedFailoverHandler<>(scatterHandler, recoveredPayloadHandler, reportPublisher, failoverExpiryExtractor);
    }

    /**
     * Registers the {@link MethodExceptionHandler} that applies the active exception policy.
     *
     * @param methodExceptionPolicy active exception policy (rethrow, never-throw, or custom)
     * @return {@link MethodExceptionHandler}
     */
    @ConditionalOnMissingBean
    @Bean
    public MethodExceptionHandler methodExceptionHandler(MethodExceptionPolicy  methodExceptionPolicy) {
        return new MethodExceptionHandler(methodExceptionPolicy);
    }

    /**
     * Registers {@link BasicFailoverExecution}, active when {@code failover.type=basic} (the default).
     *
     * @param failoverHandler        assembled failover handler
     * @param methodExceptionHandler exception handler applying the configured policy
     * @return {@link BasicFailoverExecution}
     */
    @ConditionalOnProperty(prefix = "failover", name = "type", havingValue = "basic", matchIfMissing = true)
    @ConditionalOnMissingBean
    @Bean
    public FailoverExecution<Object> failoverExecution(FailoverHandler<Object> failoverHandler, MethodExceptionHandler methodExceptionHandler) {
        log.info("FailoverExecution configured to BasicFailoverExecution. Available options are :  {{}}", (Object) FailoverType.values());
        return new BasicFailoverExecution<>(failoverHandler, methodExceptionHandler);
    }

    /**
     * Registers the AOP aspect that intercepts {@code @Failover}-annotated methods.
     *
     * @param failoverExecution the configured failover execution strategy
     * @return {@link FailoverAspect}
     */
    @ConditionalOnProperty(prefix = "failover", name = "aspect.enabled", havingValue = "true", matchIfMissing = true)
    @Bean
    public FailoverAspect<Object> failoverAspect(FailoverExecution<Object> failoverExecution) {
        return new FailoverAspect<>(failoverExecution);
    }

    /**
     * Registers a resource loader for reading classpath resources (e.g. {@code MANIFEST.MF}).
     * @return {@link ClassPathResourceLoader}
     */
    @ConditionalOnMissingBean
    @Bean
    public ResourceLoader resourceLoader() {
        return new ClassPathResourceLoader();
    }

    /**
     * Registers a caching manifest extractor for reading build metadata from {@code MANIFEST.MF}.
     *
     * @param resourceLoader resource loader for reading MANIFEST.MF
     * @return caching wrapper around {@link com.societegenerale.failover.core.report.manifest.DefaultManifestInfoExtractor}
     */
    @ConditionalOnMissingBean
    @Bean
    public ManifestInfoExtractor manifestInfoExtractor(ResourceLoader resourceLoader) {
        return new CacheableManifestInfoExtractor(new DefaultManifestInfoExtractor(resourceLoader));
    }

    /**
     * Creates the failover reporter bean that publishes a startup summary.
     *
     * @param reportPublisher        composite publisher that receives the startup report
     * @param failoverScanner        scans for all {@code @Failover}-annotated methods
     * @param clock                  clock for the report timestamp
     * @param manifestInfoExtractor  extracts build info from MANIFEST.MF
     * @param failoverExpiryExtractor reads expiry config from {@code @Failover}
     * @param failoverProperties     active failover properties for inclusion in the report
     * @return reporter that publishes a full failover summary on application startup
     */
    @ConditionalOnMissingBean
    @Bean(initMethod = "report")
    public FailoverReporter failoverReporter(CompositeReportPublisher reportPublisher, FailoverScanner failoverScanner, FailoverClock clock, ManifestInfoExtractor manifestInfoExtractor, FailoverExpiryExtractor failoverExpiryExtractor, FailoverProperties failoverProperties) {
        return new DefaultFailoverReporter(reportPublisher, failoverScanner, clock, manifestInfoExtractor, failoverExpiryExtractor, failoverProperties.additionalInfo());
    }

    @Configuration
    @ConditionalOnExpression("${failover.enabled:true} eq true")
    @ConditionalOnProperty(prefix = "failover", name = "scheduler.enabled", havingValue = "true", matchIfMissing = true)
    @EnableScheduling
    @EnableAsync
    static class FailoverSchedulingConfiguration {
        @ConditionalOnMissingBean
        @Bean
        public ReportScheduler reportScheduler(FailoverReporter failoverReporter) {
            return new ReportScheduler(failoverReporter);
        }

        @ConditionalOnMissingBean
        @Bean
        public ExpiryCleanupScheduler<Object> expiryCleanupScheduler(FailoverHandler<Object> failoverHandler) {
            return new ExpiryCleanupScheduler<>(failoverHandler);
        }
    }

    @Configuration
    @ConditionalOnExpression("${failover.enabled:true} eq true")
    static class ExceptionPolicyConfiguration {

        @ConditionalOnProperty(prefix = "failover", name = "exception-policy", havingValue = "never_throw")
        @ConditionalOnMissingBean
        @Bean
        public MethodExceptionPolicy neverRethrowMethodExceptionPolicy() {
            log.warn("MethodExceptionPolicy configured to NeverRethrowMethodExceptionPolicy. Available options are : {{}}", (Object) ExceptionPolicy.values());
            return new NeverRethrowMethodExceptionPolicy();
        }

        @ConditionalOnMissingBean
        @ConditionalOnProperty(prefix = "failover", name = "exception-policy", havingValue = "rethrow", matchIfMissing = true)
        @Bean
        public MethodExceptionPolicy rethrowIfNoRecoveryMethodExceptionPolicy() {
            return new RethrowIfNoRecoveryMethodExceptionPolicy();
        }
    }

}
