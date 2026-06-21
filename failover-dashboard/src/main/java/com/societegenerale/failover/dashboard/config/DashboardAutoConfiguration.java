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
import com.societegenerale.failover.dashboard.web.DashboardExposureInterceptor;
import com.societegenerale.failover.dashboard.web.ClusterSnapshotController;
import com.societegenerale.failover.dashboard.metrics.source.MetricsSource;
import com.societegenerale.failover.dashboard.metrics.source.LocalRegistryMetricsSource;
import com.societegenerale.failover.dashboard.metrics.source.prometheus.PrometheusClient;
import com.societegenerale.failover.dashboard.metrics.source.prometheus.PrometheusMetricsSource;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.ClusterSeriesSampler;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.ClusterSeriesStore;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.ClusterSnapshotPublisher;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.SnapshotStoreInmemory;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.RetentionPolicy;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.SharedStoreMetricsSource;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.SnapshotStore;

import com.societegenerale.failover.core.scanner.FailoverScanner;
import java.net.InetAddress;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET;

/**
 * Auto-configuration for the embedded failover dashboard.
 *
 * <p><strong>Secure-by-default (design doc §1a):</strong> an absent {@code failover.dashboard.enabled}
 * property means <em>off</em> — no {@code matchIfMissing}. The consumer must explicitly opt in via YAML.
 * With the property unset, no bean, controller, resource handler, JSON, or UI is mapped at all.
 *
 * <p>All dashboard surfaces share the single {@link DashboardProperties#basePath()} namespace: the
 * static UI is served from {@code basePath/**} and the JSON API is mapped under {@code basePath/api}.
 * The validated base path is used verbatim by both, so they cannot collide with consumer services or
 * drift apart.
 *
 * <p>P0/P1 scope: master switch, properties, static-asset resource handler, and the config API/view.
 * Metrics services and UI arrive in P2–P3.
 *
 * @author Anand Manissery
 */
@Slf4j
@AutoConfiguration(
        afterName = {
                "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
                "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
                "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration",
                // After the failover library (when present) so its real FailoverScanner wins over the
                // standalone EmptyFailoverScanner fallback below. Referenced by name — no compile dependency.
                "com.societegenerale.failover.configuration.FailoverAutoConfiguration"
        })
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

    /**
     * Standalone fallback: when the failover library is absent (the dashboard runs as its own app pointed at a
     * remote backend), supply an {@link EmptyFailoverScanner} so the config view is empty rather than failing to
     * start. The real scanner wins whenever the failover library is present (this autoconfig is ordered after it).
     */
    @Bean
    @ConditionalOnMissingBean(FailoverScanner.class)
    public FailoverScanner failoverScanner() {
        log.info("No FailoverScanner found — running the dashboard standalone (config view will be empty).");
        return new EmptyFailoverScanner();
    }

    @Bean
    @ConditionalOnMissingBean
    public DashboardConfigService dashboardConfigService(FailoverScanner scanner, Environment environment) {
        return new DashboardConfigService(scanner, environment);
    }

    @Bean
    @ConditionalOnMissingBean
    public DashboardController dashboardController(DashboardConfigService configService) {
        return new DashboardController(configService);
    }

    /**
     * Metrics service + controller — present only when a {@link MeterRegistry} is in the context.
     * Without Micrometer the config view above still works; the metrics view degrades gracefully (§3).
     */
    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean
    public DashboardMetricsService dashboardMetricsService(MeterRegistry registry) {
        return new DashboardMetricsService(registry, properties);
    }

    /**
     * The metrics provenance seam (see the distributed-dashboard design). Defaults to the local registry
     * (this instance only); {@code cluster.mode=prometheus} with a {@code base-url} aggregates the
     * {@code failover.*} meters cluster-wide via the Prometheus HTTP API, falling back to local at runtime
     * if Prometheus is unreachable. {@code @ConditionalOnMissingBean} so a consumer can fully override it.
     */
    @Bean
    @ConditionalOnBean(DashboardMetricsService.class)
    @ConditionalOnMissingBean
    public MetricsSource metricsSource(DashboardMetricsService metricsService,
                                       org.springframework.beans.factory.ObjectProvider<DashboardHistoryService> history,
                                       org.springframework.beans.factory.ObjectProvider<SnapshotStore> snapshotStore,
                                       org.springframework.beans.factory.ObjectProvider<ClusterSeriesStore> seriesStore) {
        // history is present only when failover.dashboard.history.enabled=true; null otherwise.
        LocalRegistryMetricsSource local = new LocalRegistryMetricsSource(metricsService, history.getIfAvailable());
        DashboardProperties.Cluster cluster = properties.cluster();
        String mode = cluster.mode();
        if ("prometheus".equalsIgnoreCase(mode)) {
            String baseUrl = cluster.prometheus().baseUrl();
            if (baseUrl != null && !baseUrl.isBlank()) {
                log.info("Failover dashboard metrics aggregated cluster-wide via Prometheus at '{}'.", baseUrl);
                return new PrometheusMetricsSource(
                        PrometheusClient.create(cluster.prometheus()), local, properties.health());
            }
            log.warn("failover.dashboard.cluster.mode=prometheus but prometheus.base-url is blank; "
                    + "using 'local' (this instance only).");
        } else if ("shared-store".equalsIgnoreCase(mode)) {
            SnapshotStore store = snapshotStore.getIfAvailable();
            if (store != null) {
                log.info("Failover dashboard metrics aggregated cluster-wide via in-memory shared-store "
                        + "(max-instances={}).", cluster.sharedStore().maxInstances());
                return new SharedStoreMetricsSource(store, properties.health(), local,
                        cluster.sharedStore().maxInstances(), seriesStore.getIfAvailable());
            }
            log.warn("failover.dashboard.cluster.mode=shared-store but no SnapshotStore bean is present; "
                    + "using 'local' (this instance only).");
        } else if (!"local".equalsIgnoreCase(mode)) {
            log.warn("failover.dashboard.cluster.mode='{}' is not available yet; using 'local' "
                    + "(this instance only). See the distributed-dashboard design.", mode);
        }
        return local;
    }

    /**
     * In-memory snapshot store for {@code cluster.mode=shared-store} (design §5) — the default {@code store=inmemory}.
     * {@code store=jdbc} instead activates the durable {@code failover-dashboard-snapshotstore-jdbc} module.
     * {@code @ConditionalOnMissingBean} so a consumer can supply a distributed implementation.
     */
    @Bean
    @ConditionalOnExpression("'${failover.dashboard.cluster.mode:local}' == 'shared-store' "
            + "and '${failover.dashboard.cluster.shared-store.store:inmemory}' == 'inmemory'")
    @ConditionalOnMissingBean
    public SnapshotStore snapshotStore() {
        DashboardProperties.SharedStore sharedStore = properties.cluster().sharedStore();
        return new SnapshotStoreInmemory(sharedStore.livenessSeconds() * 1000L, sharedStore.maxInstances());
    }

    /** Ingest controller for peer snapshot pushes; present only in shared-store mode. */
    @Bean
    @ConditionalOnProperty(prefix = "failover.dashboard.cluster", name = "mode", havingValue = "shared-store")
    @ConditionalOnBean(SnapshotStore.class)
    @ConditionalOnMissingBean
    public ClusterSnapshotController clusterSnapshotController(SnapshotStore snapshotStore) {
        return new ClusterSnapshotController(snapshotStore);
    }

    /** Bounded, retention-pruned ring holding the cluster-wide trend (design §5.4); shared-store mode only. */
    @Bean
    @ConditionalOnProperty(prefix = "failover.dashboard.cluster", name = "mode", havingValue = "shared-store")
    @ConditionalOnMissingBean
    public ClusterSeriesStore clusterSeriesStore() {
        DashboardProperties.Retention retention = properties.cluster().sharedStore().retention();
        return new ClusterSeriesStore(new RetentionPolicy(retention.maxAge(), retention.maxEntries()));
    }

    /**
     * Reset-aware sampler that feeds the cluster series ring from the merged aggregate; shared-store mode only.
     * Closed on shutdown to stop its scheduler. Depends on the assembled {@link MetricsSource} (the shared source).
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "failover.dashboard.cluster", name = "mode", havingValue = "shared-store")
    @ConditionalOnBean({MetricsSource.class, ClusterSeriesStore.class})
    @ConditionalOnMissingBean
    public ClusterSeriesSampler clusterSeriesSampler(MetricsSource metricsSource, ClusterSeriesStore seriesStore) {
        return new ClusterSeriesSampler(metricsSource, seriesStore,
                properties.cluster().sharedStore().sampleIntervalSeconds());
    }

    /**
     * Peer-side snapshot publisher — active on any instance that sets {@code cluster.snapshot.publish-url},
     * regardless of its own read mode. Closed on shutdown to stop the scheduler.
     */
    @Bean(destroyMethod = "close")
    @ConditionalOnProperty(prefix = "failover.dashboard.cluster.snapshot", name = "publish-url")
    @ConditionalOnBean(DashboardMetricsService.class)
    @ConditionalOnMissingBean
    public ClusterSnapshotPublisher clusterSnapshotPublisher(DashboardMetricsService metricsService, Environment environment) {
        DashboardProperties.Snapshot snapshot = properties.cluster().snapshot();
        return new ClusterSnapshotPublisher(metricsService, resolveInstanceId(environment),
                snapshot.publishUrl(), snapshot.intervalSeconds());
    }

    /** Resolves this instance's id: {@code spring.application.name:hostname} (host falls back to {@code unknown-host}). */
    private static String resolveInstanceId(Environment environment) {
        String app = environment.getProperty("spring.application.name", "application");
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        return app + ":" + host;
    }

    @Bean
    @ConditionalOnBean(MetricsSource.class)
    @ConditionalOnMissingBean
    public DashboardMetricsController dashboardMetricsController(MetricsSource metricsSource) {
        return new DashboardMetricsController(metricsSource);
    }

    /** Enforces {@code exposure.include} narrowing and the CSP header on every {@code base-path/**} request. */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(new DashboardExposureInterceptor(properties))
                .addPathPatterns(properties.basePath() + "/**");
    }

    @Override
    public void addResourceHandlers(@NonNull ResourceHandlerRegistry registry) {
        if (!properties.exposure().ui()) {
            return; // UI exposure narrowed off — serve no static assets
        }
        registry.addResourceHandler(properties.basePath() + "/**")
                .addResourceLocations("classpath:/failover-dashboard/");
    }

    /**
     * Welcome mapping: a bare {@code base-path} (and its trailing-slash form) forwards to the static
     * {@code index.html}, so {@code /failover-dashboard} opens the UI without the explicit file name.
     * A forward (not redirect) keeps the URL clean and lets the resource handler serve the page.
     * Skipped when UI exposure is narrowed off.
     */
    @Override
    public void addViewControllers(@NonNull ViewControllerRegistry registry) {
        if (!properties.exposure().ui()) {
            return;
        }
        String forward = "forward:" + properties.basePath() + "/index.html";
        registry.addViewController(properties.basePath()).setViewName(forward);
        registry.addViewController(properties.basePath() + "/").setViewName(forward);
    }

    /**
     * Opt-in trend history (design doc §8 option B): a scheduled ring-buffer sampler, active only when
     * {@code history.enabled=true} and a {@link MeterRegistry} is present. The {@code /api/metrics/series}
     * endpoint itself lives on {@link DashboardMetricsController} and serves through the {@link MetricsSource}
     * — so it also works in {@code cluster.mode=prometheus} (via {@code query_range}) without this sampler.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnProperty(prefix = "failover.dashboard.history", name = "enabled", havingValue = "true")
    @EnableScheduling
    static class HistoryConfiguration {

        @Bean
        @ConditionalOnMissingBean
        DashboardHistoryService dashboardHistoryService(DashboardMetricsService metricsService,
                                                        DashboardProperties props) {
            return new DashboardHistoryService(metricsService, props.history().samples());
        }
    }

    /**
     * Access-control gate when Spring Security IS on the classpath (design doc §9 gate 4): a
     * {@link SecurityFilterChain} scoped to {@code base-path/**} that requires the configured role.
     * It is {@code @ConditionalOnMissingBean} so a consumer can fully override it.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SecurityFilterChain.class)
    static class SecurityPresentConfiguration {

        @Bean
        @ConditionalOnMissingBean(name = "dashboardSecurityFilterChain")
        SecurityFilterChain dashboardSecurityFilterChain(HttpSecurity http, DashboardProperties props) {
            http.securityMatcher(props.basePath() + "/**")
                    .authorizeHttpRequests(auth -> auth.anyRequest().hasRole(props.security().role()))
                    .httpBasic(Customizer.withDefaults());
            log.info("Failover dashboard secured: '{}/**' requires role '{}'.",
                    props.basePath(), props.security().role());
            return http.build();
        }
    }

    /**
     * Fail-closed guard when Spring Security is ABSENT: the dashboard refuses to start (so internal
     * operational data is never served anonymously) unless {@code allow-insecure=true}, which starts
     * with a loud WARN for trusted-network / dev use only (design doc §9 gate 4).
     *
     * <p>The {@code allow-insecure} escape hatch is rejected outright when the {@code prod} profile is
     * active: it exists for dev / trusted-network use, and silently disabling the access gate in
     * production must never be possible (I-14). Production must add Spring Security.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnMissingClass("org.springframework.security.web.SecurityFilterChain")
    static class SecurityAbsentConfiguration {

        SecurityAbsentConfiguration(DashboardProperties props, Environment environment) {
            if (!props.security().allowInsecure()) {
                throw new IllegalStateException(
                        "Failover dashboard is enabled but Spring Security is not on the classpath, so '"
                                + props.basePath() + "/**' would be anonymously reachable. Add "
                                + "spring-boot-starter-security (recommended) and restrict the path to role '"
                                + props.security().role() + "', or set failover.dashboard.security.allow-insecure=true "
                                + "to run UNSECURED (dev / trusted-network only).");
            }
            if (environment.acceptsProfiles(Profiles.of("prod"))) {
                throw new IllegalStateException(
                        "Failover dashboard has failover.dashboard.security.allow-insecure=true while the 'prod' "
                                + "profile is active. The insecure escape hatch is for dev / trusted-network use only "
                                + "and must never disable the access gate in production. Add spring-boot-starter-security "
                                + "and restrict '" + props.basePath() + "/**' to role '" + props.security().role()
                                + "', or remove allow-insecure / the 'prod' profile.");
            }
            log.warn("===================================================================================");
            log.warn("Failover dashboard is running WITHOUT an access-control gate (allow-insecure=true).");
            log.warn("'{}/**' is anonymously reachable — use only on a trusted network or in development.",
                    props.basePath());
            log.warn("===================================================================================");
        }
    }
}
