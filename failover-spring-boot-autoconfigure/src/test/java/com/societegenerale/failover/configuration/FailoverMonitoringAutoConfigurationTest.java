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

import com.societegenerale.failover.core.expiry.FailoverExpiryExtractor;
import com.societegenerale.failover.core.observable.publisher.ObservablePublisher;
import com.societegenerale.failover.core.observable.scanner.FailoverScanner;
import com.societegenerale.failover.observable.micrometer.health.FailoverHealthIndicator;
import com.societegenerale.failover.observable.micrometer.FailoverMeterBinder;
import com.societegenerale.failover.observable.micrometer.MicrometerObservablePublisher;
import com.societegenerale.failover.observable.scanner.SpringContextFailoverScanner;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.AutoConfigurations;
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

    /**
     * Thin runner targeting only {@link MicrometerTracingAutoConfiguration} with the minimal
     * user beans that {@link FailoverMeterBinder} requires. The runner ensures user beans are
     * registered before autoconfiguration conditions, making {@code @ConditionalOnBean} work.
     */
    private final ApplicationContextRunner micrometerRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(FailoverMicrometerAutoConfiguration.class))
            .withBean(FailoverScanner.class, () -> mock(FailoverScanner.class))
            .withBean(FailoverExpiryExtractor.class, () -> mock(FailoverExpiryExtractor.class));

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

        @Test
        @DisplayName("MicrometerObservablePublisher is registered as a ObservablePublisher")
        void micrometerObservablePublisherRegistered() {
            micrometerRunner
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(ctx -> assertThat(ctx.getBeansOfType(ObservablePublisher.class).values())
                    .anyMatch(p -> p instanceof MicrometerObservablePublisher));
        }

        @Test
        @DisplayName("FailoverMeterBinder bean is registered")
        void failoverMeterBinderRegistered() {
            micrometerRunner
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(ctx -> assertThat(ctx.getBean(FailoverMeterBinder.class)).isNotNull());
        }

        @Test
        @DisplayName("failover.registered.total gauge is present after bindTo(registry)")
        void registeredTotalGaugePresent() {
            micrometerRunner
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .run(ctx -> {
                    MeterRegistry registry = ctx.getBean(MeterRegistry.class);
                    FailoverMeterBinder binder = ctx.getBean(FailoverMeterBinder.class);
                    binder.bindTo(registry);
                    assertThat(registry.find("failover.registered.total").gauge()).isNotNull();
                });
        }
    }

    @Nested
    @DisplayName("Micrometer — MicrometerObservablePublisher absent when no MeterRegistry")
    class WhenNoMeterRegistry {

        @Test
        @DisplayName("MicrometerObservablePublisher NOT registered when MeterRegistry absent")
        void micrometerPublisherNotRegisteredWithoutRegistry() {
            micrometerRunner.run(ctx ->
                assertThat(ctx.getBeansOfType(MicrometerObservablePublisher.class)).isEmpty());
        }

        @Test
        @DisplayName("FailoverMeterBinder NOT registered when MeterRegistry absent")
        void failoverMeterBinderNotRegisteredWithoutRegistry() {
            micrometerRunner.run(ctx ->
                assertThat(ctx.getBeansOfType(FailoverMeterBinder.class)).isEmpty());
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
