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

package com.societegenerale.failover.dashboard.config;

import com.societegenerale.failover.dashboard.service.DashboardConfigService;
import com.societegenerale.failover.dashboard.service.DashboardMetricsService;
import com.societegenerale.failover.dashboard.service.DashboardHistoryService;
import com.societegenerale.failover.dashboard.web.DashboardController;
import com.societegenerale.failover.dashboard.web.DashboardMetricsController;
import com.societegenerale.failover.dashboard.metrics.source.MetricsSource;
import com.societegenerale.failover.dashboard.metrics.source.LocalRegistryMetricsSource;
import com.societegenerale.failover.dashboard.metrics.source.prometheus.PrometheusMetricsSource;
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
            .withConfiguration(AutoConfigurations.of(
                    org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration.class,
                    org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration.class,
                    DashboardAutoConfiguration.class));

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
    @DisplayName("MeterRegistry present ⇒ metrics service + source + controller registered")
    void metricsPresentWithRegistry() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues("failover.dashboard.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(DashboardMetricsService.class);
                    assertThat(ctx).hasSingleBean(DashboardMetricsController.class);
                    assertThat(ctx).hasSingleBean(MetricsSource.class);
                    assertThat(ctx.getBean(MetricsSource.class)).isInstanceOf(LocalRegistryMetricsSource.class);
                    assertThat(ctx.getBean(MetricsSource.class).info().mode()).isEqualTo("local");
                });
    }

    @Test
    @DisplayName("cluster.mode=prometheus without a base-url ⇒ falls back to the local source")
    void prometheusModeWithoutBaseUrlFallsBackToLocal() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues("failover.dashboard.enabled=true",
                        "failover.dashboard.cluster.mode=prometheus")
                .run(ctx -> {
                    assertThat(ctx.getBean(DashboardProperties.class).cluster().mode()).isEqualTo("prometheus");
                    assertThat(ctx.getBean(MetricsSource.class)).isInstanceOf(LocalRegistryMetricsSource.class);
                    assertThat(ctx.getBean(MetricsSource.class).info().mode()).isEqualTo("local");
                });
    }

    @Test
    @DisplayName("cluster.mode=prometheus with a base-url ⇒ the Prometheus source is wired")
    void prometheusModeWithBaseUrlWiresPrometheusSource() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues("failover.dashboard.enabled=true",
                        "failover.dashboard.cluster.mode=prometheus",
                        "failover.dashboard.cluster.prometheus.base-url=http://prometheus:9090")
                .run(ctx -> assertThat(ctx.getBean(MetricsSource.class))
                        .isInstanceOf(PrometheusMetricsSource.class));
    }

    @Test
    @DisplayName("cluster.mode=shared-store ⇒ SharedStoreMetricsSource + SnapshotStore + ingest controller wired")
    void sharedStoreModeWiresSharedStoreSource() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues("failover.dashboard.enabled=true",
                        "failover.dashboard.cluster.mode=shared-store")
                .run(ctx -> {
                    assertThat(ctx.getBean(MetricsSource.class))
                            .isInstanceOf(com.societegenerale.failover.dashboard.metrics.source.sharedstore.SharedStoreMetricsSource.class);
                    assertThat(ctx).hasSingleBean(com.societegenerale.failover.dashboard.metrics.source.sharedstore.SnapshotStore.class);
                    assertThat(ctx).hasSingleBean(com.societegenerale.failover.dashboard.web.ClusterSnapshotController.class);
                    assertThat(ctx).hasSingleBean(com.societegenerale.failover.dashboard.metrics.source.sharedstore.ClusterSeriesStore.class);
                    assertThat(ctx).hasSingleBean(com.societegenerale.failover.dashboard.metrics.source.sharedstore.ClusterSeriesSampler.class);
                    assertThat(ctx.getBean(MetricsSource.class).info().mode()).isEqualTo("shared-store");
                });
    }

    @Test
    @DisplayName("no SnapshotStore / ingest controller when not in shared-store mode")
    void noSharedStoreBeansInLocalMode() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues("failover.dashboard.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(com.societegenerale.failover.dashboard.metrics.source.sharedstore.SnapshotStore.class);
                    assertThat(ctx).doesNotHaveBean(com.societegenerale.failover.dashboard.web.ClusterSnapshotController.class);
                });
    }

    @Test
    @DisplayName("standalone (no FailoverScanner) ⇒ EmptyFailoverScanner fallback, config view empty")
    void standaloneProvidesEmptyScanner() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration.class,
                        org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration.class,
                        DashboardAutoConfiguration.class))
                .withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues("failover.dashboard.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(FailoverScanner.class);
                    assertThat(ctx.getBean(FailoverScanner.class)).isInstanceOf(EmptyFailoverScanner.class);
                    assertThat(ctx.getBean(FailoverScanner.class).findAllFailover()).isEmpty();
                });
    }

    @Test
    @DisplayName("unknown cluster.mode ⇒ context still starts on the local source")
    void unknownClusterModeFallsBackToLocal() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues("failover.dashboard.enabled=true",
                        "failover.dashboard.cluster.mode=galactic")
                .run(ctx -> assertThat(ctx.getBean(MetricsSource.class))
                        .isInstanceOf(LocalRegistryMetricsSource.class));
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

    @Test
    @DisplayName("exposure.ui=false ⇒ no static resource handler and no welcome forward")
    void uiOffServesNoStatic() {
        DashboardProperties props = new DashboardProperties(true, "/failover-dashboard",
                new DashboardProperties.Exposure(false, true, java.util.List.of("config", "metrics", "health")),
                new DashboardProperties.Security("FAILOVER_ADMIN", false),
                new DashboardProperties.History(false, 120, 15),
                new DashboardProperties.Health(0.99, 0.90),
                new DashboardProperties.Cluster("local"));
        ResourceHandlerRegistry resources = Mockito.mock(ResourceHandlerRegistry.class);
        org.springframework.web.servlet.config.annotation.ViewControllerRegistry views =
                Mockito.mock(org.springframework.web.servlet.config.annotation.ViewControllerRegistry.class);

        DashboardAutoConfiguration cfg = new DashboardAutoConfiguration(props);
        cfg.addResourceHandlers(resources);
        cfg.addViewControllers(views);

        Mockito.verifyNoInteractions(resources, views);
    }

    // ── history (§8 option B, opt-in) ─────────────────────────────────────────

    @Test
    @DisplayName("history disabled by default ⇒ no sampler bean")
    void historyAbsentByDefault() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues("failover.dashboard.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DashboardHistoryService.class));
    }

    @Test
    @DisplayName("history.enabled=true + MeterRegistry ⇒ ring-buffer sampler registered")
    void historyPresentWhenEnabled() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues(
                        "failover.dashboard.enabled=true",
                        "failover.dashboard.history.enabled=true")
                .run(ctx -> assertThat(ctx).hasSingleBean(DashboardHistoryService.class));
    }

    @Test
    @DisplayName("history.enabled=true but no MeterRegistry ⇒ no history beans (needs metrics)")
    void historyAbsentWithoutRegistry() {
        runner.withPropertyValues(
                        "failover.dashboard.enabled=true",
                        "failover.dashboard.history.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(DashboardHistoryService.class));
    }

    // ── security gate (§9 gate 4) ─────────────────────────────────────────────

    @Test
    @DisplayName("Spring Security present ⇒ dashboard SecurityFilterChain registered")
    void securityChainRegisteredWhenSecurityPresent() {
        runner.withPropertyValues("failover.dashboard.enabled=true")
                .run(ctx -> assertThat(ctx).hasBean("dashboardSecurityFilterChain"));
    }

    @Test
    @DisplayName("Spring Security absent + allow-insecure=false ⇒ fail-closed (context fails to start)")
    void failClosedWhenSecurityAbsent() {
        runner.withClassLoader(new org.springframework.boot.test.context.FilteredClassLoader(
                        org.springframework.security.web.SecurityFilterChain.class))
                .withPropertyValues("failover.dashboard.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("Spring Security absent + allow-insecure=true ⇒ starts unsecured, no gate bean")
    void allowInsecureStartsWithoutGate() {
        runner.withClassLoader(new org.springframework.boot.test.context.FilteredClassLoader(
                        org.springframework.security.web.SecurityFilterChain.class))
                .withPropertyValues(
                        "failover.dashboard.enabled=true",
                        "failover.dashboard.security.allow-insecure=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean("dashboardSecurityFilterChain");
                });
    }

    @Test
    @DisplayName("Spring Security absent + allow-insecure=true + 'prod' profile ⇒ fail-closed (I-14)")
    void allowInsecureRefusedUnderProdProfile() {
        runner.withClassLoader(new org.springframework.boot.test.context.FilteredClassLoader(
                        org.springframework.security.web.SecurityFilterChain.class))
                .withPropertyValues(
                        "failover.dashboard.enabled=true",
                        "failover.dashboard.security.allow-insecure=true",
                        "spring.profiles.active=prod")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("Spring Security absent + allow-insecure=true + non-prod profile ⇒ starts unsecured")
    void allowInsecureAllowedOffProdProfile() {
        runner.withClassLoader(new org.springframework.boot.test.context.FilteredClassLoader(
                        org.springframework.security.web.SecurityFilterChain.class))
                .withPropertyValues(
                        "failover.dashboard.enabled=true",
                        "failover.dashboard.security.allow-insecure=true",
                        "spring.profiles.active=dev")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean("dashboardSecurityFilterChain");
                });
    }

    @Test
    @DisplayName("addViewControllers forwards bare base-path (and trailing slash) to index.html")
    void registersWelcomeForward() {
        DashboardProperties props = new DashboardProperties(true, "/failover-dashboard");
        org.springframework.web.servlet.config.annotation.ViewControllerRegistry registry =
                Mockito.mock(org.springframework.web.servlet.config.annotation.ViewControllerRegistry.class,
                        Mockito.RETURNS_DEEP_STUBS);

        new DashboardAutoConfiguration(props).addViewControllers(registry);

        Mockito.verify(registry).addViewController("/failover-dashboard");
        Mockito.verify(registry).addViewController("/failover-dashboard/");
    }
}
