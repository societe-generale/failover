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

import com.societegenerale.failover.dashboard.security.DashboardAccessDeniedHandler;
import com.societegenerale.failover.dashboard.security.DashboardAuthBackingValidator;
import com.societegenerale.failover.dashboard.security.DashboardAuthenticationConfigurer;
import com.societegenerale.failover.dashboard.security.DefaultFailoverSecurityProvider;
import com.societegenerale.failover.dashboard.security.FailoverSecurityProvider;
import com.societegenerale.failover.dashboard.security.SecurityContext;
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
import com.societegenerale.failover.observable.metrics.FailoverConfigSnapshotService;
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
import org.springframework.security.authentication.AuthenticationProvider;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.core.userdetails.User;
import org.springframework.security.core.userdetails.UserDetailsService;
import org.springframework.security.provisioning.InMemoryUserDetailsManager;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.ResourceHandlerRegistry;
import org.springframework.web.servlet.config.annotation.ViewControllerRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

import java.util.Arrays;

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
                // After the failover library (when present) so its real FailoverMetricsSnapshotService /
                // FailoverConfigSnapshotService beans win over the standalone fallbacks below. Referenced by
                // name — no compile dependency.
                "com.societegenerale.failover.configuration.FailoverAutoConfiguration",
                // After Boot's own fallback-user auto-configuration so dashboardAuthBackingValidator sees
                // whether Boot already created a generated-password UserDetailsService before deciding
                // whether the UI gate has no way to authenticate anyone.
                "org.springframework.boot.security.autoconfigure.UserDetailsServiceAutoConfiguration",
                // After the optional JDBC shared-store module (when present) so its SnapshotStore/HeartbeatStore
                // beans exist by the time snapshotStore()/heartbeatStore() (@ConditionalOnExpression, store=inmemory)
                // and clusterSnapshotController/clusterHeartbeatController (@ConditionalOnBean) evaluate — without
                // this, @ConditionalOnBean unreliably sees "no bean" regardless of declaration order between two
                // independently-conditioned @AutoConfiguration classes, and the ingest endpoints silently never map.
                "com.societegenerale.failover.dashboard.metrics.source.sharedstore.jdbc.SnapshotStoreJdbcAutoConfiguration"
        })
@ConditionalOnWebApplication(type = SERVLET)
@ConditionalOnClass(MeterRegistry.class)
@ConditionalOnProperty(prefix = "failover.dashboard", name = "enabled", havingValue = "true")
@EnableConfigurationProperties(DashboardProperties.class)
public class DashboardAutoConfiguration implements WebMvcConfigurer {

    private final DashboardProperties properties;

    /**
     * Creates the autoconfiguration, bound to the validated properties.
     *
     * @param properties the bound {@code failover.dashboard.*} properties
     */
    public DashboardAutoConfiguration(DashboardProperties properties) {
        this.properties = properties;
        log.info("Failover dashboard enabled at base path '{}'.", properties.basePath());
        if ("shared-store".equalsIgnoreCase(properties.cluster().mode()) && !properties.exposure().includes("cluster")) {
            log.warn("cluster.mode=shared-store is enabled but 'cluster' is missing from exposure.include; "
                            + "this no longer blocks peer snapshot pushes to '{}/api/cluster/snapshot' (the ingest "
                            + "endpoint is exempt from exposure narrowing), but the cluster read views will 404 "
                            + "for UI/API consumers until 'cluster' is added to exposure.include.",
                    properties.basePath());
        }
    }

    /**
     * Assembles the config-view service.
     *
     * @param environment   the {@code Environment}, source of the {@code failover.*} global settings
     * @param metricsSource optional source of {@code @Failover} config entries
     * @return the config service
     */
    @Bean
    @ConditionalOnMissingBean
    public DashboardConfigService dashboardConfigService(Environment environment,
                                                         ObjectProvider<MetricsSource> metricsSource) {
        return new DashboardConfigService(environment, metricsSource.getIfAvailable());
    }

    /**
     * Registers the config-view REST controller.
     *
     * @param configService the config service to delegate to
     * @return the controller
     */
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
     *
     * @param environment resolves {@code spring.application.name} and the server port
     * @return the default instance id resolver
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
     *
     * @param registry the meter registry to read {@code failover.*} meters from
     * @return the metrics snapshot service
     */
    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean
    public FailoverMetricsSnapshotService failoverMetricsSnapshotService(MeterRegistry registry) {
        return new FailoverMetricsSnapshotService(registry);
    }

    /**
     * Standalone fallback {@link FailoverConfigSnapshotService} for dashboard-only deployments where
     * {@code failover-spring-boot-autoconfigure} is not on the classpath. When the full failover starter
     * is present, {@code FailoverMicrometerAutoConfiguration} contributes the real service first and this
     * fallback is skipped. Backs the config view with zero dependency on {@code FailoverScanner} — reads
     * only the {@code failover.config.*} gauges the running service emits (empty when none present).
     *
     * @param registry the meter registry to read {@code failover.config.*} gauges from
     * @return the config snapshot service
     */
    @Bean
    @ConditionalOnBean(MeterRegistry.class)
    @ConditionalOnMissingBean
    public FailoverConfigSnapshotService failoverConfigSnapshotService(MeterRegistry registry) {
        return new FailoverConfigSnapshotService(registry);
    }

    /**
     * Metrics service + controller — present only when a {@link MeterRegistry} is in the context.
     * Without Micrometer the config view above still works; the metrics view degrades gracefully (§3).
     *
     * @param snapshotService source of the current metrics summary
     * @return the metrics service
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
     *
     * @param metricsService        source of this instance's metrics summary
     * @param history               optional trend-history ring, present when {@code history.enabled=true}
     * @param snapshotStore         optional shared-store snapshot store, present in {@code cluster.mode=shared-store}
     * @param seriesStore           optional cluster-trend ring, present in {@code cluster.mode=shared-store}
     * @param heartbeatStoreProvider optional heartbeat store, present in {@code cluster.mode=shared-store}
     * @param configSnapshotService optional source of this instance's {@code @Failover} configuration
     * @param instanceIdResolver    resolves this instance's identity for the local/shared-store views
     * @return the assembled metrics source for the configured {@code cluster.mode}
     */
    @Bean
    @ConditionalOnBean(DashboardMetricsService.class)
    @ConditionalOnMissingBean
    public MetricsSource metricsSource(DashboardMetricsService metricsService,
                                       ObjectProvider<DashboardHistoryService> history,
                                       ObjectProvider<SnapshotStore> snapshotStore,
                                       ObjectProvider<ClusterSeriesStore> seriesStore,
                                       ObjectProvider<HeartbeatStore> heartbeatStoreProvider,
                                       ObjectProvider<FailoverConfigSnapshotService> configSnapshotService,
                                       InstanceIdResolver instanceIdResolver) {
        // history is present only when failover.dashboard.history.enabled=true; null otherwise.
        LocalRegistryMetricsSource local = new LocalRegistryMetricsSource(metricsService, history.getIfAvailable(),
                instanceIdResolver, configSnapshotService.getIfAvailable());
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
     *
     * @return the in-memory snapshot store
     */
    @Bean
    @ConditionalOnExpression("'${failover.dashboard.cluster.mode:local}' == 'shared-store' "
            + "and '${failover.dashboard.cluster.shared-store.store:inmemory}' == 'inmemory'")
    @ConditionalOnMissingBean
    public SnapshotStore snapshotStore() {
        DashboardProperties.SharedStore sharedStore = properties.cluster().sharedStore();
        return new SnapshotStoreInmemory(sharedStore.maxInstances(), sharedStore.instanceRetention());
    }

    /**
     * Ingest controller for peer snapshot pushes; present only in shared-store mode, and only when the HTTP
     * ingest path is wanted at all ({@code cluster.snapshot.ingest.enabled}, default {@code true}). Turn it
     * off once every peer writes directly to the shared JDBC table instead — the dashboard still reads from
     * {@link SnapshotStore} either way, this only stops mapping the HTTP path (and its ingest security gate)
     * into it.
     *
     * @param snapshotStore the store to record incoming peer snapshots into
     * @return the ingest controller
     */
    @Bean
    @ConditionalOnProperty(prefix = "failover.dashboard.cluster", name = "mode", havingValue = "shared-store")
    @ConditionalOnProperty(prefix = "failover.dashboard.cluster.snapshot.ingest", name = "enabled", havingValue = "true", matchIfMissing = true)
    @ConditionalOnBean(SnapshotStore.class)
    @ConditionalOnMissingBean
    public ClusterSnapshotController clusterSnapshotController(SnapshotStore snapshotStore) {
        return new ClusterSnapshotController(snapshotStore);
    }

    /**
     * In-memory heartbeat store for {@code cluster.mode=shared-store} — the default {@code store=inmemory}.
     * {@code store=jdbc} instead activates the durable {@code failover-dashboard-snapshotstore-jdbc} module's
     * {@code HeartbeatStoreJdbc}, so liveness stays consistent across dashboards embedded in multiple instances
     * sharing one database. {@code @ConditionalOnMissingBean} so a consumer can supply their own implementation.
     * Instances that never send a heartbeat stay {@code UNKNOWN}.
     *
     * <p>Gated on {@code shared-store.liveness.enabled} (default {@code false}, {@link DashboardProperties.Liveness}) —
     * off by default, no bean at all until explicitly enabled, so no consumer is ever forced to run the
     * heartbeat ingest endpoint or (under {@code store=jdbc}) provision {@code FAILOVER_DASHBOARD_HEARTBEAT}
     * just because shared-store mode is on.
     *
     * @return the in-memory heartbeat store
     */
    @Bean
    @ConditionalOnExpression("'${failover.dashboard.cluster.mode:local}' == 'shared-store' "
            + "and '${failover.dashboard.cluster.shared-store.store:inmemory}' == 'inmemory' "
            + "and '${failover.dashboard.cluster.shared-store.liveness.enabled:false}' == 'true'")
    @ConditionalOnMissingBean(HeartbeatStore.class)
    public HeartbeatStore heartbeatStore() {
        return new HeartbeatStoreInmemory();
    }

    /**
     * Heartbeat ingest endpoint; present only in shared-store mode with a {@link HeartbeatStore} bean available
     * (mirrors {@link #clusterSnapshotController}'s guard — e.g. {@code store=jdbc} without the JDBC module on
     * the classpath leaves no {@link HeartbeatStore} bean, and this controller must not be wired then either).
     * Peers opt in by enabling heartbeat on their side.
     *
     * @param heartbeatStore the store to record incoming peer heartbeats into
     * @return the heartbeat ingest controller
     */
    @Bean
    @ConditionalOnProperty(prefix = "failover.dashboard.cluster", name = "mode", havingValue = "shared-store")
    @ConditionalOnBean(HeartbeatStore.class)
    @ConditionalOnMissingBean
    public ClusterHeartbeatController clusterHeartbeatController(HeartbeatStore heartbeatStore) {
        return new ClusterHeartbeatController(heartbeatStore);
    }

    /**
     * Bounded, retention-pruned ring holding the cluster-wide trend (design §5.4); shared-store mode only.
     *
     * @return the cluster series ring
     */
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
     *
     * @param metricsSource the assembled cluster-wide metrics source to sample
     * @param seriesStore   the ring to append samples into
     * @return the sampler
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
     * Registers the metrics-view REST controller.
     *
     * @param metricsSource the assembled metrics source to serve from
     * @return the controller
     */
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
     * Welcome mapping: the trailing-slash {@code base-path} forwards to the static {@code index.html},
     * so {@code /failover-dashboard/} opens the UI without the explicit file name. The bare
     * {@code base-path} (no trailing slash) redirects to the trailing-slash form instead of forwarding,
     * so the browser's URL bar actually gains the slash — otherwise index.html's relative asset URLs
     * (e.g. {@code app.js}) resolve as siblings of the last path segment and are requested from root
     * (e.g. {@code /app.js}), missing the {@code base-path/**} security matcher entirely and 401'ing
     * against the consumer's own default security rules. Skipped when UI exposure is narrowed off.
     */
    @Override
    public void addViewControllers(@NonNull ViewControllerRegistry registry) {
        if (!properties.exposure().ui()) {
            return;
        }
        registry.addRedirectViewController(properties.basePath(), properties.basePath() + "/");
        registry.addViewController(properties.basePath() + "/")
                .setViewName("forward:" + properties.basePath() + "/index.html");
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
     * {@code @Order(-10)} so they match both ingest paths before the broad {@code /**} gate.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(SecurityFilterChain.class)
    static class SecurityPresentConfiguration {

        /**
         * Fails fast when an insecure escape hatch is left on under the {@code prod} profile — the same
         * I-14 guarantee {@link SecurityAbsentConfiguration} enforces when Spring Security is absent
         * entirely, extended to cover the case where Security is present but a consumer explicitly asked
         * for {@code allow-insecure} (main gate) or {@code allow-insecure-ingest} (peer ingest).
         *
         * @param props       the bound {@code failover.dashboard.*} properties
         * @param environment used to check whether the {@code prod} profile is active
         */
        SecurityPresentConfiguration(DashboardProperties props, Environment environment) {
            boolean prod = environment.acceptsProfiles(Profiles.of("prod"));
            if (props.security().allowInsecure() && prod) {
                throw new IllegalStateException(
                        "Failover dashboard has failover.dashboard.security.allow-insecure=true while the 'prod' "
                                + "profile is active. The insecure escape hatch is for dev / trusted-network use "
                                + "only and must never disable the access gate in production. Restrict '"
                                + props.basePath() + "/**' to role '" + props.security().role()
                                + "', or remove allow-insecure / the 'prod' profile.");
            }
            if (props.cluster().snapshot().allowInsecureIngest() && prod) {
                throw new IllegalStateException(
                        "Failover dashboard has failover.dashboard.cluster.snapshot.allow-insecure-ingest=true "
                                + "while the 'prod' profile is active. The insecure ingest escape hatch is for dev "
                                + "/ trusted-network use only and must never disable the access gate in "
                                + "production. Set snapshot.username+password or "
                                + "snapshot.oauth2-client-registration-id, or remove allow-insecure-ingest / the "
                                + "'prod' profile.");
            }
        }

        /**
         * Ingest secured with HTTP Basic using a dedicated in-memory user (peer credentials from config).
         * Activated when {@code snapshot.username} is set and no OAuth2 ingest chain is registered first.
         * Covers both the snapshot and heartbeat paths with one matcher so they can't drift onto
         * different gates. Stateless — service-to-service only; no session is created, so CSRF does not
         * apply.
         */
        @Bean
        @Order(-10)
        @ConditionalOnProperty(prefix = "failover.dashboard.cluster.snapshot", name = "username")
        @ConditionalOnMissingBean(name = "dashboardIngestOAuth2FilterChain")
        SecurityFilterChain dashboardIngestBasicFilterChain(HttpSecurity http, DashboardProperties props) {
            String[] ingestPaths = ingestPaths(props);
            DashboardProperties.Snapshot snapshot = props.cluster().snapshot();
            String password = snapshot.password();
            String encodedPassword = password.startsWith("{") ? password : "{noop}" + password;
            InMemoryUserDetailsManager userDetails = new InMemoryUserDetailsManager(
                    User.builder().username(snapshot.username()).password(encodedPassword)
                            .roles("FAILOVER_PEER").build());
            http.securityMatcher(ingestPaths)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .httpBasic(Customizer.withDefaults())
                    .userDetailsService(userDetails)
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .csrf(csrf -> csrf.ignoringRequestMatchers(ingestPaths));
            log.info("Failover dashboard ingest {} secured with HTTP Basic (user: '{}').",
                    Arrays.toString(ingestPaths), snapshot.username());
            return http.build();
        }

        /**
         * Ingest open (permit-all) when {@code snapshot.allow-insecure-ingest=true} is explicitly set.
         * Not created when Basic or OAuth2 ingest chain is already registered. Covers both the snapshot
         * and heartbeat paths.
         */
        @Bean
        @Order(-10)
        @ConditionalOnProperty(prefix = "failover.dashboard.cluster.snapshot", name = "allow-insecure-ingest",
                havingValue = "true")
        @ConditionalOnMissingBean(name = {"dashboardIngestOAuth2FilterChain", "dashboardIngestBasicFilterChain"})
        SecurityFilterChain dashboardIngestOpenFilterChain(HttpSecurity http, DashboardProperties props) {
            String[] ingestPaths = ingestPaths(props);
            http.securityMatcher(ingestPaths)
                    .authorizeHttpRequests(auth -> auth.anyRequest().permitAll())
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .csrf(csrf -> csrf.ignoringRequestMatchers(ingestPaths));
            log.warn("===================================================================================");
            log.warn("Failover dashboard ingest {} is running WITHOUT an access-control gate.",
                    Arrays.toString(ingestPaths));
            log.warn("(allow-insecure-ingest=true) Set snapshot.username+password or");
            log.warn("snapshot.oauth2-client-registration-id to secure the ingest endpoint.");
            log.warn("Use only on a trusted internal network (k8s namespace, VPC).");
            log.warn("===================================================================================");
            return http.build();
        }

        /**
         * Top-priority authentication seam: when a consumer declares a {@link DashboardAuthenticationConfigurer}
         * bean, it wins over both built-in mechanisms (HTTP Basic, OAuth2 login) and the built-in OAuth2
         * resource-server option. Applies the shared matcher, {@link FailoverSecurityProvider} authorization,
         * and {@link DashboardAccessDeniedHandler} identically to the built-in chains, then delegates only the
         * authentication step to the consumer's implementation — covering any mechanism the built-ins don't
         * (a resource-server/JWT setup with custom validation, a trusted-header identity forwarded by a
         * reverse proxy, SAML, mTLS, an existing {@code AuthenticationProvider}, or a {@code client_credentials}-only
         * shop that wants the dashboard behind its own gateway's auth entirely) without re-declaring the whole
         * chain.
         *
         * <p><b>Must stay declared before {@code dashboardAuthBackingValidator} and
         * {@code dashboardSecurityFilterChain} in this class</b> — same source-declaration-order reasoning as
         * documented on {@code dashboardAuthBackingValidator} below.
         *
         * @param http                     the {@code HttpSecurity} to configure
         * @param failoverSecurityProvider applies the configured role/authority/expression check
         * @param accessDeniedHandler      reports {@code 403} with a specific reason
         * @param props                    the bound {@code failover.dashboard.*} properties
         * @param authenticationConfigurer the consumer-supplied authentication mechanism
         * @return the assembled chain
         * @throws Exception as {@code HttpSecurity} configuration methods declare
         */
        @Bean
        @Order(0)
        @ConditionalOnBean(DashboardAuthenticationConfigurer.class)
        @ConditionalOnMissingBean(name = "dashboardCustomAuthFilterChain")
        SecurityFilterChain dashboardCustomAuthFilterChain(
                HttpSecurity http, FailoverSecurityProvider failoverSecurityProvider,
                DashboardAccessDeniedHandler accessDeniedHandler, DashboardProperties props,
                DashboardAuthenticationConfigurer authenticationConfigurer) throws Exception {
            SecurityContext context = new SecurityContext(props.basePath(), props.security());
            http.securityMatcher(props.basePath() + "/**")
                    .authorizeHttpRequests(auth -> failoverSecurityProvider.configure(auth, context))
                    .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(accessDeniedHandler));
            authenticationConfigurer.configure(http, context);
            log.info("Failover dashboard secured: '{}/**' via a custom DashboardAuthenticationConfigurer, "
                    + "requires role '{}'.", props.basePath(), props.security().role());
            return http.build();
        }

        /**
         * Fails fast when the UI gate has no way to authenticate anyone — {@code security.type=ROLE} or
         * {@code AUTHORITY} requires a non-anonymous authenticated principal, but {@code httpBasic()} alone
         * doesn't guarantee one exists: without a {@code UserDetailsService} or {@code AuthenticationProvider}
         * bean anywhere in the app, every credential 401s and there is no correct password to find (a
         * multi-hour dead-end otherwise, since the app boots clean with no diagnostic).
         *
         * <p><b>Must stay declared before {@code dashboardSecurityFilterChain} in this class.</b>
         * {@code @ConditionalOnMissingBean(name=...)} conditions for {@code @Bean} methods in the same
         * {@code @Configuration} class are evaluated in source-declaration order — if this method were
         * declared after {@code dashboardSecurityFilterChain}, the starter's own default bean would already
         * be registered under that name by the time this condition runs, so the check would always see
         * "not missing" and silently skip itself even with zero backing auth. Declared first, it only ever
         * sees a name already registered when a genuine consumer override exists (Spring Boot registers all
         * user-supplied {@code @Configuration} beans before any {@code @AutoConfiguration} bean, so a real
         * override is always visible here regardless of order within this class).
         *
         * <p>Skipped when a consumer overrides {@code dashboardSecurityFilterChain} by name, or supplies a
         * {@link DashboardAuthenticationConfigurer} bean (that mechanism authenticates on its own terms — this
         * validator can't know whether it needs a {@code UserDetailsService}). Skipped (not evaluated for the
         * real-auth requirement) when {@code security.oauth2-client-registration-id} is set — the OAuth2 login
         * variant handles authentication itself and needs no backing {@code UserDetailsService} — checked
         * directly against the config value rather than the sibling bean's name, since that bean lives in a
         * different nested {@code @Configuration} class ({@link OAuth2LoginSecurityConfiguration}) whose
         * relative processing order is not guaranteed. Likewise skipped when
         * {@code security.oauth2-resource-server=true} — see {@link OAuth2ResourceServerSecurityConfiguration}.
         * {@code security.type=EXPRESSION} only warns, not fails: a SpEL expression might legitimately grant
         * access without real authentication (e.g. an IP-based rule), which we can't determine statically.
         *
         * @param props                     the bound {@code failover.dashboard.*} properties
         * @param userDetailsServices       any {@code UserDetailsService} beans in the context
         * @param authenticationProviders   any {@code AuthenticationProvider} beans in the context
         * @param authenticationConfigurers any {@code DashboardAuthenticationConfigurer} beans in the context
         * @return a marker confirming the check ran
         */
        @Bean
        @ConditionalOnMissingBean(name = "dashboardSecurityFilterChain")
        DashboardAuthBackingValidator dashboardAuthBackingValidator(
                DashboardProperties props,
                ObjectProvider<UserDetailsService> userDetailsServices,
                ObjectProvider<AuthenticationProvider> authenticationProviders,
                ObjectProvider<DashboardAuthenticationConfigurer> authenticationConfigurers) {
            if (!props.security().oauth2ClientRegistrationId().isBlank() || props.security().oauth2ResourceServer()
                    || authenticationConfigurers.stream().findAny().isPresent()) {
                return new DashboardAuthBackingValidator(); // another mechanism authenticates instead
            }
            boolean hasBackingAuth = userDetailsServices.stream().findAny().isPresent()
                    || authenticationProviders.stream().findAny().isPresent();
            boolean requiresRealAuth = props.security().type() != DashboardProperties.SecurityType.EXPRESSION;

            if (!props.security().allowInsecure() && requiresRealAuth && !hasBackingAuth) {
                String message = "Failover dashboard security.type=" + props.security().type()
                        + " requires a UserDetailsService or AuthenticationProvider bean to authenticate "
                        + "against, but none was found. httpBasic() would prompt for credentials that can "
                        + "never succeed. Either define one of those beans (e.g. "
                        + "spring.security.user.name/password for a quick dev user), configure "
                        + "failover.dashboard.security.oauth2-client-registration-id for OAuth2 login "
                        + "instead, override the 'dashboardSecurityFilterChain' bean with your own "
                        + "authentication mechanism, or set failover.dashboard.security.allow-insecure=true "
                        + "for trusted-network/dev use.";
                throw new IllegalStateException(message);
            }
            if (!props.security().allowInsecure() && !requiresRealAuth && !hasBackingAuth) {
                log.warn("Failover dashboard security.type=EXPRESSION with no UserDetailsService or "
                        + "AuthenticationProvider bean found — httpBasic() credentials will never succeed "
                        + "unless '{}' is written to also grant access without authentication (e.g. an "
                        + "IP-based rule). If real credentials are expected, define one of those beans.",
                        props.security().expression());
            }
            return new DashboardAuthBackingValidator();
        }

        /** Main dashboard gate: {@code base-path/**} requires the configured role. {@code @Order(0)} ensures
         * ingest chains at {@code @Order(-10)} are evaluated first for the ingest paths. Lowest priority of
         * the four authentication options — backs off when a consumer supplies their own
         * {@code dashboardSecurityFilterChain}, declares a {@link DashboardAuthenticationConfigurer} bean
         * (activates {@code dashboardCustomAuthFilterChain} instead), sets
         * {@code security.oauth2-client-registration-id} (activates {@code dashboardOAuth2SecurityFilterChain},
         * see {@link OAuth2LoginSecurityConfiguration}), or sets {@code security.oauth2-resource-server=true}
         * (activates {@code dashboardOAuth2ResourceServerFilterChain}, see
         * {@link OAuth2ResourceServerSecurityConfiguration}).
         * Stateless — HTTP Basic auth; no session is created. Dashboard is read-only (GET only), so no
         * state-changing operations exist and CSRF protection uses Spring Security defaults. */
        @Bean
        @Order(0)
        @ConditionalOnMissingBean(value = DashboardAuthenticationConfigurer.class,
                name = {"dashboardSecurityFilterChain", "dashboardOAuth2SecurityFilterChain",
                        "dashboardOAuth2ResourceServerFilterChain"})
        SecurityFilterChain dashboardSecurityFilterChain(HttpSecurity http, FailoverSecurityProvider failoverSecurityProvider,
                                                          DashboardAccessDeniedHandler accessDeniedHandler, DashboardProperties props) {
            http.securityMatcher(props.basePath() + "/**")
                    .authorizeHttpRequests(auth -> failoverSecurityProvider.configure(auth,
                            new SecurityContext(props.basePath(), props.security())))
                    .httpBasic(Customizer.withDefaults())
                    .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(accessDeniedHandler))
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS));
            log.info("Failover dashboard secured: '{}/**' requires role '{}'.",
                    props.basePath(), props.security().role());
            return http.build();
        }

        @Bean
        @ConditionalOnMissingBean(FailoverSecurityProvider.class)
        FailoverSecurityProvider failoverSecurityProvider() {
            return new DefaultFailoverSecurityProvider();
        }

        /**
         * Reports {@code 403} denials from the main gate with a specific reason ("missing role 'X'" /
         * "missing authority 'Y'") instead of Spring Security's default blank response. Shared by both the
         * {@code httpBasic} and {@code oauth2Login} variants of the main gate — declare your own
         * {@link DashboardAccessDeniedHandler} bean, or a plain Spring Security {@code AccessDeniedHandler},
         * to override.
         */
        @Bean
        @ConditionalOnMissingBean(DashboardAccessDeniedHandler.class)
        DashboardAccessDeniedHandler dashboardAccessDeniedHandler(DashboardProperties props) {
            return new DashboardAccessDeniedHandler(props.security());
        }
    }

    /**
     * Dashboard UI secured with OAuth2 login instead of HTTP Basic. Activated when
     * {@code failover.dashboard.security.oauth2-client-registration-id} is set. Authorization still goes
     * through {@link FailoverSecurityProvider} — role/authority/expression — same as the {@code httpBasic}
     * variant; only the authentication mechanism differs. Isolated in its own inner class so the
     * oauth2-client API is never loaded when absent from the classpath.
     *
     * <p>Deliberately does not set {@code sessionManagement(STATELESS)} — {@code oauth2Login} needs a
     * session to carry the authorization-code-flow state/PKCE parameters across the IdP redirect. CSRF
     * stays on Spring Security's default (enabled), appropriate for a session-based browser login flow.
     *
     * <p>Mapping IdP claims (e.g. GitHub/OIDC scopes) onto {@code FAILOVER_ADMIN} or another configured
     * role/authority is done via a standard Spring Security {@code GrantedAuthoritiesMapper} bean — not
     * something this starter needs to invent. Without one, {@code hasRole()}/{@code hasAuthority()} will
     * deny an OAuth2-authenticated user whose mapped authorities don't happen to already match.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.oauth2.client.registration.ClientRegistrationRepository")
    static class OAuth2LoginSecurityConfiguration {

        @Bean("dashboardOAuth2SecurityFilterChain")
        @Order(0)
        @ConditionalOnProperty(prefix = "failover.dashboard.security", name = "oauth2-client-registration-id")
        @ConditionalOnMissingBean(value = DashboardAuthenticationConfigurer.class, name = "dashboardOAuth2SecurityFilterChain")
        SecurityFilterChain dashboardOAuth2SecurityFilterChain(
                HttpSecurity http, FailoverSecurityProvider failoverSecurityProvider,
                DashboardAccessDeniedHandler accessDeniedHandler, DashboardProperties props) {
            http.securityMatcher(props.basePath() + "/**")
                    .authorizeHttpRequests(auth -> failoverSecurityProvider.configure(auth,
                            new SecurityContext(props.basePath(), props.security())))
                    .oauth2Login(Customizer.withDefaults())
                    .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(accessDeniedHandler));
            log.info("Failover dashboard secured: '{}/**' via OAuth2 login (registration '{}'), requires role '{}'.",
                    props.basePath(), props.security().oauth2ClientRegistrationId(), props.security().role());
            return http.build();
        }
    }

    /**
     * Dashboard UI/API secured with OAuth2 resource-server (JWT Bearer) validation instead of a browser
     * login. Activated when {@code failover.dashboard.security.oauth2-resource-server=true} — for consumers
     * whose SSO is terminated upstream of the dashboard (an API gateway, service mesh sidecar, or reverse
     * proxy like oauth2-proxy) and forwards a validated JWT on every request, rather than a browser-native
     * OAuth2 authorization_code login. Authorization still goes through {@link FailoverSecurityProvider} —
     * role/authority/expression — same as every other mechanism; only authentication differs.
     *
     * <p>Standard Spring Boot {@code spring.security.oauth2.resourceserver.jwt.*} properties (issuer-uri or
     * jwk-set-uri) configure JWT validation — the same properties {@link OAuth2IngestSecurityConfiguration}
     * relies on for peer ingest. Isolated in its own inner class so the resource-server API is never loaded
     * when absent from the classpath.
     *
     * <p>Stateless — no session is created; a bearer token is expected on every request, including the
     * initial page load, so this option only works when whatever sits in front of the dashboard is able to
     * attach one (a gateway/sidecar pattern) rather than a bare browser navigating directly to the dashboard.
     * For a bare-browser SSO login, use {@code security.oauth2-client-registration-id}
     * ({@link OAuth2LoginSecurityConfiguration}) instead.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken")
    static class OAuth2ResourceServerSecurityConfiguration {

        @Bean("dashboardOAuth2ResourceServerFilterChain")
        @Order(0)
        @ConditionalOnProperty(prefix = "failover.dashboard.security", name = "oauth2-resource-server", havingValue = "true")
        @ConditionalOnMissingBean(value = DashboardAuthenticationConfigurer.class,
                name = {"dashboardSecurityFilterChain", "dashboardOAuth2SecurityFilterChain",
                        "dashboardOAuth2ResourceServerFilterChain"})
        SecurityFilterChain dashboardOAuth2ResourceServerFilterChain(
                HttpSecurity http, FailoverSecurityProvider failoverSecurityProvider,
                DashboardAccessDeniedHandler accessDeniedHandler, DashboardProperties props) {
            http.securityMatcher(props.basePath() + "/**")
                    .authorizeHttpRequests(auth -> failoverSecurityProvider.configure(auth,
                            new SecurityContext(props.basePath(), props.security())))
                    .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .exceptionHandling(exceptions -> exceptions.accessDeniedHandler(accessDeniedHandler));
            log.info("Failover dashboard secured: '{}/**' via OAuth2 resource server (JWT Bearer), requires role '{}'.",
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
    @ConditionalOnClass(name = "org.springframework.security.oauth2.server.resource.authentication.BearerTokenAuthenticationToken")
    static class OAuth2IngestSecurityConfiguration {

        @Bean("dashboardIngestOAuth2FilterChain")
        @Order(-10)
        @ConditionalOnProperty(prefix = "failover.dashboard.cluster.snapshot", name = "oauth2-client-registration-id")
        @ConditionalOnMissingBean(name = "dashboardIngestOAuth2FilterChain")
        SecurityFilterChain dashboardIngestOAuth2FilterChain(HttpSecurity http, DashboardProperties props) {
            String[] ingestPaths = ingestPaths(props);
            http.securityMatcher(ingestPaths)
                    .authorizeHttpRequests(auth -> auth.anyRequest().authenticated())
                    .oauth2ResourceServer(oauth2 -> oauth2.jwt(Customizer.withDefaults()))
                    .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                    .csrf(csrf -> csrf.ignoringRequestMatchers(ingestPaths));
            log.info("Failover dashboard ingest {} secured with OAuth2 JWT.", Arrays.toString(ingestPaths));
            return http.build();
        }
    }

    /**
     * The two peer-ingest paths — {@code base-path/api/cluster/snapshot} and
     * {@code base-path/api/cluster/heartbeat} — matched together by every ingest
     * {@code SecurityFilterChain} so they can never drift onto different gates (one dedicated
     * {@code securityMatcher(...)} call covers both, instead of one hand-maintained per endpoint).
     *
     * @param props the bound {@code failover.dashboard.*} properties
     * @return the two ingest paths, snapshot first
     */
    private static String[] ingestPaths(DashboardProperties props) {
        String cluster = props.basePath() + "/api/cluster/";
        return new String[] {cluster + "snapshot", cluster + "heartbeat"};
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
