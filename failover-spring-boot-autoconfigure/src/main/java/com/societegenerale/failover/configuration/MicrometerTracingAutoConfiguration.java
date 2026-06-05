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

import com.societegenerale.failover.core.propagator.ContextPropagator;
import com.societegenerale.failover.propagator.MicrometerContextPropagator;
import io.micrometer.tracing.Tracer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Auto-configuration that registers {@link MicrometerContextPropagator} when
 * {@code io.micrometer.tracing.Tracer} is on the classpath and a {@link Tracer} bean exists.
 *
 * <p>Separated from {@link FailoverAutoConfiguration} so this class — which references
 * Micrometer types in its method signatures — is only loaded by the JVM when
 * {@code @ConditionalOnClass} is satisfied. Loading it when Micrometer is absent would
 * cause {@link NoClassDefFoundError}.
 *
 * <p>The registered bean is picked up by {@link FailoverAutoConfiguration#contextPropagator}
 * via {@code ObjectProvider} and composed into the active {@link ContextPropagator}.
 *
 * @author Anand Manissery
 * @see MicrometerContextPropagator
 * @see FailoverAutoConfiguration
 */
@AutoConfiguration(after = FailoverAutoConfiguration.class)
@ConditionalOnClass(name = "io.micrometer.tracing.Tracer")
@Slf4j
public class MicrometerTracingAutoConfiguration {

    /** No-arg constructor for Spring autoconfiguration instantiation. */
    public MicrometerTracingAutoConfiguration() {}

    /**
     * Registers a {@link MicrometerContextPropagator} that carries the active span across scatter executor threads.
     *
     * @param tracer active Micrometer {@link Tracer} bean
     * @return {@link MicrometerContextPropagator}
     */
    @ConditionalOnMissingBean
    @ConditionalOnBean(Tracer.class)
    @Bean
    public MicrometerContextPropagator micrometerContextPropagator(Tracer tracer) {
        log.info("MicrometerContextPropagator registered — scatter/gather slices will carry active Span across executor threads.");
        return new MicrometerContextPropagator(tracer);
    }
}