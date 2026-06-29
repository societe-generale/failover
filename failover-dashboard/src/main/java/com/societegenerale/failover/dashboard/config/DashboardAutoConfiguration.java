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
import com.societegenerale.failover.dashboard.web.ClusterHeartbeatController;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.HeartbeatStore;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.HeartbeatStoreInmemory;
import com.societegenerale.failover.dashboard.metrics.source.MetricsSource;
import com.societegenerale.failover.dashboard.metrics.source.LocalRegistryMetricsSource;
import com.societegenerale.failover.dashboard.metrics.source.prometheus.PrometheusClient;
import com.societegenerale.failover.dashboard.metrics.source.prometheus.PrometheusMetricsSource;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.ClusterSeriesSampler;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.ClusterSeriesStore;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.SnapshotStoreInmemory;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.RetentionPolicy;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.SharedStoreMetricsSource;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.SnapshotStore;

import com.societegenerale.failover.core.observable.InstanceIdResolver;
import com.societegenerale.failover.observable.metrics.DefaultInstanceIdResolver;
import com.societegenerale.failover.core.scanner.FailoverScanner;
import com.societegenerale.failover.observable.metrics.FailoverMetricsSnapshotService;
import io.micrometer.core.instrument.MeterRegistry;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.ObjectProvider;
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
import org.springframework.core.annotation.Order;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import static org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication.Type.SERVLET;

/**
 * Autoconfiguration for the embedded failover dashboard.
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
    public DashboardConfigService dashboardConfigService(FailoverScanner scanner, Environment environment,
                                                         ObjectProvider<MetricsSource> metricsSource) {
        return new DashboardConfigService(scanner, environment, metricsSource.getIfAvailable());
    }

    @Bean
    @ConditionalOnMissingBean
    public DashboardController dashboardController(DashboardConfigService configService) {
        return new DashboardController(configService);
    }

    /**
     * Fallback {@link InstanceIdResolver} for standalone-dashboard deployments where
     * {@code failover-spring-boot-autoconfigure} is not on the classpath. When the full failover
     * starter is present, {@code FailoverMicrometerAutoConfiguration} supplies a
     * {@code DefaultInstanceIdResolver} first and this bean is skipped.
     */
    @Bean
    @ConditionalOnMissingBean
    public InstanceIdResolver instanceIdResolver(Environment environment) {
        String app = environment.getProperty("spring.application.name", "application");
        String host = DefaultInstanceIdResolver.resolveHostname();
        return new DefaultInstanceIdResolver(app, host,
                () -> environment.getProperty("local.server.port",
                        environment.getProperty("server.port", "8080")));
    }

    /**
     * Standalone fallback {@link FailoverMetricsSnapshotService} for dashboard-only deployments where
     * {@code failover-spring-boot-autoconfigure} is not on the classpath. When the full failover starter
     * is present, {@code FailoverMicrometerAutoConfiguration} contributes the real service first and this
     * fallback is skipped.
     */
    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean
    public FailoverMetricsSnapshotService failoverMetricsSnapshotService(MeterRegistry registry) {
        return new FailoverMetricsSnapshotService(registry);
    }

    /**
     * Metrics service + controller — present only when a {@link MeterRegistry} is in the context.
     * Without Micrometer the config view above still works; the metrics view degrades gracefully (§3).
     */
    @Bean
    @ConditionalOnBean({MeterRegistry.class, FailoverMetricsSnapshotService.class})
    @ConditionalOnMissingBean
    public DashboardMetricsService dashboardMetricsService(FailoverMetricsSnapshotService snapshotService) {
        return new DashboardMetricsService(snapshotService, properties);
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
                                       ObjectProvider<DashboardHistoryService> history,
                                       ObjectProvider<SnapshotStore> snapshotStore,
                                       ObjectProvider<ClusterSeriesStore> seriesStore,
                                       ObjectProvider<HeartbeatStore> heartbeatStoreProvider,
                                       InstanceIdResolver instanceIdResolver) {
        // history is present only when failover.dashboard.history.enabled=true; null otherwise.
        LocalRegistryMetricsSource local = new LocalRegistryMetricsSource(metricsService, history.getIfAvailable(),
                instanceIdResolver);
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
                DashboardProperties.SharedStore sharedStore = cluster.sharedStore();
                log.info("Failover dashboard metrics aggregated cluster-wide via in-memory shared-store "
                        + "(max-instances={}).", sharedStore.maxInstances());
                HeartbeatStore heartbeatStore = heartbeatStoreProvider.getIfAvailable();
                long livenessMillis = sharedStore.livenessSeconds() * 1000L;
                return new SharedStoreMetricsSource(store, properties.health(), local,
                        sharedStore.maxInstances(), seriesStore.getIfAvailable(),
                        heartbeatStore, livenessMillis);
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
        return new SnapshotStoreInmemory(sharedStore.maxInstances());
    }

    /** Ingest controller for peer snapshot pushes; present only in shared-store mode. */
    @Bean
    @ConditionalOnProperty(prefix = "failover.dashboard.cluster", name = "mode", havingValue = "shared-store")
    @ConditionalOnBean(SnapshotStore.class)
    @ConditionalOnMissingBean
    public ClusterSnapshotController clusterSnapshotController(SnapshotStore snapshotStore) {
        return new ClusterSnapshotController(snapshotStore);
    }

    /** In-memory heartbeat store — always present in shared-store mode. Instances that never send a heartbeat stay UNKNOWN. */
    @Bean
    @ConditionalOnProperty(prefix = "failover.dashboard.cluster", name = "mode", havingValue = "shared-store")
    @ConditionalOnMissingBean(HeartbeatStore.class)
    public HeartbeatStore heartbeatStore() {
        return new HeartbeatStoreInmemory();
    }

    /** Heartbeat ingest endpoint — always active in shared-store mode; peers opt in by enabling heartbeat on their side. */
    @Bean
    @ConditionalOnProperty(prefix = "failover.dashboard.cluster", name = "mode", havingValue = "shared-store")
    @ConditionalOnMissingBean
    public ClusterHeartbeatController clusterHeartbeatController(HeartbeatStore heartbeatStore) {
        return new ClusterHeartbeatController(heartbeatStore);
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
     * Access-control gate when Spring Security IS on the classpath (design doc §9 gate 4).
     *
     * <p>Contains three tiers of ingest-endpoint security for {@code base-path/api/cluster/snapshot},
     * each mutually exclusive and evaluated in declaration order:
     * <ol>
     *   <li>{@code dashboardIngestBasicFilterChain} — HTTP Basic with peer credentials from config.</li>
     *   <li>{@code dashboardIngestOpenFilterChain} — permit-all for trusted internal networks
     *       (shared-store mode, no auth configured).</li>
     * </ol>
     * OAuth2 JWT validation lives in {@link OAuth2IngestSecurityConfiguration} (separate class required
     * to avoid loading the resource-server API when it is absent from the classpath).
     *
     * <p>The main {@code dashboardSecurityFilterChain} is {@code @Order(0)}; ingest chains are
     * {@code @Order(-10)} so they match {@code /api/cluster/snapshot} before the broad {@code /**} gate.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SecurityFilterChain.class)
    static class SecurityPresentConfiguration {

        /**
         * Ingest secured with HTTP Basic using a dedicated in-memory user (peer credentials from config).
         * Activated when {@code snapshot.username} is set and no OAuth2 ingest chain is registered first.
         * Stateless — service-to-service only; no session is created, so CSRF does not apply.
         */
        @Bean
        @Order(-10)
        @ConditionalOnProperty(prefix = "failover.dashboard.cluster.snapshot", name = "username")
        @ConditionalOnMissingBean(name = "dashboardIngestOAuth2FilterChain")
        SecurityFilterChain dashboardIngestBasicFilterChain(HttpSecurity http, DashboardProperties props) {
            String ingestPath = props.basePath() + "/api/cluster/snapshot";
            DashboardProperties.Snapshot snapshot = props.cluster().snapshot();
            String password = snapshot.password();
            String encodedPassword = password.startsWith("{") ? password : "{noop}" + password;
            InMemoryUserDetailsManager userDetails = new InMemoryUserDetailsManager(
                    User.builder().username(snapshot.username()).password(encodedPassword)
                            .roles("FAILOVER_PEER").build());
            http.securityMatcher(ingestPath)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults())
                    .userDetailsService(userDetails)
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .csrf(csrf -> csrf.ignoringRequestMatchers(ingestPath));
            log.info("Failover dashboard ingest '{}' secured with HTTP Basic (user: '{}').",
                    ingestPath, snapshot.username());
            return http.build();
        }

        /**
         * Ingest open (permit-all) when {@code snapshot.allow-insecure-ingest=true} is explicitly set.
         * Not created when Basic or OAuth2 ingest chain is already registered.
         */
        @Bean
        @Order(-10)
        @ConditionalOnProperty(prefix = "failover.dashboard.cluster.snapshot", name = "allow-insecure-ingest",
                havingValue = "true")
        @ConditionalOnMissingBean(name = {"dashboardIngestOAuth2FilterChain", "dashboardIngestBasicFilterChain"})
        SecurityFilterChain dashboardIngestOpenFilterChain(HttpSecurity http, DashboardProperties props) {
            String ingestPath = props.basePath() + "/api/cluster/snapshot";
            http.securityMatcher(ingestPath)
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .csrf(csrf -> csrf.ignoringRequestMatchers(ingestPath));
            log.warn("===================================================================================");
            log.warn("Failover dashboard ingest '{}' is running WITHOUT an access-control gate.", ingestPath);
            log.warn("(allow-insecure-ingest=true) Set snapshot.username+password or");
            log.warn("snapshot.oauth2-client-registration-id to secure the ingest endpoint.");
            log.warn("Use only on a trusted internal network (k8s namespace, VPC).");
            log.warn("===================================================================================");
            return http.build();
        }

        /** Main dashboard gate: {@code base-path/**} requires the configured role. {@code @Order(0)} ensures
         * ingest chains at {@code @Order(-10)} are evaluated first for {@code /api/cluster/snapshot}.
         * Stateless — HTTP Basic auth; no session is created. Dashboard is read-only (GET only), so no
         * state-changing operations exist and CSRF protection uses Spring Security defaults. */
        @Bean
        @Order(0)
        @ConditionalOnMissingBean(name = "dashboardSecurityFilterChain")
        SecurityFilterChain dashboardSecurityFilterChain(HttpSecurity http, DashboardProperties props) {
            http.securityMatcher(props.basePath() + "/**")
                    .authorizeHttpRequests(auth -> auth.anyRequest().hasRole(props.security().role()))
                    .httpBasic(Customizer.withDefaults())
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
            log.info("Failover dashboard secured: '{}/**' requires role '{}'.",
                    props.basePath(), props.security().role());
            return http.build();
        }
    }

    /**
     * Secures the ingest endpoint with OAuth2 JWT Bearer validation when the consumer has
     * {@code spring-security-oauth2-resource-server} on the classpath and
     * {@code snapshot.oauth2-client-registration-id} is set. Takes priority over the main dashboard
     * security chain via {@code @Order(-10)}.
     *
     * <p>Isolated in its own inner class so the resource-server API is never loaded when absent.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.oauth2.server.resource.BearerTokenAuthenticationToken")
    static class OAuth2IngestSecurityConfiguration {

        @Bean("dashboardIngestOAuth2FilterChain")
        @Order(-10)
        @ConditionalOnProperty(prefix = "failover.dashboard.cluster.snapshot", name = "oauth2-client-registration-id")
        @ConditionalOnMissingBean(name = "dashboardIngestOAuth2FilterChain")
        SecurityFilterChain dashboardIngestOAuth2FilterChain(HttpSecurity http, DashboardProperties props) {
            String ingestPath = props.basePath() + "/api/cluster/snapshot";
            http.securityMatcher(ingestPath)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .csrf(csrf -> csrf.ignoringRequestMatchers(ingestPath));
            log.info("Failover dashboard ingest '{}' secured with OAuth2 JWT.", ingestPath);
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
