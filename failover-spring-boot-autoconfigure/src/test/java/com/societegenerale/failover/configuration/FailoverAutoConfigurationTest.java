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

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.aspect.FailoverAspect;
import com.societegenerale.failover.core.BasicFailoverExecution;
import com.societegenerale.failover.core.FailoverExecution;
import com.societegenerale.failover.core.FailoverHandler;
import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.exception.MethodExceptionContext;
import com.societegenerale.failover.core.exception.MethodExceptionHandler;
import com.societegenerale.failover.core.exception.policy.MethodExceptionPolicy;
import com.societegenerale.failover.core.exception.policy.NeverRethrowMethodExceptionPolicy;
import com.societegenerale.failover.core.exception.policy.RethrowIfNoRecoveryMethodExceptionPolicy;
import com.societegenerale.failover.core.expiry.ExpiryPolicy;
import com.societegenerale.failover.core.expiry.FailoverExpiryExtractor;
import com.societegenerale.failover.core.key.KeyGenerator;
import com.societegenerale.failover.core.payload.PayloadEnricher;
import com.societegenerale.failover.core.payload.RecoveredPayloadHandler;
import com.societegenerale.failover.core.payload.splitter.PayloadSplitterLookup;
import com.societegenerale.failover.core.propagator.CompositeContextPropagator;
import com.societegenerale.failover.core.propagator.ContextPropagator;
import com.societegenerale.failover.core.propagator.MdcContextPropagator;
import com.societegenerale.failover.core.observable.publisher.AsyncObservablePublisher;
import com.societegenerale.failover.core.observable.publisher.CompositeObservablePublisher;
import com.societegenerale.failover.core.observable.FailoverObserver;
import com.societegenerale.failover.core.scanner.FailoverScanner;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.observable.publisher.ObservablePublisher;
import com.societegenerale.failover.core.store.FailoverStoreException;
import com.societegenerale.failover.propagator.MicrometerContextPropagator;
import com.societegenerale.failover.scheduler.ExpiryCleanupScheduler;
import com.societegenerale.failover.scheduler.ObservableScheduler;
import com.societegenerale.failover.core.store.DefaultFailoverStore;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.store.async.FailoverStoreAsync;
import com.societegenerale.failover.store.inmemory.FailoverStoreInmemory;
import com.societegenerale.failover.store.multitenant.TenantContextPropagator;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.jspecify.annotations.NonNull;
import org.mockito.Mockito;
import org.springframework.core.task.TaskExecutor;
import com.societegenerale.failover.store.async.BoundedTaskExecutor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.util.ReflectionUtils;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static com.societegenerale.failover.configuration.BeanAssertions.assertBasicBean;
import static com.societegenerale.failover.configuration.BeanAssertions.assertBeansAreEmpty;
import static com.societegenerale.failover.configuration.BeanAssertions.assertBeansAreNotNull;
import static com.societegenerale.failover.core.util.CastingUtils.cast;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anand Manissery
 */
class FailoverAutoConfigurationTest {

    // ── @TestConfiguration ────────────────────────────────────────────────────

    @TestConfiguration
    static class CustomPolicyConfig {

        @Bean
        public MethodExceptionPolicy methodExceptionPolicy() {
            return new AlwaysNullPolicy();
        }

        static class AlwaysNullPolicy implements MethodExceptionPolicy {
            @Override
            public <T> T handle(MethodExceptionContext<T> context) {
                return null;
            }
        }
    }

    @TestConfiguration
    static class CustomFailoverExecutionConfig {

        @Bean
        public FailoverExecution<Object> failoverExecution() {
            return new NoOpFailoverExecution();
        }

        static class NoOpFailoverExecution implements FailoverExecution<Object> {
            @Override
            public Object execute(Failover failover, Supplier<Object> supplier, Method method, List<Object> args) {
                return supplier.get();
            }
        }
    }

    // ── Shared store probe ────────────────────────────────────────────────────

    private record ThreadCapturingStore(
            FailoverStore<Object> delegate,
            AtomicReference<String> threadCapture,
            CountDownLatch latch
    ) implements FailoverStore<Object> {

        @Override
        public void store(ReferentialPayload<Object> payload) {
            threadCapture.set(Thread.currentThread().getName());
            latch.countDown();
            delegate.store(payload);
        }

        @Override
        public void delete(ReferentialPayload<Object> payload) { delegate.delete(payload); }

        @Override
        public Optional<ReferentialPayload<Object>> find(String name, String key) {
            return delegate.find(name, key);
        }

        @Override
        public List<ReferentialPayload<Object>> findAll(String name) throws FailoverStoreException {
            return delegate.findAll(name);
        }

        @Override
        public void cleanByExpiry(Instant expiry) { delegate.cleanByExpiry(expiry); }
    }

    // ── Default configuration ─────────────────────────────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @DisplayName("when default configuration")
    class WhenDefault {

        @Autowired
        private ApplicationContext applicationContext;

        @Autowired
        private FailoverStore<Object> failoverStore;

        @Test
        @DisplayName("should load all basic default beans")
        void shouldLoadAllBasicDefaultBeans() {
            assertBasicBean(applicationContext);
        }

        @Test
        @DisplayName("should load FailoverAspect bean")
        void shouldLoadFailoverAspectBean() {
            assertBeansAreNotNull(applicationContext, FailoverAspect.class);
        }

        @Test
        @DisplayName("should load BasicFailoverExecution by default")
        void shouldLoadBasicFailoverExecutionByDefault() {
            assertThat(applicationContext.getBean(BasicFailoverExecution.class)).isNotNull();
        }

        @Test
        @DisplayName("should load MethodExceptionHandler bean")
        void shouldLoadMethodExceptionHandlerBean() {
            assertBeansAreNotNull(applicationContext, MethodExceptionHandler.class);
        }

        @Test
        @DisplayName("should load RethrowIfNoRecoveryMethodExceptionPolicy as default")
        void shouldLoadRethrowPolicyByDefault() {
            assertThat(applicationContext.getBean(MethodExceptionPolicy.class))
                    .isInstanceOf(RethrowIfNoRecoveryMethodExceptionPolicy.class);
        }

        @Test
        @DisplayName("failoverStore is FailoverStoreAsync(DefaultFailoverStore(FailoverStoreInmemory)) by default")
        void shouldLoadInmemoryFailoverStoreByDefault() {
            assertThat(failoverStore).isInstanceOf(FailoverStoreAsync.class);
            FailoverStore<Object> inner = requireNonNull(((FailoverStoreAsync<Object>) failoverStore).getFailoverStore());
            assertThat(inner).isInstanceOf(DefaultFailoverStore.class);
            assertThat(requireNonNull(((DefaultFailoverStore<Object>) inner).getFailoverStore())).isInstanceOf(FailoverStoreInmemory.class);
        }

        @Test
        @DisplayName("innermost store is FailoverStoreInmemory")
        void innermostShouldBeInmemory() {
            FailoverStoreAsync<Object> async = cast(failoverStore);
            DefaultFailoverStore<Object> defaultStore = cast(requireNonNull(async.getFailoverStore()));
            assertThat(requireNonNull(defaultStore.getFailoverStore())).isInstanceOf(FailoverStoreInmemory.class);
        }

        @Test
        @DisplayName("should load ObservableScheduler bean by default")
        void shouldLoadObservableSchedulerByDefault() {
            assertThat(applicationContext.getBean(ObservableScheduler.class)).isNotNull();
        }

        @Test
        @DisplayName("should load ExpiryCleanupScheduler bean by default")
        void shouldLoadExpiryCleanupSchedulerByDefault() {
            assertThat(applicationContext.getBean(ExpiryCleanupScheduler.class)).isNotNull();
        }

        @Test
        @DisplayName("contextPropagator is MdcContextPropagator by default — no tenant, no Micrometer")
        void contextPropagatorIsMdcContextPropagatorByDefault() {
            ContextPropagator propagator = applicationContext.getBean("contextPropagator", ContextPropagator.class);
            assertThat(propagator).isInstanceOf(MdcContextPropagator.class);
        }

        @Test
        @DisplayName("payloadSplitterLookup is registered")
        void payloadSplitterLookupIsRegistered() {
            assertThat(applicationContext.getBean(PayloadSplitterLookup.class)).isNotNull();
        }

        @Test
        @DisplayName("scatterGatherExecutor is NOT registered by default — requires failover.scatter.parallel=true")
        void scatterGatherExecutorNotRegisteredByDefault() {
            assertThat(applicationContext.containsBean("scatterGatherExecutor")).isFalse();
        }

        @Test
        @DisplayName("store() executes on a different thread — async write offload confirmed")
        void storeExecutesOnDifferentThread() throws Exception {
            String callingThread = Thread.currentThread().getName();
            AtomicReference<String> storingThread = new AtomicReference<>();
            CountDownLatch latch = new CountDownLatch(1);

            FailoverStoreAsync<Object> async = cast(failoverStore);
            DefaultFailoverStore<Object> defaultStore = (DefaultFailoverStore<Object>) requireNonNull(async.getFailoverStore());
            FailoverStore<Object> originalInner = requireNonNull(defaultStore.getFailoverStore());

            Field innerField = DefaultFailoverStore.class.getDeclaredField("failoverStore");
            ReflectionUtils.makeAccessible(innerField);
            innerField.set(defaultStore, new ThreadCapturingStore(originalInner, storingThread, latch));

            try {
                failoverStore.store(new ReferentialPayload<>("async-test", "key1", true,
                        Instant.now(), Instant.now().plusSeconds(3600), "payload"));

                assertThat(latch.await(5, TimeUnit.SECONDS)).as("store() did not execute within 5 seconds").isTrue();
                assertThat(storingThread.get()).as("store() must run on a different thread").isNotEqualTo(callingThread);
            } finally {
                innerField.set(defaultStore, originalInner);
            }
        }
    }

    // ── failover.enabled=false ────────────────────────────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @TestPropertySource(properties = {"failover.enabled=false"})
    @DisplayName("when failover disabled")
    class WhenFailoverDisabled {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("should not load any failover beans")
        void shouldNotLoadAnyFailoverBeans() {
            assertBeansAreEmpty(applicationContext,
                    FailoverClock.class, FailoverStore.class,
                    PayloadEnricher.class, RecoveredPayloadHandler.class,
                    FailoverHandler.class, FailoverExecution.class,
                    MethodExceptionHandler.class, MethodExceptionPolicy.class,
                    FailoverExpiryExtractor.class, FailoverScanner.class,
                    CompositeObservablePublisher.class, FailoverObserver.class,
                    ObservablePublisher.class, KeyGenerator.class, ExpiryPolicy.class
            );
        }
    }

    // ── failover.aspect.enabled=false ─────────────────────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @TestPropertySource(properties = {"failover.aspect.enabled=false"})
    @DisplayName("when aspect disabled")
    class WhenAspectDisabled {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("should load all basic default beans")
        void shouldLoadAllBasicDefaultBeans() {
            assertBasicBean(applicationContext);
        }

        @Test
        @DisplayName("should NOT load FailoverAspect bean")
        void shouldNotLoadFailoverAspectBean() {
            assertBeansAreEmpty(applicationContext, FailoverAspect.class);
        }
    }

    // ── observable async (non-blocking publish) ───────────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @TestPropertySource(properties = {"failover.observable.async.enabled=true"})
    @DisplayName("when observable async enabled")
    class WhenObservableAsyncEnabled {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("dispatching publisher is non-blocking (AsyncObservablePublisher)")
        void dispatchingPublisherIsAsyncWhenEnabled() {
            ObservablePublisher publisher =
                    applicationContext.getBean("failoverObservablePublisher", ObservablePublisher.class);
            assertThat(publisher).isInstanceOf(AsyncObservablePublisher.class);
        }
    }

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @TestPropertySource(properties = {"failover.observable.async.enabled=false"})
    @DisplayName("when observable async disabled")
    class WhenObservableAsyncDisabled {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("dispatching publisher is the synchronous composite")
        void dispatchingPublisherIsCompositeWhenSync() {
            ObservablePublisher publisher =
                    applicationContext.getBean("failoverObservablePublisher", ObservablePublisher.class);
            assertThat(publisher).isInstanceOf(CompositeObservablePublisher.class);
        }
    }

    // ── observable instance tag & cardinality guard ───────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @DisplayName("when observable instance/cardinality are default (mode=auto)")
    class WhenObservableTaggingDefault {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("mode=auto by default ⇒ the per-registry customizer is wired (not the global always-filter); cardinality on")
        void autoByDefault() {
            assertThat(applicationContext.containsBean("failoverInstanceMeterRegistryCustomizer")).isTrue();
            assertThat(applicationContext.containsBean("failoverInstanceMeterFilter")).isFalse();   // global always-filter not used
            assertThat(applicationContext.containsBean("failoverCardinalityMeterFilter")).isTrue();
        }

        @Test
        @DisplayName("mode=auto ⇒ customizer tags non-Prometheus registries but skips a Prometheus one")
        void autoCustomizerSkipsPrometheus() {
            @SuppressWarnings("unchecked")
            org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer<io.micrometer.core.instrument.MeterRegistry> customizer =
                    applicationContext.getBean("failoverInstanceMeterRegistryCustomizer",
                            org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer.class);

            SimpleMeterRegistry push = new SimpleMeterRegistry();          // not Prometheus → tagged
            customizer.customize(push);
            push.counter("failover.store.total").increment();
            push.counter("other.metric").increment();
            assertThat(push.find("failover.store.total").tagKeys("instance").counter())
                    .as("failover.* must carry an instance tag on a non-Prometheus registry").isNotNull();
            assertThat(push.find("other.metric").tagKeys("instance").counter())
                    .as("non-failover meters untouched").isNull();

            SimpleMeterRegistry prom = new FakePrometheusMeterRegistry();  // simpleName contains "Prometheus" → skipped
            customizer.customize(prom);
            prom.counter("failover.store.total").increment();
            assertThat(prom.find("failover.store.total").tagKeys("instance").counter())
                    .as("Prometheus registry must NOT get our instance tag").isNull();
        }
    }

    /** A registry whose simple name contains "Prometheus" so the auto customizer treats it as Prometheus. */
    static class FakePrometheusMeterRegistry extends SimpleMeterRegistry { }

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @TestPropertySource(properties = {
            "failover.observable.instance.mode=always",
            "failover.observable.instance.id=order-svc:node-1"})
    @DisplayName("when instance mode=always")
    class WhenInstanceModeAlways {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("global filter tags failover.* (all registries), leaves other meters untouched")
        void alwaysFilterTagsOnlyFailoverMeters() {
            assertThat(applicationContext.containsBean("failoverInstanceMeterRegistryCustomizer")).isFalse();
            MeterFilter filter = applicationContext.getBean("failoverInstanceMeterFilter", MeterFilter.class);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            registry.config().meterFilter(filter);

            registry.counter("failover.store.total").increment();
            registry.counter("other.metric").increment();

            assertThat(registry.get("failover.store.total").tag("instance", "order-svc:node-1").counter().count())
                    .isEqualTo(1.0);
            assertThat(registry.find("other.metric").tag("instance", "order-svc:node-1").counter()).isNull();
        }
    }

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @TestPropertySource(properties = {"failover.observable.instance.mode=never"})
    @DisplayName("when instance mode=never")
    class WhenInstanceModeNever {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("neither the customizer nor the global filter is wired")
        void neitherInstanceBean() {
            assertThat(applicationContext.containsBean("failoverInstanceMeterRegistryCustomizer")).isFalse();
            assertThat(applicationContext.containsBean("failoverInstanceMeterFilter")).isFalse();
        }
    }

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @TestPropertySource(properties = {"failover.observable.cardinality.max-apis=2"})
    @DisplayName("when cardinality guard capped")
    class WhenCardinalityGuardCapped {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("denies new failover series once the distinct-name cap is reached")
        void capsDistinctFailoverNames() {
            MeterFilter filter = applicationContext.getBean("failoverCardinalityMeterFilter", MeterFilter.class);
            SimpleMeterRegistry registry = new SimpleMeterRegistry();
            registry.config().meterFilter(filter);

            registry.counter("failover.store.total", "name", "a").increment();
            registry.counter("failover.store.total", "name", "b").increment();
            registry.counter("failover.store.total", "name", "c").increment(); // beyond cap of 2

            assertThat(registry.find("failover.store.total").tag("name", "a").counter()).isNotNull();
            assertThat(registry.find("failover.store.total").tag("name", "b").counter()).isNotNull();
            assertThat(registry.find("failover.store.total").tag("name", "c").counter()).isNull();
        }
    }

    // ── exception-policy=never_throw ──────────────────────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @TestPropertySource(properties = {"failover.exception-policy=never_throw"})
    @DisplayName("when exception policy is never_throw")
    class WhenExceptionPolicyNeverThrow {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("should load NeverRethrowMethodExceptionPolicy")
        void shouldLoadNeverRethrowPolicy() {
            assertThat(applicationContext.getBean(MethodExceptionPolicy.class))
                    .isInstanceOf(NeverRethrowMethodExceptionPolicy.class);
        }
    }

    // ── exception-policy=custom ───────────────────────────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class, CustomPolicyConfig.class})
    @TestPropertySource(properties = {"failover.exception-policy=custom"})
    @DisplayName("when exception policy is custom")
    class WhenExceptionPolicyCustom {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("custom MethodExceptionPolicy bean overrides auto-configured default")
        void customPolicyBeanOverridesAutoConfiguredDefault() {
            assertThat(applicationContext.getBean(MethodExceptionPolicy.class))
                    .isInstanceOf(CustomPolicyConfig.AlwaysNullPolicy.class);
        }
    }

    // ── failover.scheduler.enabled=false ─────────────────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @TestPropertySource(properties = {"failover.scheduler.enabled=false"})
    @DisplayName("when scheduler disabled")
    class WhenSchedulerDisabled {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("should load all basic default beans")
        void shouldLoadAllBasicDefaultBeans() {
            assertBasicBean(applicationContext);
        }

        @Test
        @DisplayName("ObservableScheduler should NOT be registered")
        void observableSchedulerNotRegistered() {
            assertBeansAreEmpty(applicationContext, ObservableScheduler.class);
        }

        @Test
        @DisplayName("ExpiryCleanupScheduler should NOT be registered")
        void expiryCleanupSchedulerNotRegistered() {
            assertBeansAreEmpty(applicationContext, ExpiryCleanupScheduler.class);
        }
    }

    // ── failover.type=custom ──────────────────────────────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class, CustomFailoverExecutionConfig.class})
    @TestPropertySource(properties = {"failover.type=custom"})
    @DisplayName("when failover type is custom")
    class WhenFailoverTypeCustom {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("custom FailoverExecution bean is used")
        void customFailoverExecutionUsed() {
            assertThat(applicationContext.getBean(FailoverExecution.class))
                    .isInstanceOf(CustomFailoverExecutionConfig.NoOpFailoverExecution.class);
        }

        @Test
        @DisplayName("BasicFailoverExecution should NOT be registered — @ConditionalOnProperty(type=basic) does not match")
        void basicFailoverExecutionNotRegistered() {
            assertThat(applicationContext.getBeansOfType(BasicFailoverExecution.class)).isEmpty();
        }
    }

    // ── failover.scatter.parallel=true ───────────────────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @TestPropertySource(properties = {"failover.scatter.parallel=true"})
    @DisplayName("when scatter parallel enabled")
    class WhenScatterParallelEnabled {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("scatterGatherExecutor bean is registered with virtual threads")
        void scatterGatherExecutorRegistered() {
            assertThat(applicationContext.containsBean("scatterGatherExecutor")).isTrue();
            assertThat(applicationContext.getBean("scatterGatherExecutor", TaskExecutor.class)).isNotNull();
        }

        @Test
        @DisplayName("scatterGatherExecutor is unbounded by default (not wrapped)")
        void scatterGatherExecutorUnboundedByDefault() {
            assertThat(applicationContext.getBean("scatterGatherExecutor", TaskExecutor.class))
                    .isNotInstanceOf(BoundedTaskExecutor.class);
        }

        @Test
        @DisplayName("contextPropagator is still MdcContextPropagator — parallel mode does not change propagator")
        void contextPropagatorUnchangedWhenParallelEnabled() {
            ContextPropagator propagator = applicationContext.getBean("contextPropagator", ContextPropagator.class);
            assertThat(propagator).isInstanceOf(MdcContextPropagator.class);
        }
    }

    // ── failover.scatter.concurrency-limit > 0 ──────────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @TestPropertySource(properties = {
            "failover.scatter.parallel=true",
            "failover.scatter.concurrency-limit=4",
            "failover.scatter.rejection-policy=DISCARD"})
    @DisplayName("when failover.scatter.concurrency-limit > 0")
    class WhenScatterExecutorBounded {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("scatterGatherExecutor is wrapped in a BoundedTaskExecutor")
        void scatterGatherExecutorBounded() {
            assertThat(applicationContext.getBean("scatterGatherExecutor", TaskExecutor.class))
                    .isInstanceOf(BoundedTaskExecutor.class);
        }
    }

    // ── @ConditionalOnMissingBean(name="scatterGatherExecutor") ─────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class, FailoverAutoConfigurationTest.CustomScatterExecutorConfig.class})
    @TestPropertySource(properties = {"failover.scatter.parallel=true"})
    @DisplayName("when custom scatterGatherExecutor bean declared")
    class WhenCustomScatterGatherExecutor {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("auto-configured scatterGatherExecutor is NOT registered — custom bean wins")
        void customScatterGatherExecutorWins() {
            TaskExecutor executor = applicationContext.getBean("scatterGatherExecutor", TaskExecutor.class);
            assertThat(executor).isInstanceOf(CustomScatterExecutorConfig.StubExecutor.class);
        }
    }

    @TestConfiguration
    static class CustomScatterExecutorConfig {

        static class StubExecutor implements TaskExecutor {
            @Override public void execute(Runnable task) { task.run(); }
        }

        @Bean(name = "scatterGatherExecutor")
        public TaskExecutor scatterGatherExecutor() { return new StubExecutor(); }
    }

    // ── @ConditionalOnMissingBean(ContextPropagator) ─────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class, FailoverAutoConfigurationTest.CustomContextPropagatorConfig.class})
    @DisplayName("when custom ContextPropagator bean declared")
    class WhenCustomContextPropagator {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("auto-configured contextPropagator is NOT registered — custom bean wins via @ConditionalOnMissingBean")
        void customContextPropagatorWins() {
            ContextPropagator propagator = applicationContext.getBean("contextPropagator", ContextPropagator.class);
            assertThat(propagator).isInstanceOf(CustomContextPropagatorConfig.StubPropagator.class);
        }

        @Test
        @DisplayName("exactly one ContextPropagator bean — no auto-composed bean alongside custom")
        void exactlyOneContextPropagatorBean() {
            assertThat(applicationContext.getBeansOfType(ContextPropagator.class)).hasSize(1);
        }
    }

    @TestConfiguration
    static class CustomContextPropagatorConfig {

        static class StubPropagator implements ContextPropagator {
            @Override public @NonNull Runnable wrap(@NonNull Runnable task) { return task; }
        }

        @Bean
        public ContextPropagator contextPropagator() {
            return new StubPropagator();
        }
    }

    // ── Custom (differently-named) ContextPropagator joins the composed chain ─

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class, FailoverAutoConfigurationTest.CustomNamedPropagatorConfig.class})
    @DisplayName("when a custom, differently-named ContextPropagator bean is present")
    class WhenCustomNamedContextPropagatorPresent {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("auto-composed contextPropagator is a Composite that includes the custom propagator in the chain")
        void customPropagatorJoinsChain() {
            ContextPropagator propagator = applicationContext.getBean("contextPropagator", ContextPropagator.class);
            assertThat(propagator).isInstanceOf(CompositeContextPropagator.class);

            CustomNamedPropagatorConfig.MarkerPropagator marker =
                    applicationContext.getBean(CustomNamedPropagatorConfig.MarkerPropagator.class);
            marker.wrapped.set(false);
            // Composing the chain must invoke every member propagator's wrap() — proving the custom
            // bean was gathered into the composite alongside the always-on MdcContextPropagator.
            propagator.wrap(() -> { });
            assertThat(marker.wrapped.get()).isTrue();
        }
    }

    @TestConfiguration
    static class CustomNamedPropagatorConfig {

        static class MarkerPropagator implements ContextPropagator {
            final AtomicBoolean wrapped = new AtomicBoolean(false);
            @Override public @NonNull Runnable wrap(@NonNull Runnable task) {
                wrapped.set(true);
                return task;
            }
        }

        @Bean("myCustomPropagator")
        public ContextPropagator myCustomPropagator() {
            return new MarkerPropagator();
        }
    }

    // ── TenantContextPropagator present → CompositeContextPropagator ─────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class, FailoverAutoConfigurationTest.MockTenantPropagatorConfig.class})
    @DisplayName("when TenantContextPropagator bean present")
    class WhenTenantContextPropagatorPresent {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("contextPropagator is CompositeContextPropagator — wraps TenantContextPropagator + MdcContextPropagator")
        void contextPropagatorIsCompositeWithTenantAndMdc() {
            ContextPropagator propagator = applicationContext.getBean("contextPropagator", ContextPropagator.class);
            assertThat(propagator).isInstanceOf(CompositeContextPropagator.class);
        }

        @Test
        @DisplayName("TenantContextPropagator bean is present in context")
        void tenantContextPropagatorBeanPresent() {
            assertThat(applicationContext.getBean(TenantContextPropagator.class)).isNotNull();
        }
    }

    @TestConfiguration
    static class MockTenantPropagatorConfig {
        @Bean
        public TenantContextPropagator tenantContextPropagator() {
            return new TenantContextPropagator();
        }
    }

    // ── Tracer bean present → MicrometerContextPropagator + CompositeContextPropagator ──

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class, FailoverAutoConfigurationTest.MockTracerConfig.class})
    @DisplayName("when Micrometer Tracer bean present")
    class WhenMicrometerTracerPresent {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("MicrometerContextPropagator is registered by MicrometerTracingAutoConfiguration")
        void micrometerContextPropagatorRegistered() {
            assertThat(applicationContext.getBean(MicrometerContextPropagator.class)).isNotNull();
        }

        @Test
        @DisplayName("contextPropagator is CompositeContextPropagator — wraps MicrometerContextPropagator + MdcContextPropagator")
        void contextPropagatorIsCompositeWithMicrometerAndMdc() {
            ContextPropagator propagator = applicationContext.getBean("contextPropagator", ContextPropagator.class);
            assertThat(propagator).isInstanceOf(CompositeContextPropagator.class);
        }
    }

    @TestConfiguration
    static class MockTracerConfig {
        @Bean
        public Tracer tracer() {
            return Mockito.mock(Tracer.class);
        }
    }

    // ── Tenant + Micrometer → 3-element CompositeContextPropagator ───────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class,
            FailoverAutoConfigurationTest.MockTenantPropagatorConfig.class,
            FailoverAutoConfigurationTest.MockTracerConfig.class})
    @DisplayName("when both TenantContextPropagator and Micrometer Tracer present")
    class WhenTenantAndMicrometerBothPresent {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("contextPropagator is CompositeContextPropagator — wraps Tenant + Micrometer + MDC")
        void contextPropagatorIsThreeElementComposite() {
            ContextPropagator propagator = applicationContext.getBean("contextPropagator", ContextPropagator.class);
            assertThat(propagator).isInstanceOf(CompositeContextPropagator.class);
        }

        @Test
        @DisplayName("TenantContextPropagator, MicrometerContextPropagator, and contextPropagator beans all registered")
        void allThreePropagatorBeansRegistered() {
            assertThat(applicationContext.getBean(TenantContextPropagator.class)).isNotNull();
            assertThat(applicationContext.getBean(MicrometerContextPropagator.class)).isNotNull();
            assertThat(applicationContext.getBean("contextPropagator", ContextPropagator.class)).isNotNull();
        }
    }
}
