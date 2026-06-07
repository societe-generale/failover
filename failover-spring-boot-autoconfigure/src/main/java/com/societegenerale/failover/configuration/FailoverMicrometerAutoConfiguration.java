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
import com.societegenerale.failover.observable.micrometer.FailoverMeterBinder;
import com.societegenerale.failover.observable.micrometer.MicrometerObservablePublisher;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;

/**
 * Autoconfiguration for Micrometer-based failover metrics.
 *
 * <p>Active only when a {@link MeterRegistry} bean is present in the application context.
 * {@code @ConditionalOnBean(MeterRegistry.class)} is evaluated at the outer autoconfiguration
 * class level — guaranteeing it runs after all user bean definitions are loaded, making it
 * visible to beans from any configuration class (including test configurations).
 *
 * <p>The class-level {@code @ConditionalOnClass(name = ...)} uses the string form so that
 * Spring Boot's ASM reader evaluates the condition without loading the class into the JVM.
 * {@code micrometer-core} is a required transitive dependency of {@code failover-monitoring},
 * so {@link MeterRegistry} is always on the classpath for consumers of this autoconfiguration.
 *
 * @author Anand Manissery
 */
@AutoConfiguration(after = FailoverAutoConfiguration.class)
@ConditionalOnExpression("${failover.enabled:true} eq true")
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
@ConditionalOnBean(MeterRegistry.class)
public class FailoverMicrometerAutoConfiguration {

    /** No-arg constructor for Spring autoconfiguration instantiation. */
    public FailoverMicrometerAutoConfiguration() {}

    /**
     * Emits {@code failover.store.total}, {@code failover.recover.total},
     * {@code failover.exception.total}, and {@code failover.operation.duration} meters.
     *
     * @param meterRegistry active meter registry
     * @return {@link MicrometerObservablePublisher}
     */
    @ConditionalOnMissingBean(MicrometerObservablePublisher.class)
    @Bean
    public ObservablePublisher micrometerObservablePublisher(MeterRegistry meterRegistry) {
        return new MicrometerObservablePublisher(meterRegistry);
    }

    /**
     * Binds {@code failover.registered.total} and per-failover
     * {@code failover.config.expiry.seconds} gauges to the registry.
     *
     * @param failoverScanner  scanner providing the list of registered failovers
     * @param expiryExtractor  extracts expiry configuration from each failover
     * @return {@link FailoverMeterBinder}
     */
    @ConditionalOnMissingBean(FailoverMeterBinder.class)
    @Bean
    public FailoverMeterBinder failoverMeterBinder(
            FailoverScanner failoverScanner,
            FailoverExpiryExtractor expiryExtractor) {
        return new FailoverMeterBinder(failoverScanner, expiryExtractor);
    }
}
