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

package com.societegenerale.failover.testconfig;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Test-only configuration that registers a {@link SimpleMeterRegistry}.
 *
 * <p>Placed in {@code com.societegenerale.failover.testconfig} — outside the
 * component-scan root ({@code com.societegenerale.failover.configuration}) — so it is
 * never picked up automatically. Include explicitly via
 * {@code @SpringBootTest(classes = {..., SimpleMeterRegistryConfig.class})}.
 *
 * <p>Uses plain {@code @Configuration} (not {@code @TestConfiguration}) so that its
 * bean definitions are registered before autoconfiguration conditions are evaluated,
 * making the {@link MeterRegistry} bean visible to {@code @ConditionalOnBean}.
 */
@Configuration
public class SimpleMeterRegistryConfig {

    @Bean
    public MeterRegistry meterRegistry() {
        return new SimpleMeterRegistry();
    }
}
