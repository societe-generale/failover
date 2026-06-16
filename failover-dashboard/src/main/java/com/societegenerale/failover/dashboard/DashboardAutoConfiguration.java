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

package com.societegenerale.failover.dashboard;

import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET;

/**
 * Auto-configuration for the embedded failover dashboard.
 *
 * <p><strong>Secure-by-default (design doc §1a):</strong> an absent {@code failover.dashboard.enabled}
 * property means <em>off</em> — no {@code matchIfMissing}. The consumer must explicitly opt in via YAML.
 * With the property unset, no bean, controller, resource handler, JSON, or UI is mapped at all.
 *
 * <p>P0 scope: this is a skeleton. It wires the master switch, the configuration properties, and a
 * static-asset resource handler for the (currently empty) UI. The config/metrics services and the
 * REST controller arrive in P1–P3.
 *
 * @author Anand Manissery
 */
@Slf4j
@AutoConfiguration
@ConditionalOnWebApplication(type = SERVLET)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(prefix = "failover.dashboard", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DashboardProperties.class)
public class DashboardAutoConfiguration implements WebMvcConfigurer {

    private final DashboardProperties properties;

    public DashboardAutoConfiguration(DashboardProperties properties) {
        this.properties = properties;
        log.info("Failover dashboard enabled at base path '{}'.", properties.basePath());
    }

    @Override
    public void addResourceHandlers(ResourceHandlerRegistry registry) {
        registry.addResourceHandler(properties.basePath() + "/**")
                .addResourceLocations("classpath:/failover-dashboard/");
    }
}
