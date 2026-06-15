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

import com.societegenerale.failover.propagator.MicrometerContextPropagator;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests {@link MicrometerTracingAutoConfiguration} conditional bean registration.
 *
 * @author Anand Manissery
 */
@SuppressWarnings("java:S2699")
class MicrometerTracingAutoConfigurationTest {

    private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(MicrometerTracingAutoConfiguration.class));

    @Nested
    @DisplayName("when Tracer bean present")
    class WhenTracerBeanPresent {

        @Test
        @DisplayName("registers MicrometerContextPropagator")
        void registersMicrometerContextPropagator() {
            contextRunner
                    .withBean(Tracer.class, () -> mock(Tracer.class))
                    .run(ctx -> assertThat(ctx).hasSingleBean(MicrometerContextPropagator.class));
        }

        @Test
        @DisplayName("does not override existing MicrometerContextPropagator bean")
        void doesNotOverrideExistingBean() {
            MicrometerContextPropagator custom = new MicrometerContextPropagator(mock(Tracer.class));
            contextRunner
                    .withBean(Tracer.class, () -> mock(Tracer.class))
                    .withBean("customPropagator", MicrometerContextPropagator.class, () -> custom)
                    .run(ctx -> {
                        assertThat(ctx).hasSingleBean(MicrometerContextPropagator.class);
                        assertThat(ctx.getBean(MicrometerContextPropagator.class)).isSameAs(custom);
                    });
        }
    }

    @Nested
    @DisplayName("when Tracer bean absent")
    class WhenTracerBeanAbsent {

        @Test
        @DisplayName("does not register MicrometerContextPropagator")
        void doesNotRegisterMicrometerContextPropagator() {
            contextRunner
                    .run(ctx -> assertThat(ctx).doesNotHaveBean(MicrometerContextPropagator.class));
        }
    }

    @Nested
    @DisplayName("autoconfiguration ordering")
    class Ordering {

        /**
         * The {@code Tracer} that {@code @ConditionalOnBean(Tracer.class)} gates on is contributed by
         * Spring Boot's own tracing autoconfigurations (Brave / OpenTelemetry). This autoconfiguration
         * must therefore be ordered <em>after</em> them, or the condition is evaluated before the
         * {@code Tracer} exists and {@link MicrometerContextPropagator} silently never registers.
         * A behavioural test would require Brave/OTel test dependencies (only the micrometer-tracing
         * API is on the test classpath), so this guards the ordering declaration directly to prevent
         * accidental removal. See finding A6 in the audit report.
         */
        @Test
        @DisplayName("declared after the Spring Boot Brave/OpenTelemetry tracing autoconfigurations")
        void orderedAfterBootTracingAutoConfigurations() {
            AutoConfiguration annotation =
                    MicrometerTracingAutoConfiguration.class.getAnnotation(AutoConfiguration.class);
            assertThat(annotation).isNotNull();
            assertThat(annotation.afterName()).contains(
                    "org.springframework.boot.micrometer.tracing.brave.autoconfigure.BraveAutoConfiguration",
                    "org.springframework.boot.micrometer.tracing.otel.autoconfigure.OpenTelemetryTracingAutoConfiguration");
        }
    }
}