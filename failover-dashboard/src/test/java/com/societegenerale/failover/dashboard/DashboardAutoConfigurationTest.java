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

import com.societegenerale.failover.core.scanner.FailoverScanner;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Autoconfiguration tests for the P0 dashboard skeleton, including the secure-by-default contract
 * (design doc §1a / §13): with {@code failover.dashboard.enabled} unset, nothing is registered.
 *
 * @author Anand Manissery
 */
class DashboardAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withBean(FailoverScanner.class, () -> Mockito.mock(FailoverScanner.class))
            .withConfiguration(AutoConfigurations.of(DashboardAutoConfiguration.class));

    @Test
    @DisplayName("secure-by-default — enabled property unset ⇒ no dashboard bean registered")
    void disabledByDefault() {
        runner.run(ctx -> {
            assertThat(ctx).doesNotHaveBean(DashboardAutoConfiguration.class);
            assertThat(ctx).doesNotHaveBean(DashboardProperties.class);
        });
    }

    @Test
    @DisplayName("enabled=false explicitly ⇒ still no dashboard bean")
    void disabledExplicitly() {
        runner.withPropertyValues("failover.dashboard.enabled=false")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DashboardAutoConfiguration.class));
    }

    @Test
    @DisplayName("enabled=true ⇒ autoconfig + properties registered with default base path")
    void enabledRegistersBeans() {
        runner.withPropertyValues("failover.dashboard.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DashboardAutoConfiguration.class);
                    assertThat(ctx).hasSingleBean(DashboardProperties.class);
                    assertThat(ctx).hasSingleBean(DashboardConfigService.class);
                    assertThat(ctx).hasSingleBean(DashboardController.class);
                    assertThat(ctx.getBean(DashboardProperties.class).basePath())
                            .isEqualTo("/failover-dashboard");
                });
    }

    @Test
    @DisplayName("no MeterRegistry ⇒ metrics beans absent, config view still works (graceful degradation)")
    void metricsAbsentWithoutRegistry() {
        runner.withPropertyValues("failover.dashboard.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DashboardConfigService.class);
                    assertThat(ctx).doesNotHaveBean(DashboardMetricsService.class);
                    assertThat(ctx).doesNotHaveBean(DashboardMetricsController.class);
                });
    }

    @Test
    @DisplayName("MeterRegistry present ⇒ metrics service + controller registered")
    void metricsPresentWithRegistry() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues("failover.dashboard.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DashboardMetricsService.class);
                    assertThat(ctx).hasSingleBean(DashboardMetricsController.class);
                });
    }

    @Test
    @DisplayName("custom base-path property is bound")
    void customBasePathBound() {
        runner.withPropertyValues(
                        "failover.dashboard.enabled=true",
                        "failover.dashboard.base-path=/ops/failover")
                .run(ctx -> assertThat(ctx.getBean(DashboardProperties.class).basePath())
                        .isEqualTo("/ops/failover"));
    }

    @Test
    @DisplayName("root or trailing-slash base-path is rejected — context fails fast (cannot collide with user services)")
    void invalidBasePathFailsFast() {
        runner.withPropertyValues(
                        "failover.dashboard.enabled=true",
                        "failover.dashboard.base-path=/")
                .run(ctx -> assertThat(ctx).hasFailed());

        runner.withPropertyValues(
                        "failover.dashboard.enabled=true",
                        "failover.dashboard.base-path=failover-dashboard")
                .run(ctx -> assertThat(ctx).hasFailed());

        runner.withPropertyValues(
                        "failover.dashboard.enabled=true",
                        "failover.dashboard.base-path=/failover-dashboard/")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"/", "/dash/", "dash", " ", ""})
    @DisplayName("invalid base-path values are rejected at construction")
    void invalidBasePathRejected(String basePath) {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new DashboardProperties(true, basePath))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("base-path");
    }

    @Test
    @DisplayName("null base-path is rejected at construction")
    void nullBasePathRejected() {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new DashboardProperties(true, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(strings = {"/failover-dashboard", "/ops/failover"})
    @DisplayName("valid dedicated, non-root base-path values are accepted")
    void validBasePathAccepted(String basePath) {
        assertThat(new DashboardProperties(true, basePath).basePath()).isEqualTo(basePath);
    }

    @Test
    @DisplayName("addResourceHandlers maps the static UI under the configured base path")
    void registersResourceHandler() {
        DashboardProperties props = new DashboardProperties(true, "/failover-dashboard");
        ResourceHandlerRegistry registry = Mockito.mock(ResourceHandlerRegistry.class, Mockito.RETURNS_DEEP_STUBS);

        new DashboardAutoConfiguration(props).addResourceHandlers(registry);

        Mockito.verify(registry).addResourceHandler("/failover-dashboard/**");
    }
}
