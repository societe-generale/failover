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

import com.societegenerale.failover.core.expiry.FailoverExpiryExtractor;
import com.societegenerale.failover.core.observable.InstanceIdResolver;
import com.societegenerale.failover.observable.metrics.DefaultInstanceIdResolver;
import com.societegenerale.failover.core.observable.publisher.ObservablePublisher;
import com.societegenerale.failover.core.scanner.FailoverScanner;
import com.societegenerale.failover.observable.micrometer.health.FailoverHealthIndicator;
import com.societegenerale.failover.observable.micrometer.ClusterSnapshotPublisher;
import com.societegenerale.failover.observable.micrometer.FailoverMeterBinder;
import com.societegenerale.failover.observable.micrometer.MicrometerObservablePublisher;
import com.societegenerale.failover.scanner.SpringContextFailoverScanner;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration;
import org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.context.ApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.test.context.TestPropertySource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Integration tests for monitoring beans wired by {@link FailoverAutoConfiguration} and
 * {@link MicrometerTracingAutoConfiguration}.
 *
 * <p>Micrometer-conditional beans ({@link WhenMeterRegistryPresent}, {@link WhenNoMeterRegistry})
 * use {@link ApplicationContextRunner} — the canonical Spring Boot approach for testing
 * autoconfiguration {@code @ConditionalOnBean} clauses, because user beans provided to the
 * runner are always visible before autoconfiguration conditions are evaluated.
 *
 * @author Anand Manissery
 */
class FailoverMonitoringAutoConfigurationTest {

    // ── Scanner ───────────────────────────────────────────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @DisplayName("scanner — SpringContextFailoverScanner is the default bean")
    class ScannerBean {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("FailoverScanner bean is SpringContextFailoverScanner")
        void scannerBeanIsSpringContextScanner() {
            FailoverScanner scanner = applicationContext.getBean(FailoverScanner.class);
            assertThat(scanner).isInstanceOf(SpringContextFailoverScanner.class);
        }

        @Test
        @DisplayName("custom FailoverScanner bean overrides auto-configured scanner")
        void customScannerOverridesDefault() {
            assertThat(applicationContext.getBean(FailoverScanner.class)).isNotNull();
        }
    }

    // ── Micrometer publishers (ApplicationContextRunner) ─────────────────────

    @Nested
    @DisplayName("Micrometer — MicrometerObservablePublisher and FailoverMeterBinder present when MeterRegistry available")
    class WhenMeterRegistryPresent {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FailoverMicrometerAutoConfiguration.class))
                .withBean(FailoverScanner.class, () -> mock(FailoverScanner.class))
                .withBean(FailoverExpiryExtractor.class, () -> mock(FailoverExpiryExtractor.class));

        @Test
        @DisplayName("MicrometerObservablePublisher is registered as a ObservablePublisher")
        void micrometerObservablePublisherRegistered() {
            runner
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(ctx -> assertThat(ctx.getBeansOfType(ObservablePublisher.class).values())
                    .anyMatch(p -> p instanceof MicrometerObservablePublisher));
        }

        @Test
        @DisplayName("FailoverMeterBinder bean is registered")
        void failoverMeterBinderRegistered() {
            runner
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(ctx -> assertThat(ctx.getBean(FailoverMeterBinder.class)).isNotNull());
        }

        @Test
        @DisplayName("failover.registered.total gauge is present after bindTo(registry)")
        void registeredTotalGaugePresent() {
            runner
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(ctx -> {
                    MeterRegistry registry = ctx.getBean(MeterRegistry.class);
                    FailoverMeterBinder binder = ctx.getBean(FailoverMeterBinder.class);
                    binder.bindTo(registry);
                    assertThat(registry.find("failover.registered.total").gauge()).isNotNull();
                });
        }

        @Test
        @DisplayName("DefaultInstanceIdResolver bean is registered when MeterRegistry present")
        void instanceIdResolverRegisteredByDefault() {
            runner
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(InstanceIdResolver.class);
                    assertThat(ctx.getBean(InstanceIdResolver.class))
                            .isInstanceOf(DefaultInstanceIdResolver.class);
                    assertThat(ctx.getBean(InstanceIdResolver.class).resolve()).isNotBlank();
                });
        }

        @Test
        @DisplayName("custom InstanceIdResolver bean honoured via @ConditionalOnMissingBean")
        void customInstanceIdResolverHonouredViaMissingBean() {
            InstanceIdResolver custom = () -> "my-pod:10.0.0.1:8080";
            runner
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(InstanceIdResolver.class, () -> custom)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(InstanceIdResolver.class);
                    assertThat(ctx.getBean(InstanceIdResolver.class)).isSameAs(custom);
                });
        }
    }

    @Nested
    @DisplayName("Micrometer — MicrometerObservablePublisher absent when no MeterRegistry")
    class WhenNoMeterRegistry {

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FailoverMicrometerAutoConfiguration.class))
                .withBean(FailoverScanner.class, () -> mock(FailoverScanner.class))
                .withBean(FailoverExpiryExtractor.class, () -> mock(FailoverExpiryExtractor.class));

        @Test
        @DisplayName("MicrometerObservablePublisher NOT registered when MeterRegistry absent")
        void micrometerPublisherNotRegisteredWithoutRegistry() {
            runner.run(ctx ->
                assertThat(ctx.getBeansOfType(MicrometerObservablePublisher.class)).isEmpty());
        }

        @Test
        @DisplayName("FailoverMeterBinder NOT registered when MeterRegistry absent")
        void failoverMeterBinderNotRegisteredWithoutRegistry() {
            runner.run(ctx ->
                assertThat(ctx.getBeansOfType(FailoverMeterBinder.class)).isEmpty());
        }

        @Test
        @SuppressWarnings("java:S2699")
        @DisplayName("InstanceIdResolver NOT registered when MeterRegistry absent")
        void instanceIdResolverNotRegisteredWithoutRegistry() {
            runner.run(ctx -> assertThat(ctx.getBeansOfType(InstanceIdResolver.class)).isEmpty());
        }
    }

    @Nested
    @DisplayName("Micrometer — ordering regression: publisher registered when the MeterRegistry is auto-configured by Spring Boot")
    class WhenMeterRegistryAutoConfigured {

        // Exercises real Boot autoconfiguration ordering: FailoverMicrometerAutoConfiguration must be
        // after the Boot metrics autoconfigurations so its @ConditionalOnBean(MeterRegistry) sees the registry.
        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        MetricsAutoConfiguration.class,
                        CompositeMeterRegistryAutoConfiguration.class,
                        SimpleMetricsExportAutoConfiguration.class,
                        FailoverMicrometerAutoConfiguration.class))
                .withBean(FailoverScanner.class, () -> mock(FailoverScanner.class))
                .withBean(FailoverExpiryExtractor.class, () -> mock(FailoverExpiryExtractor.class));

        @Test
        @DisplayName("auto-configured MeterRegistry is visible to FailoverMicrometerAutoConfiguration → MicrometerObservablePublisher registered")
        void publisherRegisteredWhenRegistryAutoConfigured() {
            runner.run(ctx -> {
                assertThat(ctx).hasSingleBean(MeterRegistry.class);
                assertThat(ctx.getBeansOfType(ObservablePublisher.class).values())
                        .anyMatch(MicrometerObservablePublisher.class::isInstance);
                assertThat(ctx).hasSingleBean(FailoverMeterBinder.class);
            });
        }
    }

    // ── Health indicator ──────────────────────────────────────────────────────

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @DisplayName("health — FailoverHealthIndicator registered when Actuator on classpath")
    class WhenActuatorPresent {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("FailoverHealthIndicator bean is registered")
        void failoverHealthIndicatorRegistered() {
            assertThat(applicationContext.getBean(FailoverHealthIndicator.class)).isNotNull();
        }

        @Test
        @DisplayName("health indicator status is UP or DOWN — must not throw")
        void healthIndicatorResponds() {
            FailoverHealthIndicator indicator = applicationContext.getBean(FailoverHealthIndicator.class);
            Health health = indicator.health();
            assertThat(health).isNotNull();
            assertThat(health.getStatus()).isIn(Status.UP, Status.DOWN);
        }
    }

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class, CustomMicrometerPublisherConfig.class})
    @DisplayName("custom MicrometerObservablePublisher overrides auto-configured one")
    class WhenCustomMicrometerPublisher {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("exactly one MicrometerObservablePublisher — custom bean wins via @ConditionalOnMissingBean")
        void customPublisherWins() {
            assertThat(applicationContext.getBeansOfType(MicrometerObservablePublisher.class)).hasSize(1);
            assertThat(applicationContext.getBean(MicrometerObservablePublisher.class))
                .isInstanceOf(CustomMicrometerPublisherConfig.StubMicrometerPublisher.class);
        }
    }

    @Nested
    @SpringBootTest(classes = {MyTestApplication.class})
    @TestPropertySource(properties = {"failover.enabled=false"})
    @DisplayName("when failover disabled — monitoring beans absent")
    class WhenFailoverDisabled {

        @Autowired
        private ApplicationContext applicationContext;

        @Test
        @DisplayName("FailoverHealthIndicator NOT registered when failover disabled")
        void healthIndicatorAbsent() {
            assertThat(applicationContext.getBeansOfType(FailoverHealthIndicator.class)).isEmpty();
        }
    }

    // ── cluster snapshot publisher ────────────────────────────────────────────

    @Nested
    @DisplayName("cluster snapshot publisher — wired by FailoverMicrometerAutoConfiguration")
    class WhenClusterPublisher {

        private static final String PUBLISH_URL = "http://dashboard:8080/failover-dashboard";

        private final ApplicationContextRunner runner = new ApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(FailoverMicrometerAutoConfiguration.class))
                .withBean(FailoverScanner.class, () -> mock(FailoverScanner.class))
                .withBean(FailoverExpiryExtractor.class, () -> mock(FailoverExpiryExtractor.class));

        @Test
        @SuppressWarnings("java:S2699")
        @DisplayName("publish-url + username ⇒ ClusterSnapshotPublisher wired (Basic Auth)")
        void publisherWiredWithBasicAuth() {
            runner
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(
                    "failover.dashboard.cluster.snapshot.publish-url=" + PUBLISH_URL,
                    "failover.dashboard.cluster.snapshot.username=peer",
                    "failover.dashboard.cluster.snapshot.password=secret")
                .run(ctx -> assertThat(ctx).hasSingleBean(ClusterSnapshotPublisher.class));
        }

        @Test
        @SuppressWarnings("java:S2699")
        @DisplayName("publish-url + oauth2-client-registration-id + OAuth2AuthorizedClientManager ⇒ publisher + OAuth2 interceptor wired")
        void publisherWiredWithOAuth2() {
            runner
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager.class,
                    () -> mock(org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager.class))
                .withPropertyValues(
                    "failover.dashboard.cluster.snapshot.publish-url=" + PUBLISH_URL,
                    "failover.dashboard.cluster.snapshot.oauth2-client-registration-id=failover-dashboard")
                .run(ctx -> {
                    assertThat(ctx).hasBean("failoverSnapshotOAuth2Interceptor");
                    assertThat(ctx).hasSingleBean(ClusterSnapshotPublisher.class);
                });
        }

        @Test
        @SuppressWarnings("java:S2699")
        @DisplayName("publish-url, no auth ⇒ publisher wired in insecure mode")
        void publisherWiredInsecureWhenNoAuthConfigured() {
            runner
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(
                    "failover.dashboard.cluster.snapshot.publish-url=" + PUBLISH_URL)
                .run(ctx -> assertThat(ctx).hasSingleBean(ClusterSnapshotPublisher.class));
        }

        @Test
        @SuppressWarnings("java:S2699")
        @DisplayName("publish-url absent ⇒ ClusterSnapshotPublisher not wired")
        void publisherNotWiredWhenPublishUrlAbsent() {
            runner
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(ClusterSnapshotPublisher.class));
        }

        @Test
        @SuppressWarnings("java:S2699")
        @DisplayName("heartbeat.enabled=true + publish-url ⇒ HeartbeatPublisher wired")
        void heartbeatPublisherWiredWhenEnabled() {
            runner
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(
                    "failover.dashboard.cluster.snapshot.publish-url=" + PUBLISH_URL,
                    "failover.dashboard.cluster.snapshot.heartbeat.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(
                        com.societegenerale.failover.observable.micrometer.HeartbeatPublisher.class));
        }

        @Test
        @SuppressWarnings("java:S2699")
        @DisplayName("heartbeat.enabled=false (default) ⇒ HeartbeatPublisher not wired")
        void heartbeatPublisherNotWiredByDefault() {
            runner
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withPropertyValues(
                    "failover.dashboard.cluster.snapshot.publish-url=" + PUBLISH_URL)
                .run(ctx -> assertThat(ctx).doesNotHaveBean(
                        com.societegenerale.failover.observable.micrometer.HeartbeatPublisher.class));
        }

    }

    // ── @TestConfiguration helpers ────────────────────────────────────────────

    @TestConfiguration
    static class CustomMicrometerPublisherConfig {

        static class StubMicrometerPublisher extends MicrometerObservablePublisher {
            StubMicrometerPublisher(MeterRegistry r) { super(r); }
        }

        @Bean
        public MicrometerObservablePublisher micrometerObservablePublisher() {
            return new StubMicrometerPublisher(new SimpleMeterRegistry());
        }
    }
}
