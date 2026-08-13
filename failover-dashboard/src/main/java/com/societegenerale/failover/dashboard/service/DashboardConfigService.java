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

package com.societegenerale.failover.dashboard.service;

import com.societegenerale.failover.dashboard.metrics.FailoverHealth;
import com.societegenerale.failover.dashboard.metrics.source.MetricsSource;
import com.societegenerale.failover.observable.metrics.ApiHealth;
import com.societegenerale.failover.observable.metrics.ConfigEntry;
import org.springframework.core.env.Environment;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Builds the configuration view: one {@link ConfigEntry} per {@code @Failover} point, sourced from the
 * {@link MetricsSource} — never a live {@code FailoverScanner} reference. The entries themselves come from
 * the {@code failover.config.*} gauges the running service emits (see {@code FailoverConfigSnapshotService}),
 * so this module has zero dependency on the scanner.
 *
 * <p>Global settings are read from the {@link Environment} (the {@code failover.*} keys) rather than
 * the typed {@code FailoverProperties} bean, keeping this module decoupled from
 * {@code failover-spring-boot-autoconfigure} (design doc §11). Reads only configuration metadata —
 * never connection details or payload data (§9).
 *
 * @author Anand Manissery
 */
public class DashboardConfigService {

    private final Environment environment;
    private final MetricsSource metricsSource;

    /**
     * Creates a new service with no {@link MetricsSource} (config view stays empty).
     *
     * @param environment source of the {@code failover.*} global settings
     */
    public DashboardConfigService(Environment environment) {
        this(environment, null);
    }

    /**
     * Creates a new service.
     *
     * @param environment   source of the {@code failover.*} global settings
     * @param metricsSource source of the {@code @Failover} config entries; {@code null} if none is wired
     */
    public DashboardConfigService(Environment environment, MetricsSource metricsSource) {
        this.environment = environment;
        this.metricsSource = metricsSource;
    }

    /**
     * Lists every discovered {@code @Failover} configuration entry.
     *
     * @return one {@link ConfigEntry} per discovered {@code @Failover}, sorted by name; never {@code null}.
     */
    public List<ConfigEntry> configEntries() {
        return metricsSource == null ? List.of() : metricsSource.configEntries();
    }

    /**
     * Failover-specific health: overall status derived from per-endpoint recovery rates, not from instance liveness
     * (instance UP/DOWN is already covered by standard app monitoring).
     *
     * <p><strong>Primary path</strong> (when {@link MetricsSource} is wired and calls have been made):
     * status is {@code DOWN} if any endpoint is {@code UNHEALTHY} (recovery rate below the configured
     * unhealthy threshold), {@code UP} otherwise. Details show each endpoint's
     * {@code HEALTHY / DEGRADED / UNHEALTHY} classification and healthy-rate percentage. This works
     * identically in {@code local} and cluster modes — the {@link MetricsSource} already aggregates
     * cluster-wide data when applicable.
     *
     * <p><strong>Cluster cold-start</strong> (cluster mode, no calls recorded yet): returns {@code UP}
     * with a "no calls yet" note rather than a false {@code DOWN}.
     *
     * <p><strong>Fallback</strong> (no {@link MetricsSource} or no calls in local mode): {@code UP} when
     * at least one {@code @Failover} is registered, {@code DOWN} when none are discovered.
     *
     * @return the failover health snapshot
     */
    public FailoverHealth failoverHealth() {
        String clusterMode = environment.getProperty("failover.dashboard.cluster.mode", "local");
        Map<String, String> details = new LinkedHashMap<>();

        if (metricsSource != null) {
            List<ApiHealth> apiHealthList = metricsSource.health();
            if (!apiHealthList.isEmpty()) {
                boolean anyUnhealthy = apiHealthList.stream()
                        .anyMatch(h -> ApiHealth.Status.UNHEALTHY.name().equals(h.status()));
                details.put("endpoints", String.valueOf(apiHealthList.size()));
                for (ApiHealth api : apiHealthList) {
                    details.put(api.name(), api.status() + " (" + String.format("%.0f%%", api.healthyRate() * 100) + ")");
                }
                return new FailoverHealth(anyUnhealthy ? "DOWN" : "UP", details);
            }
            if (!"local".equalsIgnoreCase(clusterMode)) {
                // Cluster mode, no calls recorded yet — don't show DOWN for a cold start
                details.put("cluster.mode", clusterMode);
                details.put("note", "No calls recorded yet");
                return new FailoverHealth("UP", details);
            }
        }

        // Local mode or no MetricsSource: fall back to the discovered-config count
        int registered = configEntries().size();
        details.put("registered-failovers", Integer.toString(registered));
        details.put("enabled", environment.getProperty("failover.enabled", "true"));
        details.put("type", environment.getProperty("failover.type", "BASIC"));
        details.put("exception-policy", environment.getProperty("failover.exception-policy", "RETHROW"));
        details.put("store.type", environment.getProperty("failover.store.type", "INMEMORY"));
        details.put("store.async", environment.getProperty("failover.store.async", "true"));
        details.put("store.jdbc.table-prefix", environment.getProperty("failover.store.jdbc.table-prefix", ""));
        details.put("scheduler.enabled", environment.getProperty("failover.scheduler.enabled", "true"));
        return new FailoverHealth(registered == 0 ? "DOWN" : "UP", details);
    }

    /**
     * Effective global configuration for the framework and the dashboard, grouped for display
     * ({@code Core}, {@code Store}, {@code Scheduler}, {@code Scatter}, {@code Dashboard}). Each value is
     * read from the {@link Environment} with the framework default applied when unset.
     *
     * <p>Only types, flags, crons, thresholds and paths are exposed — never connection strings,
     * credentials, or payload data (design doc §9). Insertion order is preserved for a stable, YAML-like
     * reading order.
     *
     * @return ordered map of {@code group → (property key → effective value)}
     */
    public Map<String, Map<String, String>> globalSettings() {
        Map<String, Map<String, String>> groups = new LinkedHashMap<>();
        groups.put("Core", group(
                "failover.enabled", "true",
                "failover.type", "basic",
                "failover.exception-policy", "rethrow"));
        groups.put("Store", group(
                "failover.store.type", "inmemory",
                "failover.store.async", "true",
                "failover.store.async-executor.concurrency-limit", "0",
                "failover.store.async-executor.rejection-policy", "DISCARD",
                "failover.store.inmemory.max-entries", "10000",
                "failover.store.caffeine.max-size", "10000",
                "failover.store.jdbc.table-prefix", "",
                "failover.store.jdbc.encryption.enabled", "false",
                "failover.store.jdbc.encryption.cipher", "b64",
                "failover.store.multitenant.enabled", "false",
                "failover.store.multitenant.strategy", "TABLE_PREFIX",
                "failover.store.multitenant.default-tenant", "",
                "failover.store.multitenant.strict", "false"));
        groups.put("Scheduler", group(
                "failover.scheduler.enabled", "true",
                "failover.scheduler.report-cron", "0 0 0 * * *",
                "failover.scheduler.cleanup-cron", "0 0 * * * *"));
        groups.put("Scatter", group(
                "failover.scatter.parallel", "true",
                "failover.scatter.timeout", "10s",
                "failover.scatter.concurrency-limit", "0",
                "failover.scatter.rejection-policy", "DISCARD"));
        groups.put("Dashboard", group(
                "failover.dashboard.enabled", "false",
                "failover.dashboard.base-path", "/failover-dashboard",
                "failover.dashboard.exposure.ui", "true",
                "failover.dashboard.exposure.api", "true",
                "failover.dashboard.exposure.include", "config, failover-health, metrics, health",
                "failover.dashboard.security.role", "FAILOVER_ADMIN",
                "failover.dashboard.security.allow-insecure", "false",
                "failover.dashboard.history.enabled", "false",
                "failover.dashboard.history.samples", "120",
                "failover.dashboard.history.sample-interval-seconds", "15",
                "failover.dashboard.health.degraded-threshold", "0.99",
                "failover.dashboard.health.unhealthy-threshold", "0.90"));
        return groups;
    }

    /** Builds an ordered {@code key → effective value} map from {@code (key, default)} pairs. */
    private Map<String, String> group(String... keyDefaultPairs) {
        Map<String, String> entries = new LinkedHashMap<>();
        for (int i = 0; i < keyDefaultPairs.length; i += 2) {
            entries.put(keyDefaultPairs[i], environment.getProperty(keyDefaultPairs[i], keyDefaultPairs[i + 1]));
        }
        return entries;
    }
}
