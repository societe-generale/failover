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
import com.societegenerale.failover.core.observable.publisher.CompositeObservablePublisher;
import com.societegenerale.failover.core.observable.publisher.MdcLoggerObservablePublisher;
import com.societegenerale.failover.core.observable.publisher.ObservablePublisher;
import com.societegenerale.failover.core.payload.DefaultPayloadEnricher;
import com.societegenerale.failover.core.payload.PassThroughRecoveredPayloadHandler;
import com.societegenerale.failover.core.payload.PayloadEnricher;
import com.societegenerale.failover.core.payload.RecoveredPayloadHandler;
import com.societegenerale.failover.lookup.BeanFactoryExpiryPolicyLookup;
import com.societegenerale.failover.lookup.BeanFactoryFailoverExpiryExtractor;
import com.societegenerale.failover.lookup.BeanFactoryKeyGeneratorLookup;
import com.societegenerale.failover.lookup.BeanFactoryPayloadSplitterLookup;
import com.societegenerale.failover.core.payload.splitter.PayloadSplitterLookup;
import com.societegenerale.failover.core.propagator.CompositeContextPropagator;
import com.societegenerale.failover.core.propagator.ContextPropagator;
import com.societegenerale.failover.core.propagator.MdcContextPropagator;
import com.societegenerale.failover.propagator.MicrometerContextPropagator;
import com.societegenerale.failover.store.multitenant.TenantContextPropagator;
import com.societegenerale.failover.core.observable.*;
import com.societegenerale.failover.core.observable.manifest.*;
import com.societegenerale.failover.core.scanner.FailoverScanner;
import com.societegenerale.failover.observable.micrometer.health.FailoverHealthIndicator;
import com.societegenerale.failover.scanner.SpringContextFailoverScanner;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.properties.ExceptionPolicy;
import com.societegenerale.failover.properties.FailoverProperties;
import com.societegenerale.failover.properties.FailoverType;
import com.societegenerale.failover.store.async.BoundedTaskExecutor;
import com.societegenerale.failover.scheduler.ExpiryCleanupScheduler;
import com.societegenerale.failover.scheduler.ObservableScheduler;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
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

import java.util.ArrayList;
import java.util.List;

/**
 * Root Spring Boot autoconfiguration for the failover framework.
 *
 * <p>Activates when {@code failover.enabled=true} (the default). Registers all core
 * failover infrastructure beans: AOP aspect, expiry policy, key generators, payload
 * enricher, recovered-payload handler, method-exception policy, schedulers, and the
 * in-memory store factory (when no other store type is configured).
 *
 * <p>Store-type-specific beans (Caffeine, JDBC) and the final assembled {@code FailoverStore}
 * bean are produced by {@code FailoverStoreAutoConfiguration} (with per-tenant routing layered on
 * by {@code FailoverStoreMultiTenantAutoConfiguration} when enabled).
 *
 * <p><strong>Note on structure:</strong> the core beans are declared flat on this class (rather
 * than grouped into nested {@code @Configuration} classes) on purpose. {@code @ConditionalOnMissingBean}
 * relies on auto-configuration ordering to let a user-declared bean win; nested {@code @Configuration}
 * classes lose that deferred ordering and break bean-override (the framework's primary extension
 * point). Keep new beans flat here unless they have no {@code @ConditionalOnMissingBean}.
 *
 * @author Anand Manissery
 */
@AutoConfiguration
@ConditionalOnExpression("${failover.enabled:true} eq true")
@EnableConfigurationProperties(FailoverProperties.class)
@Slf4j
@EnableAspectJAutoProxy
public class FailoverAutoConfiguration {

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
     * @return {@link DefaultKeyGenerator}, which derives the key by joining the method arguments'
     *         string representations (see {@link DefaultKeyGenerator} for the per-argument rules)
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
    @Bean(name = "failoverExpiryPolicy")
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
     * Composes the active {@link ContextPropagator} from <em>every</em> {@link ContextPropagator}
     * bean in the application context, plus an always-on {@link MdcContextPropagator}:
     * <ol>
     *   <li>All {@code ContextPropagator} beans, gathered in
     *       {@link ObjectProvider#orderedStream() ordered} form. This includes the auto-configured
     *       {@link TenantContextPropagator} (when {@code failover.store.multitenant.enabled=true})
     *       and {@link MicrometerContextPropagator} (when {@code io.micrometer.tracing.Tracer} is on
     *       the classpath and a {@code Tracer} bean exists), <em>and any custom {@code ContextPropagator}
     *       bean you declare</em>. Annotate a custom bean with {@code @Order} to control its position
     *       in the chain.</li>
     *   <li>{@link MdcContextPropagator} — always appended last (innermost: restored immediately
     *       before the slice runs).</li>
     * </ol>
     * Declare a bean <strong>named {@code contextPropagator}</strong> to replace this composition
     * entirely; declare any other {@code ContextPropagator} bean to <strong>add</strong> it to the chain.
     *
     * <p>The parameter is a {@link List} (not an {@code ObjectProvider}) on purpose: Spring's
     * collection injection excludes the bean currently in creation, so this {@code contextPropagator}
     * bean does not gather itself (which would be a self-referential {@code BeanCurrentlyInCreationException}).
     * The list is ordered by {@code @Order}/{@link org.springframework.core.Ordered}.
     *
     * @param contextPropagators all {@link ContextPropagator} beans in the context (self excluded), {@code @Order}-sorted
     * @return a single propagator or a {@link CompositeContextPropagator} when multiple are present
     */
    @ConditionalOnMissingBean(name = "contextPropagator")
    @Bean
    public ContextPropagator contextPropagator(List<ContextPropagator> contextPropagators) {
        List<ContextPropagator> propagators = new ArrayList<>(contextPropagators);
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
     * <p>Unbounded by default. When {@code failover.scatter.concurrency-limit > 0} the virtual-thread
     * executor is wrapped in a {@link BoundedTaskExecutor} that caps concurrent slice fan-out and
     * applies the configured {@code rejection-policy} on overload (audit R-2); accepted slices still
     * run on virtual threads.
     *
     * @return virtual-thread {@link SimpleAsyncTaskExecutor} (optionally bounded) named {@code failover-scatter-*}
     */
    @ConditionalOnMissingBean(name = "scatterGatherExecutor")
    @ConditionalOnProperty(prefix = "failover.scatter", name = "parallel", havingValue = "true")
    @Bean(name = "scatterGatherExecutor")
    public TaskExecutor scatterGatherExecutor(FailoverProperties properties) {
        var executor = new SimpleAsyncTaskExecutor("failover-scatter-");
        executor.setVirtualThreads(true);
        var scatter = properties.getScatter();
        if (scatter.getConcurrencyLimit() > 0) {
            log.info("ScatterGather executor bounded: concurrencyLimit={}, rejectionPolicy={} (virtual threads).",
                    scatter.getConcurrencyLimit(), scatter.getRejectionPolicy());
            return new BoundedTaskExecutor(executor, scatter.getConcurrencyLimit(),
                    scatter.getRejectionPolicy(), "failover-scatter");
        }
        log.info("ScatterGather executor configured with virtual threads (failover.scatter.parallel=true).");
        return executor;
    }

    /**
     * Registers a Spring-native scanner that locates all {@code @Failover}-annotated methods
     * by walking the already-built {@link org.springframework.context.ApplicationContext}.
     *
     * @return {@link SpringContextFailoverScanner}
     */
    @ConditionalOnMissingBean
    @Bean
    public FailoverScanner failoverScanner() {
        return new SpringContextFailoverScanner();
    }

    /** Registers the default no-op {@link RecoveredPayloadHandler} that returns the payload unchanged.
     * @return pass-through {@link PassThroughRecoveredPayloadHandler} */
    @ConditionalOnMissingBean
    @Bean
    public RecoveredPayloadHandler recoveredPayloadHandler() {
        return new PassThroughRecoveredPayloadHandler();
    }

    /**
     * Registers a {@link ObservablePublisher} that writes failover reports to SLF4J.
     *
     * @return {@link MdcLoggerObservablePublisher}
     */
    @Bean
    public ObservablePublisher loggerObservablePublisher() {
        return new MdcLoggerObservablePublisher();
    }

    /**
     * Registers a composite publisher that stamps the publish timestamp once and broadcasts
     * to every {@link ObservablePublisher} in the context. Stamping once here ensures all delegates
     * receive the same timestamp regardless of how many publishers are registered.
     *
     * @param observablePublishers all {@link ObservablePublisher} beans in the context
     * @param clock            clock used to stamp the single publish timestamp
     * @return composite publisher
     */
    @ConditionalOnMissingBean
    @Bean
    public CompositeObservablePublisher compositeObservablePublisher(List<ObservablePublisher> observablePublishers, FailoverClock clock) {
        return new CompositeObservablePublisher(observablePublishers, clock);
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
     * @param observablePublisher            composite report publisher
     * @param failoverExpiryExtractor    reads expiry from {@code @Failover}
     * @param payloadSplitterLookup      looks up named splitter beans
     * @param contextPropagator          propagates thread context to scatter executor threads
     * @param scatterGatherExecutorProvider optional executor for parallel scatter (null = sequential)
     * @param failoverProperties         framework properties (provides the scatter slice timeout)
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
            CompositeObservablePublisher observablePublisher,
            FailoverExpiryExtractor failoverExpiryExtractor,
            PayloadSplitterLookup<Object,Object> payloadSplitterLookup,
            @Qualifier("contextPropagator") ContextPropagator contextPropagator,
            @Qualifier("scatterGatherExecutor") ObjectProvider<TaskExecutor> scatterGatherExecutorProvider,
            FailoverProperties failoverProperties) {
        var defaultHandler = new DefaultFailoverHandler<>(keyGenerator, clock, failoverStore, expiryPolicy, payloadEnricher);
        var scatterHandler = ScatterGatherFailoverHandler.builder(defaultHandler, defaultHandler, payloadSplitterLookup)
                .executor(scatterGatherExecutorProvider.getIfAvailable())
                .contextPropagator(contextPropagator)
                .timeout(failoverProperties.getScatter().getTimeout())
                .observablePublisher(observablePublisher)
                .build();
        return new AdvancedFailoverHandler<>(scatterHandler, recoveredPayloadHandler, observablePublisher, failoverExpiryExtractor);
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
     * @return caching wrapper around {@link com.societegenerale.failover.core.observable.manifest.DefaultManifestInfoExtractor}
     */
    @ConditionalOnMissingBean
    @Bean
    public ManifestInfoExtractor manifestInfoExtractor(ResourceLoader resourceLoader) {
        return new CacheableManifestInfoExtractor(new DefaultManifestInfoExtractor(resourceLoader));
    }

    /**
     * Creates the failover reporter bean that publishes a startup summary.
     *
     * @param observablePublisher        composite publisher that receives the startup report
     * @param failoverScanner        scans for all {@code @Failover}-annotated methods
     * @param clock                  clock for the report timestamp
     * @param manifestInfoExtractor  extracts build info from MANIFEST.MF
     * @param failoverExpiryExtractor reads expiry config from {@code @Failover}
     * @param failoverProperties     active failover properties for inclusion in the report
     * @return reporter that publishes a full failover summary on application startup
     */
    @ConditionalOnMissingBean
    @Bean(initMethod = "observe")
    public FailoverObserver failoverObserver(CompositeObservablePublisher observablePublisher, FailoverScanner failoverScanner, FailoverClock clock, ManifestInfoExtractor manifestInfoExtractor, FailoverExpiryExtractor failoverExpiryExtractor, FailoverProperties failoverProperties) {
        return new DefaultFailoverObserver(observablePublisher, failoverScanner, clock, manifestInfoExtractor, failoverExpiryExtractor, failoverProperties.additionalInfo());
    }

    @Configuration
    @ConditionalOnExpression("${failover.enabled:true} eq true")
    @ConditionalOnClass(name = "org.springframework.boot.health.contributor.HealthIndicator")
    static class FailoverHealthConfiguration {

        /**
         * Actuator health indicator for the failover framework. Reports {@code DOWN} when
         * the scanner discovers zero {@code @Failover} annotations (misconfiguration).
         */
        @ConditionalOnMissingBean(FailoverHealthIndicator.class)
        @Bean
        public FailoverHealthIndicator failoverHealthIndicator(FailoverScanner failoverScanner) {
            return new FailoverHealthIndicator(failoverScanner);
        }
    }

    @Configuration
    @ConditionalOnExpression("${failover.enabled:true} eq true")
    @ConditionalOnProperty(prefix = "failover", name = "scheduler.enabled", havingValue = "true", matchIfMissing = true)
    @EnableScheduling
    @EnableAsync
    static class FailoverSchedulingConfiguration {
        @ConditionalOnMissingBean
        @Bean
        public ObservableScheduler observableScheduler(FailoverObserver failoverObserver) {
            return new ObservableScheduler(failoverObserver);
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
