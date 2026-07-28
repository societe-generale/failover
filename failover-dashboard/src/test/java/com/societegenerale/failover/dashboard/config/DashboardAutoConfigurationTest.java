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

import com.societegenerale.failover.core.observable.InstanceIdResolver;
import com.societegenerale.failover.dashboard.security.DashboardAuthenticationConfigurer;
import com.societegenerale.failover.dashboard.service.DashboardConfigService;
import com.societegenerale.failover.dashboard.service.DashboardMetricsService;
import com.societegenerale.failover.dashboard.service.DashboardHistoryService;
import com.societegenerale.failover.dashboard.web.ClusterHeartbeatController;
import com.societegenerale.failover.dashboard.web.DashboardController;
import com.societegenerale.failover.dashboard.web.DashboardMetricsController;
import com.societegenerale.failover.dashboard.metrics.source.MetricsSource;
import com.societegenerale.failover.dashboard.metrics.source.LocalRegistryMetricsSource;
import com.societegenerale.failover.dashboard.metrics.source.prometheus.PrometheusMetricsSource;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.HeartbeatStore;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.SnapshotStore;
import com.societegenerale.failover.dashboard.web.ClusterSnapshotController;
import com.societegenerale.failover.observable.metrics.FailoverConfigSnapshotService;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.Customizer;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Autoconfiguration tests for the P0 dashboard skeleton, including the secure-by-default contract
 * (design doc §1a / §13): with {@code failover.dashboard.enabled} unset, nothing is registered.
 *
 * <p>The shared {@code runner} wires a trivial {@code UserDetailsService} so the vast majority of tests
 * here — unrelated to the UI auth-backing check — aren't tripped up by {@code dashboardAuthBackingValidator}
 * (see "auth-backing validator" tests below, which deliberately use a bare runner without one).
 *
 * @author Anand Manissery
 */
class DashboardAutoConfigurationTest {

    private final WebApplicationContextRunner runner = new WebApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(
                    org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration.class,
                    org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration.class,
                    DashboardAutoConfiguration.class))
            .withBean(UserDetailsService.class, () -> new InMemoryUserDetailsManager(
                    User.withUsername("test").password("{noop}test").roles("FAILOVER_ADMIN").build()));

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

    // ── instance id resolver ──────────────────────────────────────────────────

    @Test
    @DisplayName("enabled=true ⇒ InstanceIdResolver bean registered (standalone fallback lambda)")
    void instanceIdResolverRegisteredByDefault() {
        runner.withPropertyValues("failover.dashboard.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(InstanceIdResolver.class);
                    assertThat(ctx.getBean(InstanceIdResolver.class).resolve()).isNotBlank();
                });
    }

    @Test
    @DisplayName("custom InstanceIdResolver bean ⇒ @ConditionalOnMissingBean — default not created")
    void customInstanceIdResolverHonouredViaMissingBean() {
        InstanceIdResolver custom = () -> "my-pod:10.0.0.1:8080";
        runner.withPropertyValues("failover.dashboard.enabled=true")
                .withBean(InstanceIdResolver.class, () -> custom)
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(InstanceIdResolver.class);
                    assertThat(ctx.getBean(InstanceIdResolver.class)).isSameAs(custom);
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
                    assertThat(ctx).hasSingleBean(SnapshotStore.class);
                    assertThat(ctx).hasSingleBean(ClusterSnapshotController.class);
                    assertThat(ctx).hasSingleBean(com.societegenerale.failover.dashboard.metrics.source.sharedstore.ClusterSeriesStore.class);
                    assertThat(ctx).hasSingleBean(com.societegenerale.failover.dashboard.metrics.source.sharedstore.ClusterSeriesSampler.class);
                    assertThat(ctx.getBean(MetricsSource.class).info().mode()).isEqualTo("shared-store");
                });
    }

    @Test
    @DisplayName("cluster.mode=shared-store + snapshot.ingest.enabled=false ⇒ no ClusterSnapshotController, SnapshotStore still wired")
    void sharedStoreModeWithIngestDisabledSkipsController() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues("failover.dashboard.enabled=true",
                        "failover.dashboard.cluster.mode=shared-store",
                        "failover.dashboard.cluster.snapshot.ingest.enabled=false")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(ClusterSnapshotController.class);
                    assertThat(ctx).hasSingleBean(SnapshotStore.class);
                    assertThat(ctx.getBean(MetricsSource.class).info().mode()).isEqualTo("shared-store");
                });
    }

    @Test
    @DisplayName("cluster.mode=shared-store + shared-store.store=jdbc (no SnapshotStore bean) ⇒ falls back to local")
    void sharedStoreModeWithoutSnapshotStoreBeanFallsBackToLocal() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues("failover.dashboard.enabled=true",
                        "failover.dashboard.cluster.mode=shared-store",
                        "failover.dashboard.cluster.shared-store.store=jdbc")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean(SnapshotStore.class);
                    assertThat(ctx.getBean(MetricsSource.class)).isInstanceOf(LocalRegistryMetricsSource.class);
                });
    }

    @Test
    @DisplayName("no SnapshotStore / ingest controller when not in shared-store mode")
    void noSharedStoreBeansInLocalMode() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues("failover.dashboard.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(SnapshotStore.class);
                    assertThat(ctx).doesNotHaveBean(ClusterSnapshotController.class);
                });
    }

    @Test
    @SuppressWarnings("java:S2699")
    @DisplayName("shared-store mode, liveness.enabled unset ⇒ off by default, no HeartbeatStore or ClusterHeartbeatController (ADR 66)")
    void heartbeatBeansAbsentByDefaultInSharedStoreMode() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues(
                        "failover.dashboard.enabled=true",
                        "failover.dashboard.cluster.mode=shared-store")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(HeartbeatStore.class);
                    assertThat(ctx).doesNotHaveBean(ClusterHeartbeatController.class);
                });
    }

    @Test
    @SuppressWarnings("java:S2699")
    @DisplayName("shared-store mode + liveness.enabled=true ⇒ HeartbeatStore + ClusterHeartbeatController wired")
    void heartbeatBeansWiredWhenLivenessExplicitlyEnabled() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues(
                        "failover.dashboard.enabled=true",
                        "failover.dashboard.cluster.mode=shared-store",
                        "failover.dashboard.cluster.shared-store.liveness.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(HeartbeatStore.class);
                    assertThat(ctx).hasSingleBean(ClusterHeartbeatController.class);
                });
    }

    @Test
    @SuppressWarnings("java:S2699")
    @DisplayName("local mode ⇒ no HeartbeatStore or ClusterHeartbeatController")
    void heartbeatBeansAbsentInLocalMode() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues("failover.dashboard.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean(HeartbeatStore.class);
                    assertThat(ctx).doesNotHaveBean(ClusterHeartbeatController.class);
                });
    }

    @Test
    @DisplayName("standalone (no failover.config.* gauges emitted) ⇒ FailoverConfigSnapshotService fallback, config view empty")
    void standaloneProvidesEmptyConfigSnapshotService() {
        new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration.class,
                        org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration.class,
                        DashboardAutoConfiguration.class))
                .withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withBean(UserDetailsService.class, () -> new InMemoryUserDetailsManager(
                        User.withUsername("test").password("{noop}test").roles("FAILOVER_ADMIN").build()))
                .withPropertyValues("failover.dashboard.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(FailoverConfigSnapshotService.class);
                    assertThat(ctx.getBean(FailoverConfigSnapshotService.class).configEntries()).isEmpty();
                    assertThat(ctx.getBean(DashboardConfigService.class).configEntries()).isEmpty();
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

    @org.junit.jupiter.params.ParameterizedTest
    @org.junit.jupiter.params.provider.ValueSource(ints = {0, -1, -100})
    @DisplayName("non-positive health.sample-size is rejected at construction")
    void nonPositiveSampleSizeRejected(int sampleSize) {
        org.assertj.core.api.Assertions
                .assertThatThrownBy(() -> new DashboardProperties.Health(0.99, 0.90, sampleSize))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("sample-size");
    }

    @Test
    @DisplayName("non-positive health.sample-size fails the context fast")
    void nonPositiveSampleSizeFailsContextFast() {
        runner.withPropertyValues(
                        "failover.dashboard.enabled=true",
                        "failover.dashboard.health.sample-size=0")
                .run(ctx -> assertThat(ctx).hasFailed());
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
    @DisplayName("addInterceptors registers the exposure interceptor for the configured base path")
    void registersExposureInterceptor() {
        DashboardProperties props = new DashboardProperties(true, "/failover-dashboard");
        org.springframework.web.servlet.config.annotation.InterceptorRegistry registry =
                Mockito.mock(org.springframework.web.servlet.config.annotation.InterceptorRegistry.class,
                        Mockito.RETURNS_DEEP_STUBS);

        new DashboardAutoConfiguration(props).addInterceptors(registry);

        Mockito.verify(registry).addInterceptor(
                Mockito.any(com.societegenerale.failover.dashboard.web.DashboardExposureInterceptor.class));
    }

    @Test
    @DisplayName("cluster.mode=shared-store + 'cluster' missing from exposure.include ⇒ warns but still starts")
    void sharedStoreWithoutClusterExposureWarnsButStarts() {
        runner.withPropertyValues("failover.dashboard.enabled=true",
                        "failover.dashboard.cluster.mode=shared-store",
                        "failover.dashboard.exposure.include=config,metrics,health")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx.getBean(DashboardProperties.class).exposure().includes("cluster")).isFalse();
                });
    }

    @Test
    @DisplayName("exposure.ui=false ⇒ no static resource handler and no welcome forward")
    void uiOffServesNoStatic() {
        DashboardProperties props = new DashboardProperties(true, "/failover-dashboard",
                new DashboardProperties.Exposure(false, true, java.util.List.of("config", "metrics", "health")),
                new DashboardProperties.Security(DashboardProperties.SecurityType.AUTHORITY, "FAILOVER_ADMIN","FAILOVER_ADMIN", null, false, "", false),
                new DashboardProperties.History(false, 120, 15),
                new DashboardProperties.Health(0.99, 0.90, 100),
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
    @DisplayName("shared-store mode + username ⇒ Basic Auth ingest filter chain registered")
    void basicIngestChainRegisteredWhenUsernameSet() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues("failover.dashboard.enabled=true",
                        "failover.dashboard.cluster.mode=shared-store",
                        "failover.dashboard.cluster.snapshot.username=peer",
                        "failover.dashboard.cluster.snapshot.password=secret")
                .run(ctx -> {
                    assertThat(ctx).hasBean("dashboardIngestBasicFilterChain");
                    assertThat(ctx).doesNotHaveBean("dashboardIngestOpenFilterChain");
                });
    }

    @Test
    @DisplayName("shared-store mode + pre-encoded ingest password ({bcrypt}…) ⇒ used verbatim, not double-wrapped in {noop}")
    void basicIngestChainAcceptsPreEncodedPassword() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues("failover.dashboard.enabled=true",
                        "failover.dashboard.cluster.mode=shared-store",
                        "failover.dashboard.cluster.snapshot.username=peer",
                        "failover.dashboard.cluster.snapshot.password={noop}secret")
                .run(ctx -> assertThat(ctx).hasBean("dashboardIngestBasicFilterChain"));
    }

    @Test
    @DisplayName("shared-store mode + allow-insecure-ingest=true ⇒ open (permit-all) ingest chain registered")
    void openIngestChainRegisteredWhenAllowInsecureIngest() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues("failover.dashboard.enabled=true",
                        "failover.dashboard.cluster.mode=shared-store",
                        "failover.dashboard.cluster.snapshot.allow-insecure-ingest=true")
                .run(ctx -> {
                    assertThat(ctx).hasBean("dashboardIngestOpenFilterChain");
                    assertThat(ctx).doesNotHaveBean("dashboardIngestBasicFilterChain");
                });
    }

    @Test
    @DisplayName("allow-insecure-ingest=true + username set ⇒ Basic chain wins, open chain suppressed")
    void basicChainWinsOverOpenChain() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues("failover.dashboard.enabled=true",
                        "failover.dashboard.cluster.mode=shared-store",
                        "failover.dashboard.cluster.snapshot.username=peer",
                        "failover.dashboard.cluster.snapshot.password=secret",
                        "failover.dashboard.cluster.snapshot.allow-insecure-ingest=true")
                .run(ctx -> {
                    assertThat(ctx).hasBean("dashboardIngestBasicFilterChain");
                    assertThat(ctx).doesNotHaveBean("dashboardIngestOpenFilterChain");
                    assertThat(ctx).doesNotHaveBean("dashboardIngestOAuth2FilterChain");
                });
    }

    @Test
    @SuppressWarnings("java:S2699")
    @DisplayName("no ingest auth configured ⇒ no ingest security chains (ingest falls through to main gate)")
    void noIngestChainsWhenNoAuthConfigured() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues("failover.dashboard.enabled=true",
                        "failover.dashboard.cluster.mode=shared-store")
                .run(ctx -> {
                    assertThat(ctx).doesNotHaveBean("dashboardIngestBasicFilterChain");
                    assertThat(ctx).doesNotHaveBean("dashboardIngestOpenFilterChain");
                    assertThat(ctx).doesNotHaveBean("dashboardIngestOAuth2FilterChain");
                });
    }

    @Test
    @DisplayName("shared-store mode + snapshot.oauth2-client-registration-id ⇒ OAuth2 ingest filter chain registered, matches both ingest paths")
    void oauth2IngestChainRegisteredWhenRegistrationIdSet() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withBean(org.springframework.security.oauth2.jwt.JwtDecoder.class,
                        () -> Mockito.mock(org.springframework.security.oauth2.jwt.JwtDecoder.class))
                .withPropertyValues("failover.dashboard.enabled=true",
                        "failover.dashboard.cluster.mode=shared-store",
                        "failover.dashboard.cluster.snapshot.oauth2-client-registration-id=idp")
                .run(ctx -> {
                    assertThat(ctx).hasBean("dashboardIngestOAuth2FilterChain");
                    assertThat(ctx).doesNotHaveBean("dashboardIngestBasicFilterChain");
                    assertThat(ctx).doesNotHaveBean("dashboardIngestOpenFilterChain");

                    org.springframework.security.web.SecurityFilterChain chain = ctx.getBean(
                            "dashboardIngestOAuth2FilterChain", org.springframework.security.web.SecurityFilterChain.class);
                    assertThat(chain.matches(new org.springframework.mock.web.MockHttpServletRequest(
                            "POST", "/failover-dashboard/api/cluster/snapshot"))).isTrue();
                    assertThat(chain.matches(new org.springframework.mock.web.MockHttpServletRequest(
                            "POST", "/failover-dashboard/api/cluster/heartbeat"))).isTrue();
                    assertThat(chain.matches(new org.springframework.mock.web.MockHttpServletRequest(
                            "GET", "/failover-dashboard/api/config"))).isFalse();
                });
    }

    @Test
    @DisplayName("Basic ingest chain matches BOTH the snapshot and heartbeat paths (not just snapshot)")
    void basicIngestChainMatchesBothIngestPaths() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues("failover.dashboard.enabled=true",
                        "failover.dashboard.cluster.mode=shared-store",
                        "failover.dashboard.cluster.snapshot.username=peer",
                        "failover.dashboard.cluster.snapshot.password=secret")
                .run(ctx -> {
                    org.springframework.security.web.SecurityFilterChain chain =
                            ctx.getBean("dashboardIngestBasicFilterChain", org.springframework.security.web.SecurityFilterChain.class);
                    assertThat(chain.matches(new org.springframework.mock.web.MockHttpServletRequest(
                            "POST", "/failover-dashboard/api/cluster/snapshot"))).isTrue();
                    assertThat(chain.matches(new org.springframework.mock.web.MockHttpServletRequest(
                            "POST", "/failover-dashboard/api/cluster/heartbeat"))).isTrue();
                    assertThat(chain.matches(new org.springframework.mock.web.MockHttpServletRequest(
                            "GET", "/failover-dashboard/api/config"))).isFalse();
                });
    }

    @Test
    @DisplayName("Spring Security present + allow-insecure=true + 'prod' profile ⇒ fail-closed (I-14)")
    void allowInsecureRefusedUnderProdProfileWhenSecurityPresent() {
        runner.withPropertyValues(
                        "failover.dashboard.enabled=true",
                        "failover.dashboard.security.allow-insecure=true",
                        "spring.profiles.active=prod")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("Spring Security present + allow-insecure=true + non-prod profile ⇒ starts, main gate permits all")
    void allowInsecureAllowedOffProdProfileWhenSecurityPresent() {
        runner.withPropertyValues(
                        "failover.dashboard.enabled=true",
                        "failover.dashboard.security.allow-insecure=true",
                        "spring.profiles.active=dev")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasBean("dashboardSecurityFilterChain");
                });
    }

    @Test
    @DisplayName("Spring Security present + allow-insecure-ingest=true + 'prod' profile ⇒ fail-closed (I-14)")
    void allowInsecureIngestRefusedUnderProdProfile() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues(
                        "failover.dashboard.enabled=true",
                        "failover.dashboard.cluster.mode=shared-store",
                        "failover.dashboard.cluster.snapshot.allow-insecure-ingest=true",
                        "spring.profiles.active=prod")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("Spring Security present + allow-insecure-ingest=true + non-prod profile ⇒ starts, open chain registered")
    void allowInsecureIngestAllowedOffProdProfile() {
        runner.withBean(io.micrometer.core.instrument.MeterRegistry.class,
                        io.micrometer.core.instrument.simple.SimpleMeterRegistry::new)
                .withPropertyValues(
                        "failover.dashboard.enabled=true",
                        "failover.dashboard.cluster.mode=shared-store",
                        "failover.dashboard.cluster.snapshot.allow-insecure-ingest=true",
                        "spring.profiles.active=dev")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasBean("dashboardIngestOpenFilterChain");
                });
    }

    @Test
    @DisplayName("addViewControllers redirects bare base-path to trailing slash, forwards trailing slash to index.html")
    void registersWelcomeForward() {
        DashboardProperties props = new DashboardProperties(true, "/failover-dashboard");
        org.springframework.web.servlet.config.annotation.ViewControllerRegistry registry =
                Mockito.mock(org.springframework.web.servlet.config.annotation.ViewControllerRegistry.class,
                        Mockito.RETURNS_DEEP_STUBS);

        new DashboardAutoConfiguration(props).addViewControllers(registry);

        Mockito.verify(registry).addRedirectViewController("/failover-dashboard", "/failover-dashboard/");
        Mockito.verify(registry).addViewController("/failover-dashboard/");
    }

    // --- dashboardAuthBackingValidator (fix-me-2.md Issue 1) ---
    // Deliberately uses a bare runner (no shared UserDetailsService bean) to control backing-auth precisely.

    private WebApplicationContextRunner bareSecurityRunner() {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(
                        org.springframework.boot.security.autoconfigure.SecurityAutoConfiguration.class,
                        org.springframework.boot.security.autoconfigure.web.servlet.ServletWebSecurityAutoConfiguration.class,
                        DashboardAutoConfiguration.class));
    }

    @Test
    @DisplayName("type=AUTHORITY, no UserDetailsService/AuthenticationProvider, allow-insecure=false ⇒ fail-closed")
    void authBackingValidatorFailsClosedWithNoBackingAuth() {
        bareSecurityRunner()
                .withPropertyValues("failover.dashboard.enabled=true")
                .run(ctx -> assertThat(ctx).hasFailed());
    }

    @Test
    @DisplayName("type=AUTHORITY + a UserDetailsService bean present ⇒ starts clean")
    void authBackingValidatorPassesWithUserDetailsService() {
        bareSecurityRunner()
                .withBean(UserDetailsService.class, () -> new InMemoryUserDetailsManager(
                        User.withUsername("test").password("{noop}test").roles("FAILOVER_ADMIN").build()))
                .withPropertyValues("failover.dashboard.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasBean("dashboardAuthBackingValidator");
                });
    }

    @Test
    @DisplayName("type=AUTHORITY + an AuthenticationProvider bean present ⇒ starts clean")
    void authBackingValidatorPassesWithAuthenticationProvider() {
        bareSecurityRunner()
                .withBean(org.springframework.security.authentication.AuthenticationProvider.class,
                        () -> Mockito.mock(org.springframework.security.authentication.AuthenticationProvider.class))
                .withPropertyValues("failover.dashboard.enabled=true")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("no backing auth + allow-insecure=true ⇒ validator doesn't fire (permitAll needs no authentication)")
    void authBackingValidatorSkippedWhenAllowInsecure() {
        bareSecurityRunner()
                .withPropertyValues("failover.dashboard.enabled=true",
                        "failover.dashboard.security.allow-insecure=true")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("no backing auth + type=EXPRESSION ⇒ starts clean (warns, doesn't fail — expression may not need auth)")
    void authBackingValidatorWarnsNotFailsForExpressionType() {
        bareSecurityRunner()
                .withPropertyValues("failover.dashboard.enabled=true",
                        "failover.dashboard.security.type=EXPRESSION",
                        "failover.dashboard.security.expression=permitAll")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("type=EXPRESSION + a UserDetailsService bean present ⇒ starts clean, no warn needed")
    void authBackingValidatorSkipsWarnForExpressionTypeWithBackingAuth() {
        bareSecurityRunner()
                .withBean(UserDetailsService.class, () -> new InMemoryUserDetailsManager(
                        User.withUsername("test").password("{noop}test").roles("FAILOVER_ADMIN").build()))
                .withPropertyValues("failover.dashboard.enabled=true",
                        "failover.dashboard.security.type=EXPRESSION",
                        "failover.dashboard.security.expression=permitAll")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("no backing auth + consumer overrides dashboardSecurityFilterChain ⇒ validator not required")
    void authBackingValidatorSkippedWhenChainOverridden() {
        bareSecurityRunner()
                .withUserConfiguration(CustomSecurityFilterChainConfig.class)
                .withPropertyValues("failover.dashboard.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).doesNotHaveBean("dashboardAuthBackingValidator");
                });
    }

    @Test
    @DisplayName("no backing auth + security.oauth2-client-registration-id set ⇒ validator doesn't require one (OAuth2 login authenticates instead)")
    void authBackingValidatorSkippedWhenOAuth2LoginConfigured() {
        org.springframework.security.oauth2.client.registration.ClientRegistration registration =
                org.springframework.security.oauth2.client.registration.ClientRegistration.withRegistrationId("github")
                        .clientId("test-client")
                        .clientSecret("test-secret")
                        .authorizationGrantType(org.springframework.security.oauth2.core.AuthorizationGrantType.AUTHORIZATION_CODE)
                        .redirectUri("https://example.com/login/oauth2/code/github")
                        .authorizationUri("https://github.com/login/oauth/authorize")
                        .tokenUri("https://github.com/login/oauth/access_token")
                        .build();

        bareSecurityRunner()
                .withBean(org.springframework.security.oauth2.client.registration.ClientRegistrationRepository.class,
                        () -> new org.springframework.security.oauth2.client.registration.InMemoryClientRegistrationRepository(registration))
                .withPropertyValues("failover.dashboard.enabled=true",
                        "failover.dashboard.security.oauth2-client-registration-id=github")
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasBean("dashboardOAuth2SecurityFilterChain");
                    assertThat(ctx).doesNotHaveBean("dashboardSecurityFilterChain");
                });
    }

    // --- DashboardAuthenticationConfigurer seam ---

    @Test
    @DisplayName("DashboardAuthenticationConfigurer bean present ⇒ custom auth chain registered, built-ins back off")
    void customAuthenticationConfigurerTakesPriority() {
        runner.withUserConfiguration(CustomAuthenticationConfigurerConfig.class)
                .withPropertyValues("failover.dashboard.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasBean("dashboardCustomAuthFilterChain");
                    assertThat(ctx).doesNotHaveBean("dashboardSecurityFilterChain");
                    assertThat(ctx).doesNotHaveBean("dashboardOAuth2SecurityFilterChain");
                    assertThat(ctx).doesNotHaveBean("dashboardOAuth2ResourceServerFilterChain");
                });
    }

    @Test
    @DisplayName("DashboardAuthenticationConfigurer bean present ⇒ auth-backing validator not required")
    void authBackingValidatorSkippedWhenCustomConfigurerPresent() {
        bareSecurityRunner()
                .withUserConfiguration(CustomAuthenticationConfigurerConfig.class)
                .withPropertyValues("failover.dashboard.enabled=true")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("security.oauth2-resource-server=true ⇒ OAuth2 resource-server filter chain registered")
    void oauth2ResourceServerChainRegistered() {
        runner.withBean(JwtDecoder.class, () -> Mockito.mock(JwtDecoder.class))
                .withPropertyValues("failover.dashboard.enabled=true",
                        "failover.dashboard.security.oauth2-resource-server=true")
                .run(ctx -> {
                    assertThat(ctx).hasBean("dashboardOAuth2ResourceServerFilterChain");
                    assertThat(ctx).doesNotHaveBean("dashboardSecurityFilterChain");
                });
    }

    @Test
    @DisplayName("no backing auth + security.oauth2-resource-server=true ⇒ validator doesn't require one (resource server authenticates instead)")
    void authBackingValidatorSkippedWhenResourceServerConfigured() {
        bareSecurityRunner()
                .withBean(JwtDecoder.class, () -> Mockito.mock(JwtDecoder.class))
                .withPropertyValues("failover.dashboard.enabled=true",
                        "failover.dashboard.security.oauth2-resource-server=true")
                .run(ctx -> assertThat(ctx).hasNotFailed());
    }

    @Test
    @DisplayName("security.oauth2-resource-server=true takes lower priority than a custom DashboardAuthenticationConfigurer")
    void customConfigurerTakesPriorityOverResourceServer() {
        runner.withUserConfiguration(CustomAuthenticationConfigurerConfig.class)
                .withBean(JwtDecoder.class, () -> Mockito.mock(JwtDecoder.class))
                .withPropertyValues("failover.dashboard.enabled=true",
                        "failover.dashboard.security.oauth2-resource-server=true")
                .run(ctx -> {
                    assertThat(ctx).hasBean("dashboardCustomAuthFilterChain");
                    assertThat(ctx).doesNotHaveBean("dashboardOAuth2ResourceServerFilterChain");
                });
    }

    @Configuration
    static class CustomAuthenticationConfigurerConfig {
        @Bean
        DashboardAuthenticationConfigurer dashboardAuthenticationConfigurer() {
            return (http, context) -> http.httpBasic(Customizer.withDefaults());
        }
    }

    @org.springframework.context.annotation.Configuration
    static class CustomSecurityFilterChainConfig {
        @org.springframework.context.annotation.Bean(name = "dashboardSecurityFilterChain")
        org.springframework.security.web.SecurityFilterChain customChain(
                org.springframework.security.config.annotation.web.builders.HttpSecurity http) {
            http.securityMatcher("/failover-dashboard/**")
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
            return http.build();
        }
    }
}