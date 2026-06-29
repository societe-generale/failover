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
import com.societegenerale.failover.core.store.DefaultFailoverStore;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.core.store.FailoverStoreException;
import com.societegenerale.failover.propagator.MicrometerContextPropagator;
import com.societegenerale.failover.scheduler.ExpiryCleanupScheduler;
import com.societegenerale.failover.scheduler.ObservableScheduler;
import com.societegenerale.failover.store.async.BoundedTaskExecutor;
import com.societegenerale.failover.store.async.FailoverStoreAsync;
import com.societegenerale.failover.store.inmemory.FailoverStoreInmemory;
import com.societegenerale.failover.store.multitenant.TenantContextPropagator;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.config.MeterFilter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.tracing.Tracer;
import org.jspecify.annotations.NonNull;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.platform.commons.util.ReflectionUtils;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.TaskExecutor;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

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

    // One runner shared across all nested classes — 6 failover auto-configurations,
    // no full Spring Boot app, no DataSource init, no web server startup.
    private static final ApplicationContextRunner BASE_RUNNER = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    FailoverAutoConfiguration.class,
                    FailoverStoreAutoConfiguration.class,
                    FailoverStoreMultiTenantAutoConfiguration.class,
                    MicrometerTracingAutoConfiguration.class,
                    FailoverMicrometerAutoConfiguration.class,
                    ResilienceFailoverExecutionAutoConfiguration.class
            ));

    // ── @TestConfiguration helpers ────────────────────────────────────────────

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

    @TestConfiguration
    static class CustomScatterExecutorConfig {

        static class StubExecutor implements TaskExecutor {
            @Override public void execute(Runnable task) { task.run(); }
        }

        @Bean(name = "scatterGatherExecutor")
        public TaskExecutor scatterGatherExecutor() { return new StubExecutor(); }
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

    @TestConfiguration
    static class MockTenantPropagatorConfig {
        @Bean
        public TenantContextPropagator tenantContextPropagator() {
            return new TenantContextPropagator();
        }
    }

    @TestConfiguration
    static class MockTracerConfig {
        @Bean
        public Tracer tracer() {
            return Mockito.mock(Tracer.class);
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

    /** A registry whose simple name contains "Prometheus" so the auto customizer treats it as Prometheus. */
    static class FakePrometheusMeterRegistry extends SimpleMeterRegistry { }

    // ── Default configuration ─────────────────────────────────────────────────

    @Nested
    @DisplayName("when default configuration")
    class WhenDefault {

        @Test
        @DisplayName("all core beans, store chain, schedulers and propagator are wired correctly")
        void defaultBeanWiring() {
            BASE_RUNNER.run(ctx -> {
                assertBasicBean(ctx);
                assertBeansAreNotNull(ctx, FailoverAspect.class);
                assertThat(ctx.getBean(BasicFailoverExecution.class)).isNotNull();
                assertBeansAreNotNull(ctx, MethodExceptionHandler.class);
                assertThat(ctx.getBean(MethodExceptionPolicy.class))
                        .isInstanceOf(RethrowIfNoRecoveryMethodExceptionPolicy.class);

                FailoverStore<Object> failoverStore = ctx.getBean(FailoverStore.class);
                assertThat(failoverStore).isInstanceOf(FailoverStoreAsync.class);
                FailoverStoreAsync<Object> async = cast(failoverStore);
                DefaultFailoverStore<Object> defaultStore = cast(requireNonNull(async.getFailoverStore()));
                assertThat(requireNonNull(defaultStore.getFailoverStore())).isInstanceOf(FailoverStoreInmemory.class);

                assertThat(ctx.getBean(ObservableScheduler.class)).isNotNull();
                assertThat(ctx.getBean(ExpiryCleanupScheduler.class)).isNotNull();
                assertThat(ctx.getBean("contextPropagator", ContextPropagator.class))
                        .isInstanceOf(MdcContextPropagator.class);
                assertThat(ctx.getBean(PayloadSplitterLookup.class)).isNotNull();
                assertThat(ctx.containsBean("scatterGatherExecutor")).isFalse();
            });
        }

        @Test
        @DisplayName("store() executes on a different thread — async write offload confirmed")
        void storeExecutesOnDifferentThread() {
            BASE_RUNNER.run(ctx -> {
                String callingThread = Thread.currentThread().getName();
                AtomicReference<String> storingThread = new AtomicReference<>();
                CountDownLatch latch = new CountDownLatch(1);

                FailoverStore<Object> failoverStore = ctx.getBean(FailoverStore.class);
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
            });
        }
    }

    // ── failover.enabled=false ────────────────────────────────────────────────

    @Nested
    @DisplayName("when failover disabled")
    class WhenFailoverDisabled {

        @Test
        @DisplayName("no failover beans loaded")
        void shouldNotLoadAnyFailoverBeans() {
            BASE_RUNNER.withPropertyValues("failover.enabled=false").run(ctx ->
                assertBeansAreEmpty(ctx,
                    FailoverClock.class, FailoverStore.class,
                    PayloadEnricher.class, RecoveredPayloadHandler.class,
                    FailoverHandler.class, FailoverExecution.class,
                    MethodExceptionHandler.class, MethodExceptionPolicy.class,
                    FailoverExpiryExtractor.class, FailoverScanner.class,
                    CompositeObservablePublisher.class, FailoverObserver.class,
                    ObservablePublisher.class, KeyGenerator.class, ExpiryPolicy.class
                ));
        }
    }

    // ── failover.aspect.enabled=false ─────────────────────────────────────────

    @Nested
    @DisplayName("when aspect disabled")
    class WhenAspectDisabled {

        @Test
        @DisplayName("basic beans present but FailoverAspect absent")
        void aspectDisabled() {
            BASE_RUNNER.withPropertyValues("failover.aspect.enabled=false").run(ctx -> {
                assertBasicBean(ctx);
                assertBeansAreEmpty(ctx, FailoverAspect.class);
            });
        }
    }

    // ── observable async (non-blocking publish) ───────────────────────────────

    @Nested
    @DisplayName("when observable async enabled")
    class WhenObservableAsyncEnabled {

        @Test
        @DisplayName("dispatching publisher is AsyncObservablePublisher")
        void dispatchingPublisherIsAsync() {
            BASE_RUNNER.withPropertyValues("failover.observable.async.enabled=true").run(ctx ->
                assertThat(ctx.getBean("failoverObservablePublisher", ObservablePublisher.class))
                    .isInstanceOf(AsyncObservablePublisher.class));
        }
    }

    @Nested
    @DisplayName("when observable async disabled")
    class WhenObservableAsyncDisabled {

        @Test
        @DisplayName("dispatching publisher is CompositeObservablePublisher")
        void dispatchingPublisherIsComposite() {
            BASE_RUNNER.withPropertyValues("failover.observable.async.enabled=false").run(ctx ->
                assertThat(ctx.getBean("failoverObservablePublisher", ObservablePublisher.class))
                    .isInstanceOf(CompositeObservablePublisher.class));
        }
    }

    // ── observable instance tag — mode=auto (default) ────────────────────────

    @Nested
    @DisplayName("when observable instance/cardinality are default (mode=auto)")
    class WhenObservableTaggingDefault {

        private final ApplicationContextRunner runner = BASE_RUNNER
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new);

        @Test
        @DisplayName("per-registry customizer wired; global always-filter absent; cardinality filter on")
        void autoModeCustomizerWiredAndCardinalityOn() {
            runner.run(ctx -> {
                assertThat(ctx.containsBean("failoverInstanceMeterRegistryCustomizer")).isTrue();
                assertThat(ctx.containsBean("failoverInstanceMeterFilter")).isFalse();
                assertThat(ctx.containsBean("failoverCardinalityMeterFilter")).isTrue();
            });
        }

        @Test
        @DisplayName("customizer tags non-Prometheus registries but skips a Prometheus one")
        void autoCustomizerSkipsPrometheus() {
            runner.run(ctx -> {
                @SuppressWarnings("unchecked")
                org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer<io.micrometer.core.instrument.MeterRegistry> customizer =
                        ctx.getBean("failoverInstanceMeterRegistryCustomizer",
                                org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer.class);

                SimpleMeterRegistry push = new SimpleMeterRegistry();
                customizer.customize(push);
                push.counter("failover.store.total").increment();
                push.counter("other.metric").increment();
                assertThat(push.find("failover.store.total").tagKeys("instance").counter())
                        .as("failover.* must carry instance tag on non-Prometheus registry").isNotNull();
                assertThat(push.find("other.metric").tagKeys("instance").counter())
                        .as("non-failover meters untouched").isNull();

                SimpleMeterRegistry prom = new FakePrometheusMeterRegistry();
                customizer.customize(prom);
                prom.counter("failover.store.total").increment();
                assertThat(prom.find("failover.store.total").tagKeys("instance").counter())
                        .as("Prometheus registry must NOT get our instance tag").isNull();
            });
        }
    }

    // ── observable instance tag — mode=always ────────────────────────────────

    @Nested
    @DisplayName("when instance mode=always")
    class WhenInstanceModeAlways {

        @Test
        @DisplayName("global filter tags failover.* on all registries; customizer absent")
        void alwaysFilterTagsOnlyFailoverMeters() {
            BASE_RUNNER
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(
                        "failover.observable.instance.mode=always",
                        "failover.observable.instance.id=order-svc:node-1")
                .run(ctx -> {
                    assertThat(ctx.containsBean("failoverInstanceMeterRegistryCustomizer")).isFalse();
                    MeterFilter filter = ctx.getBean("failoverInstanceMeterFilter", MeterFilter.class);
                    SimpleMeterRegistry registry = new SimpleMeterRegistry();
                    registry.config().meterFilter(filter);
                    registry.counter("failover.store.total").increment();
                    registry.counter("other.metric").increment();
                    assertThat(registry.get("failover.store.total").tag("instance", "order-svc:node-1").counter().count())
                            .isEqualTo(1.0);
                    assertThat(registry.find("other.metric").tag("instance", "order-svc:node-1").counter()).isNull();
                });
        }
    }

    // ── observable instance tag — mode=never ─────────────────────────────────

    @Nested
    @DisplayName("when instance mode=never")
    class WhenInstanceModeNever {

        @Test
        @DisplayName("neither customizer nor global filter wired")
        void neitherInstanceBean() {
            BASE_RUNNER
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues("failover.observable.instance.mode=never")
                .run(ctx -> {
                    assertThat(ctx.containsBean("failoverInstanceMeterRegistryCustomizer")).isFalse();
                    assertThat(ctx.containsBean("failoverInstanceMeterFilter")).isFalse();
                });
        }
    }

    // ── cardinality guard ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("when cardinality guard capped")
    class WhenCardinalityGuardCapped {

        @Test
        @DisplayName("denies new failover series once the distinct-name cap is reached")
        void capsDistinctFailoverNames() {
            BASE_RUNNER.withPropertyValues("failover.observable.cardinality.max-apis=2").run(ctx -> {
                MeterFilter filter = ctx.getBean("failoverCardinalityMeterFilter", MeterFilter.class);
                SimpleMeterRegistry registry = new SimpleMeterRegistry();
                registry.config().meterFilter(filter);
                registry.counter("failover.store.total", "name", "a").increment();
                registry.counter("failover.store.total", "name", "b").increment();
                registry.counter("failover.store.total", "name", "c").increment();
                assertThat(registry.find("failover.store.total").tag("name", "a").counter()).isNotNull();
                assertThat(registry.find("failover.store.total").tag("name", "b").counter()).isNotNull();
                assertThat(registry.find("failover.store.total").tag("name", "c").counter()).isNull();
            });
        }
    }

    // ── exception-policy ─────────────────────────────────────────────────────

    @Nested
    @DisplayName("when exception policy is never_throw")
    class WhenExceptionPolicyNeverThrow {

        @Test
        @DisplayName("NeverRethrowMethodExceptionPolicy loaded")
        void shouldLoadNeverRethrowPolicy() {
            BASE_RUNNER.withPropertyValues("failover.exception-policy=never_throw").run(ctx ->
                assertThat(ctx.getBean(MethodExceptionPolicy.class))
                    .isInstanceOf(NeverRethrowMethodExceptionPolicy.class));
        }
    }

    @Nested
    @DisplayName("when exception policy is custom")
    class WhenExceptionPolicyCustom {

        @Test
        @DisplayName("custom MethodExceptionPolicy bean overrides auto-configured default")
        void customPolicyBeanOverridesDefault() {
            BASE_RUNNER
                .withPropertyValues("failover.exception-policy=custom")
                .withUserConfiguration(CustomPolicyConfig.class)
                .run(ctx ->
                    assertThat(ctx.getBean(MethodExceptionPolicy.class))
                        .isInstanceOf(CustomPolicyConfig.AlwaysNullPolicy.class));
        }
    }

    // ── scheduler ────────────────────────────────────────────────────────────

    @Nested
    @DisplayName("when scheduler disabled")
    class WhenSchedulerDisabled {

        @Test
        @DisplayName("basic beans present; ObservableScheduler and ExpiryCleanupScheduler absent")
        void schedulersAbsentWhenDisabled() {
            BASE_RUNNER.withPropertyValues("failover.scheduler.enabled=false").run(ctx -> {
                assertBasicBean(ctx);
                assertBeansAreEmpty(ctx, ObservableScheduler.class);
                assertBeansAreEmpty(ctx, ExpiryCleanupScheduler.class);
            });
        }
    }

    // ── failover.type=custom ──────────────────────────────────────────────────

    @Nested
    @DisplayName("when failover type is custom")
    class WhenFailoverTypeCustom {

        @Test
        @DisplayName("custom FailoverExecution used; BasicFailoverExecution absent")
        void customFailoverExecutionUsed() {
            BASE_RUNNER
                .withPropertyValues("failover.type=custom")
                .withUserConfiguration(CustomFailoverExecutionConfig.class)
                .run(ctx -> {
                    assertThat(ctx.getBean(FailoverExecution.class))
                        .isInstanceOf(CustomFailoverExecutionConfig.NoOpFailoverExecution.class);
                    assertThat(ctx.getBeansOfType(BasicFailoverExecution.class)).isEmpty();
                });
        }
    }

    // ── failover.scatter.parallel ─────────────────────────────────────────────

    @Nested
    @DisplayName("when scatter parallel enabled")
    class WhenScatterParallelEnabled {

        @Test
        @DisplayName("scatterGatherExecutor registered with virtual threads; propagator unchanged")
        void scatterGatherExecutorRegistered() {
            BASE_RUNNER.withPropertyValues("failover.scatter.parallel=true").run(ctx -> {
                assertThat(ctx.containsBean("scatterGatherExecutor")).isTrue();
                assertThat(ctx.getBean("scatterGatherExecutor", TaskExecutor.class))
                    .isNotInstanceOf(BoundedTaskExecutor.class);
                assertThat(ctx.getBean("contextPropagator", ContextPropagator.class))
                    .isInstanceOf(MdcContextPropagator.class);
            });
        }
    }

    @Nested
    @DisplayName("when failover.scatter.concurrency-limit > 0")
    class WhenScatterExecutorBounded {

        @Test
        @DisplayName("scatterGatherExecutor wrapped in BoundedTaskExecutor")
        void scatterGatherExecutorBounded() {
            BASE_RUNNER.withPropertyValues(
                    "failover.scatter.parallel=true",
                    "failover.scatter.concurrency-limit=4",
                    "failover.scatter.rejection-policy=DISCARD")
                .run(ctx ->
                    assertThat(ctx.getBean("scatterGatherExecutor", TaskExecutor.class))
                        .isInstanceOf(BoundedTaskExecutor.class));
        }
    }

    @Nested
    @DisplayName("when custom scatterGatherExecutor bean declared")
    class WhenCustomScatterGatherExecutor {

        @Test
        @DisplayName("auto-configured scatterGatherExecutor replaced by custom bean")
        void customScatterGatherExecutorWins() {
            BASE_RUNNER
                .withPropertyValues("failover.scatter.parallel=true")
                .withUserConfiguration(CustomScatterExecutorConfig.class)
                .run(ctx ->
                    assertThat(ctx.getBean("scatterGatherExecutor", TaskExecutor.class))
                        .isInstanceOf(CustomScatterExecutorConfig.StubExecutor.class));
        }
    }

    // ── ContextPropagator wiring ──────────────────────────────────────────────

    @Nested
    @DisplayName("when custom ContextPropagator bean declared")
    class WhenCustomContextPropagator {

        @Test
        @DisplayName("custom bean wins via @ConditionalOnMissingBean; exactly one propagator registered")
        void customContextPropagatorWins() {
            BASE_RUNNER.withUserConfiguration(CustomContextPropagatorConfig.class).run(ctx -> {
                assertThat(ctx.getBean("contextPropagator", ContextPropagator.class))
                    .isInstanceOf(CustomContextPropagatorConfig.StubPropagator.class);
                assertThat(ctx.getBeansOfType(ContextPropagator.class)).hasSize(1);
            });
        }
    }

    @Nested
    @DisplayName("when a custom, differently-named ContextPropagator bean is present")
    class WhenCustomNamedContextPropagatorPresent {

        @Test
        @DisplayName("auto-composed contextPropagator is Composite containing the custom propagator")
        void customPropagatorJoinsChain() {
            BASE_RUNNER.withUserConfiguration(CustomNamedPropagatorConfig.class).run(ctx -> {
                ContextPropagator propagator = ctx.getBean("contextPropagator", ContextPropagator.class);
                assertThat(propagator).isInstanceOf(CompositeContextPropagator.class);

                CustomNamedPropagatorConfig.MarkerPropagator marker =
                        ctx.getBean(CustomNamedPropagatorConfig.MarkerPropagator.class);
                marker.wrapped.set(false);
                propagator.wrap(() -> { });
                assertThat(marker.wrapped.get()).isTrue();
            });
        }
    }

    @Nested
    @DisplayName("when TenantContextPropagator bean present")
    class WhenTenantContextPropagatorPresent {

        @Test
        @DisplayName("contextPropagator is CompositeContextPropagator; TenantContextPropagator registered")
        void tenantPropagatorComposed() {
            BASE_RUNNER.withUserConfiguration(MockTenantPropagatorConfig.class).run(ctx -> {
                assertThat(ctx.getBean("contextPropagator", ContextPropagator.class))
                    .isInstanceOf(CompositeContextPropagator.class);
                assertThat(ctx.getBean(TenantContextPropagator.class)).isNotNull();
            });
        }
    }

    @Nested
    @DisplayName("when Micrometer Tracer bean present")
    class WhenMicrometerTracerPresent {

        @Test
        @DisplayName("MicrometerContextPropagator registered; contextPropagator is Composite")
        void micrometerPropagatorComposed() {
            BASE_RUNNER.withUserConfiguration(MockTracerConfig.class).run(ctx -> {
                assertThat(ctx.getBean(MicrometerContextPropagator.class)).isNotNull();
                assertThat(ctx.getBean("contextPropagator", ContextPropagator.class))
                    .isInstanceOf(CompositeContextPropagator.class);
            });
        }
    }

    @Nested
    @DisplayName("when both TenantContextPropagator and Micrometer Tracer present")
    class WhenTenantAndMicrometerBothPresent {

        @Test
        @DisplayName("contextPropagator is Composite; all three propagator beans registered")
        void threeElementComposite() {
            BASE_RUNNER
                .withUserConfiguration(MockTenantPropagatorConfig.class, MockTracerConfig.class)
                .run(ctx -> {
                    assertThat(ctx.getBean("contextPropagator", ContextPropagator.class))
                        .isInstanceOf(CompositeContextPropagator.class);
                    assertThat(ctx.getBean(TenantContextPropagator.class)).isNotNull();
                    assertThat(ctx.getBean(MicrometerContextPropagator.class)).isNotNull();
                });
        }
    }
}
