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

import com.societegenerale.failover.propagator.MicrometerContextPropagator;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

/**
 * Tests {@link MicrometerTracingAutoConfiguration} conditional bean registration.
 *
 * @author Anand Manissery
 */
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
}