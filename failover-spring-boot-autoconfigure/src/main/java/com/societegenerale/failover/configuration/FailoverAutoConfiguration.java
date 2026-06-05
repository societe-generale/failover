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

    @ConditionalOnMissingBean
    @Bean
    public FailoverClock failoverClock() {
        return new DefaultFailoverClock();
    }

    @ConditionalOnMissingBean(name = "defaultKeyGenerator")
    @Bean(name = "defaultKeyGenerator")
    public KeyGenerator defaultKeyGenerator() {
        return new DefaultKeyGenerator();
    }

    @ConditionalOnMissingBean
    @Bean
    public KeyGeneratorLookup keyGeneratorLookup() {
        return new BeanFactoryKeyGeneratorLookup();
    }

    @ConditionalOnMissingBean(name = "failoverKeyGenerator")
    @Bean(name = "failoverKeyGenerator")
    public KeyGenerator failoverKeyGenerator(@Qualifier("defaultKeyGenerator") KeyGenerator defaultKeyGenerator, KeyGeneratorLookup keyGeneratorLookup) {
        return new FailoverKeyGenerator(defaultKeyGenerator, keyGeneratorLookup);
    }

    @ConditionalOnMissingBean
    @Bean
    public FailoverExpiryExtractor failoverExpiryExtractor() {
        return new BeanFactoryFailoverExpiryExtractor();
    }

    @ConditionalOnMissingBean(name = "defaultExpiryPolicy")
    @Bean
    public ExpiryPolicy<Object> defaultExpiryPolicy(FailoverClock clock, FailoverExpiryExtractor failoverExpiryExtractor) {
        return new DefaultExpiryPolicy<>(clock, failoverExpiryExtractor);
    }

    @ConditionalOnMissingBean
    @Bean
    public ExpiryPolicyLookup<Object> expiryPolicyLookup() {
        return new BeanFactoryExpiryPolicyLookup<>();
    }

    @ConditionalOnMissingBean(name = "failoverExpiryPolicy")
    @Bean
    public ExpiryPolicy<Object> failoverExpiryPolicy(@Qualifier("defaultExpiryPolicy") ExpiryPolicy<Object> defaultExpiryPolicy, ExpiryPolicyLookup<Object> expiryPolicyLookup) {
        return new FailoverExpiryPolicy<>(defaultExpiryPolicy, expiryPolicyLookup);
    }

    @ConditionalOnMissingBean
    @Bean
    public PayloadEnricher<Object> payloadEnricher() {
        return new DefaultPayloadEnricher<>();
    }

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

    @ConditionalOnMissingBean
    @Bean
    public FailoverScanner failoverScanner(FailoverProperties failoverProperties) {
        return new DefaultFailoverScanner(failoverProperties.getPackageToScan());
    }

    @ConditionalOnMissingBean
    @Bean
    public RecoveredPayloadHandler recoveredPayloadHandler() {
        return new PassThroughRecoveredPayloadHandler();
    }

    @Bean
    public ReportPublisher loggerReportPublisher(FailoverClock clock) {
        return new LoggerReportPublisher(clock);
    }

    @Bean
    public ReportPublisher metricsReportPublisher(FailoverClock clock) {
        return new MetricsReportPublisher(clock);
    }

    @ConditionalOnMissingBean
    @Bean
    public CompositeReportPublisher compositeReportPublisher(List<ReportPublisher> reportPublishers) {
        return new CompositeReportPublisher(reportPublishers);
    }

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

    @ConditionalOnMissingBean
    @Bean
    public MethodExceptionHandler methodExceptionHandler(MethodExceptionPolicy  methodExceptionPolicy) {
        return new MethodExceptionHandler(methodExceptionPolicy);
    }

    @ConditionalOnProperty(prefix = "failover", name = "type", havingValue = "basic", matchIfMissing = true)
    @ConditionalOnMissingBean
    @Bean
    public FailoverExecution<Object> failoverExecution(FailoverHandler<Object> failoverHandler, MethodExceptionHandler methodExceptionHandler) {
        log.info("FailoverExecution configured to BasicFailoverExecution. Available options are :  {{}}", (Object) FailoverType.values());
        return new BasicFailoverExecution<>(failoverHandler, methodExceptionHandler);
    }

    @ConditionalOnProperty(prefix = "failover", name = "aspect.enabled", havingValue = "true", matchIfMissing = true)
    @Bean
    public FailoverAspect<Object> failoverAspect(FailoverExecution<Object> failoverExecution) {
        return new FailoverAspect<>(failoverExecution);
    }

    @ConditionalOnMissingBean
    @Bean
    public ResourceLoader resourceLoader() {
        return new ClassPathResourceLoader();
    }

    @ConditionalOnMissingBean
    @Bean
    public ManifestInfoExtractor manifestInfoExtractor(ResourceLoader resourceLoader) {
        return new CacheableManifestInfoExtractor(new DefaultManifestInfoExtractor(resourceLoader));
    }

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
