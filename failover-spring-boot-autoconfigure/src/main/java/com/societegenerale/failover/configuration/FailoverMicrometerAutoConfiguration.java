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

package com.societegenerale.failover.configuration;

import com.societegenerale.failover.core.expiry.FailoverExpiryExtractor;
import com.societegenerale.failover.core.observable.InstanceIdResolver;
import com.societegenerale.failover.core.observable.publisher.ObservablePublisher;
import com.societegenerale.failover.core.scanner.FailoverScanner;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.observable.metrics.DefaultInstanceIdResolver;
import com.societegenerale.failover.observable.metrics.FailoverMetricsSnapshotService;
import com.societegenerale.failover.observable.micrometer.FailoverMeterBinder;
import com.societegenerale.failover.observable.micrometer.MicrometerObservablePublisher;
import com.societegenerale.failover.properties.FailoverProperties;
import com.societegenerale.failover.properties.Observable;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.micrometer.metrics.autoconfigure.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.web.client.RestClient;

import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/**
 * Autoconfiguration for Micrometer-based failover metrics.
 *
 * <p>Active only when a {@link MeterRegistry} bean is present in the application context.
 *
 * <p><strong>Ordering is critical.</strong> The {@link MeterRegistry} this autoconfiguration
 * depends on is itself contributed by Spring Boot's own metrics autoconfigurations
 * ({@code CompositeMeterRegistryAutoConfiguration}, {@code SimpleMetricsExportAutoConfiguration},
 * and the registry-specific exporters). Because {@code @ConditionalOnBean} only sees beans that
 * were registered by autoconfigurations evaluated <em>before</em> this one, this class must be
 * ordered <em>after</em> those metrics autoconfigurations — otherwise {@code MeterRegistry} does
 * not yet exist when the condition is evaluated, the condition fails, and the application silently
 * falls back to {@code MdcLoggerObservablePublisher} only (no Micrometer metrics). This mirrors how
 * Spring Boot's own meter-binder autoconfigurations (e.g. {@code JvmMetricsAutoConfiguration}) are
 * ordered. The metrics autoconfigurations are referenced by name via {@code afterName} so this
 * module needs no compile-time dependency on the Boot metrics-autoconfigure artifact.
 *
 * <p>The class-level {@code @ConditionalOnClass(name = ...)} uses the string form so that
 * Spring Boot's ASM reader evaluates the condition without loading the class into the JVM.
 * {@code micrometer-core} is a required transitive dependency of {@code failover-monitoring},
 * so {@link MeterRegistry} is always on the classpath for consumers of this autoconfiguration.
 *
 * @author Anand Manissery
 */
@AutoConfiguration(
        after = FailoverAutoConfiguration.class,
        afterName = {
                "org.springframework.boot.micrometer.metrics.autoconfigure.MetricsAutoConfiguration",
                "org.springframework.boot.micrometer.metrics.autoconfigure.CompositeMeterRegistryAutoConfiguration",
                "org.springframework.boot.micrometer.metrics.autoconfigure.export.simple.SimpleMetricsExportAutoConfiguration"
        })
@ConditionalOnExpression("${failover.enabled:true} eq true")
@ConditionalOnClass(name = "io.micrometer.core.instrument.MeterRegistry")
@ConditionalOnBean(MeterRegistry.class)
@EnableConfigurationProperties({FailoverProperties.class, FailoverClusterPublisherProperties.class})
@Slf4j
public class FailoverMicrometerAutoConfiguration {

    /**
     * Default instance identity resolver: {@code <app>:<host>:<port>}.
     *
     * <p>Registered here (metrics config) rather than the dashboard config so that any service that
     * publishes metrics — including peer apps in cluster mode that only have the failover starter, not
     * the dashboard starter — gets the correct instance id without pulling in dashboard dependencies.
     *
     * <p>Declare a custom {@link InstanceIdResolver} bean to override — for example to use a k8s pod
     * name, a Docker container id, or the explicit {@code failover.observable.instance.id} value.
     */
    @ConditionalOnMissingBean
    @Bean
    public InstanceIdResolver instanceIdResolver(Environment environment) {
        String app = environment.getProperty("spring.application.name", "application");
        String host = DefaultInstanceIdResolver.resolveHostname();
        return new DefaultInstanceIdResolver(app, host,
                () -> environment.getProperty("local.server.port",
                        environment.getProperty("server.port", "8080")));
    }

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
     * @param failoverStore    the assembled store; backs the {@code failover.live.entries} gauge when size-aware
     * @return {@link FailoverMeterBinder}
     */
    @ConditionalOnMissingBean(FailoverMeterBinder.class)
    @Bean
    public FailoverMeterBinder failoverMeterBinder(
            FailoverScanner failoverScanner,
            FailoverExpiryExtractor expiryExtractor,
            ObjectProvider<FailoverStore<Object>> failoverStore) {
        return new FailoverMeterBinder(failoverScanner, expiryExtractor, failoverStore.getIfAvailable());
    }

    /**
     * Aggregates the existing {@code failover.*} Micrometer counters into a {@link com.societegenerale.failover.observable.metrics.MetricsSummary}.
     * Used by peer apps to build snapshots for the shared-store cluster tier without dashboard dependencies.
     */
    @ConditionalOnMissingBean
    @Bean
    public FailoverMetricsSnapshotService failoverMetricsSnapshotService(MeterRegistry registry) {
        return new FailoverMetricsSnapshotService(registry);
    }

    // ── instance tag (failover.observable.instance.mode) ──────────────────────────────

    /**
     * {@code instance}-tag mode {@code auto} (default): add the {@code instance} tag to every registry
     * <em>except</em> a Prometheus one, via a {@link MeterRegistryCustomizer}. Isolated in this nested
     * configuration so the optional {@code spring-boot-micrometer-metrics} type is referenced only when present
     * — {@code @ConditionalOnClass} is ASM-evaluated, so a runtime without that jar (e.g. the dashboard-only
     * classpath) simply skips this class instead of failing to introspect the enclosing one.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnExpression("${failover.enabled:true} eq true")
    @ConditionalOnClass(MeterRegistryCustomizer.class)
    @ConditionalOnProperty(prefix = "failover.observable.instance", name = "mode", havingValue = "auto", matchIfMissing = true)
    static class InstanceTagAutoConfiguration {

        /**
         * Prometheus attaches its own {@code instance} label at scrape time, so tagging it would only yield a
         * confusing {@code exported_instance}; push registries (OTLP / Elastic / Datadog) have no scrape-time
         * label, so they are tagged — making pods distinguishable with zero config. Per-registry, so a composite
         * (Prometheus + OTLP at once) is handled correctly: the Prometheus delegate stays clean, the push one tagged.
         *
         * @param properties  failover properties (instance id)
         * @param environment resolves {@code spring.application.name} when the id is left blank
         * @return a customizer installing the instance filter on each non-Prometheus registry
         */
        @ConditionalOnMissingBean(name = "failoverInstanceMeterRegistryCustomizer")
        @Bean
        MeterRegistryCustomizer<MeterRegistry> failoverInstanceMeterRegistryCustomizer(FailoverProperties properties,
                                                                                       Environment environment) {
            String instanceId = resolveInstanceId(properties.getObservable().getInstance(), environment);
            MeterFilter filter = instanceTagFilter(instanceId);
            return registry -> {
                if (isPrometheus(registry)) {
                    return;   // Prometheus adds `instance` at scrape — leave its registry untagged
                }
                log.info("Failover meters tagged with instance='{}' on {} (mode=auto).",
                        instanceId, registry.getClass().getSimpleName());
                registry.config().meterFilter(filter);
            };
        }
    }

    /**
     * {@code instance}-tag mode {@code always}: tag {@code failover.*} on <em>every</em> registry, including a
     * Prometheus one (surfaces as {@code exported_instance}). A global {@link MeterFilter} bean (micrometer-core
     * only, so it works even without {@code spring-boot-micrometer-metrics}).
     *
     * @param properties  failover properties (instance id)
     * @param environment resolves {@code spring.application.name} when the id is left blank
     * @return a meter filter tagging {@code failover.*} on all registries
     */
    @ConditionalOnProperty(prefix = "failover.observable.instance", name = "mode", havingValue = "always")
    @ConditionalOnMissingBean(name = "failoverInstanceMeterFilter")
    @Bean
    public MeterFilter failoverInstanceMeterFilter(FailoverProperties properties, Environment environment) {
        String instanceId = resolveInstanceId(properties.getObservable().getInstance(), environment);
        log.info("Failover meters are tagged with instance='{}' on all registries (mode=always).", instanceId);
        return instanceTagFilter(instanceId);
    }

    /** A {@link MeterFilter} that appends the {@code instance} tag to {@code failover.*} meters only. */
    private static MeterFilter instanceTagFilter(String instanceId) {
        Tag instanceTag = Tag.of("instance", instanceId);
        return new MeterFilter() {
            @Override
            public Meter.Id map(Meter.Id id) {
                return id.getName().startsWith("failover") ? id.withTag(instanceTag) : id;
            }
        };
    }

    /** True for a Prometheus(-compatible) registry, which supplies its own {@code instance} label at scrape time. */
    private static boolean isPrometheus(MeterRegistry registry) {
        return registry.getClass().getSimpleName().contains("Prometheus");
    }

    /** Resolves the instance id: the explicit value, else {@code spring.application.name:hostname}. */
    private static String resolveInstanceId(Observable.Instance instance, Environment environment) {
        if (instance.getId() != null && !instance.getId().isBlank()) {
            return instance.getId();
        }
        String app = environment.getProperty("spring.application.name", "application");
        String host;
        try {
            host = InetAddress.getLocalHost().getHostName();
        } catch (Exception e) {
            host = "unknown-host";
        }
        return app + ":" + host;
    }

    // ── cluster snapshot publisher (shared-store peer side) ───────────────────────────

    /**
     * Snapshot publisher configuration — active only when {@code spring-web} is on the classpath
     * (RestClient is in spring-web). Isolated in a nested class so that the RestClient type is only
     * referenced when it is actually available — ASM-evaluated condition means no runtime failure
     * when the consumer is a non-web app.
     */
    @Configuration(proxyBeanMethods = false)
    @ConditionalOnClass(name = "org.springframework.web.client.RestClient")
    @Slf4j
    static class PublisherConfiguration {

        /**
         * Peer-side snapshot publisher: periodically POSTs this instance's local metrics snapshot to
         * the dashboard's ingest endpoint. Active only when a non-blank publish URL is configured.
         * Moved here from the dashboard artifact so that any peer app carrying only the
         * {@code failover-spring-boot-starter} (no dashboard dependency) can push snapshots to a
         * centralised dashboard host.
         *
         * <p>Auth priority (publisher side):
         * <ol>
         *   <li>OAuth2 Bearer — when {@code oauth2-client-registration-id} is set and
         *       {@code spring-security-oauth2-client} is on the classpath.</li>
         *   <li>HTTP Basic — when {@code username} + {@code password} are set.</li>
         *   <li>No auth — insecure; warn unless {@code allow-insecure-ingest=true}.</li>
         * </ol>
         */
        @Bean(destroyMethod = "close")
        @ConditionalOnProperty(prefix = "failover.dashboard.cluster.snapshot", name = "publish-url")
        @ConditionalOnMissingBean(ClusterSnapshotPublisher.class)
        public ClusterSnapshotPublisher clusterSnapshotPublisher(
                FailoverMetricsSnapshotService metricsSnapshotService,
                InstanceIdResolver instanceIdResolver,
                FailoverClusterPublisherProperties publisherProperties,
                @Qualifier("failoverSnapshotOAuth2Interceptor")
                ObjectProvider<ClientHttpRequestInterceptor> oauth2Interceptor) {
            RestClient client = buildPublisherClient(publisherProperties, oauth2Interceptor.getIfAvailable());
            return new ClusterSnapshotPublisher(metricsSnapshotService, instanceIdResolver,
                    publisherProperties.publishUrl(), publisherProperties.intervalSeconds(), client);
        }

        private static RestClient buildPublisherClient(FailoverClusterPublisherProperties props,
                                                       ClientHttpRequestInterceptor oauth2Interceptor) {
            if (oauth2Interceptor != null) {
                return RestClient.builder().requestInterceptor(oauth2Interceptor).build();
            }
            String username = props.username();
            if (username != null && !username.isBlank()) {
                log.info("Failover snapshot publisher using HTTP Basic Auth (user: '{}').", username);
                String creds = Base64.getEncoder().encodeToString(
                        (username + ":" + props.password()).getBytes(StandardCharsets.UTF_8));
                return RestClient.builder()
                        .requestInterceptor((req, body, exec) -> {
                            req.getHeaders().set("Authorization", "Basic " + creds);
                            return exec.execute(req, body);
                        })
                        .build();
            }
            if (!props.allowInsecureIngest()) {
                log.warn("Failover snapshot publisher '{}' has no auth configured — posting without credentials "
                        + "(insecure). Set snapshot.username+password, snapshot.oauth2-client-registration-id, "
                        + "or snapshot.allow-insecure-ingest=true to suppress this warning.", props.publishUrl());
            }
            return RestClient.create();
        }

        /**
         * Wires an OAuth2 {@link ClientHttpRequestInterceptor} for the snapshot publisher when the consumer's
         * application already has {@code spring-security-oauth2-client} on the classpath. The interceptor
         * obtains a Bearer token from the consumer's existing {@code OAuth2AuthorizedClientManager} on every
         * push — token refresh is handled automatically by Spring Security. Activated only when
         * {@code snapshot.oauth2-client-registration-id} is set.
         *
         * <p>Isolated in its own inner class so the OAuth2 client API is never loaded when the module is absent.
         */
        @Configuration(proxyBeanMethods = false)
        @ConditionalOnClass(name = "org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager")
        @Slf4j
        static class OAuth2PublisherConfiguration {

            @Bean("failoverSnapshotOAuth2Interceptor")
            @ConditionalOnProperty(prefix = "failover.dashboard.cluster.snapshot", name = "oauth2-client-registration-id")
            @ConditionalOnMissingBean(name = "failoverSnapshotOAuth2Interceptor")
            ClientHttpRequestInterceptor failoverSnapshotOAuth2Interceptor(
                    org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager manager,
                    FailoverClusterPublisherProperties props) {
                String registrationId = props.oauth2ClientRegistrationId();
                log.info("Failover snapshot publisher using OAuth2 Bearer auth (registration-id: '{}').", registrationId);
                return (req, body, execution) -> {
                    var authorizeReq = org.springframework.security.oauth2.client.OAuth2AuthorizeRequest
                            .withClientRegistrationId(registrationId)
                            .principal("failover-snapshot-publisher")
                            .build();
                    var authorizedClient = manager.authorize(authorizeReq);
                    if (authorizedClient != null) {
                        req.getHeaders().setBearerAuth(authorizedClient.getAccessToken().getTokenValue());
                    }
                    return execution.execute(req, body);
                };
            }
        }
    }
}
