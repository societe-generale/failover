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

import com.societegenerale.failover.dashboard.metrics.source.MetricsSource;
import com.societegenerale.failover.observable.metrics.ApiHealth;
import com.societegenerale.failover.observable.metrics.ConfigEntry;
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import com.societegenerale.failover.observable.metrics.SeriesPoint;
import com.societegenerale.failover.observable.metrics.SourceInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link DashboardConfigService} no longer reads a live {@code FailoverScanner} — it is a thin pass-through
 * over {@link MetricsSource#configEntries()} (mapping annotations to {@link ConfigEntry} is
 * {@code FailoverConfigSnapshotService}'s job, covered in {@code failover-observable-metrics}) plus the
 * {@code Environment}-derived global settings / health fallback.
 */
class DashboardConfigServiceTest {

    private static ConfigEntry entry(String name) {
        return new ConfigEntry(name, "default", 24L, "HOURS", false,
                "default", "default", "default", "inmemory", "basic", "rethrow", true);
    }

    /** Minimal stub MetricsSource with fixed config/health data. */
    private static MetricsSource stubSource(List<ConfigEntry> configEntries, List<ApiHealth> apiHealthList) {
        return new MetricsSource() {
            public MetricsSummary summary() { return null; }
            public List<ApiHealth> health() { return apiHealthList; }
            public SourceInfo info() { return null; }
            public List<SeriesPoint> series(long w) { return List.of(); }
            public List<ConfigEntry> configEntries() { return configEntries; }
        };
    }

    @Test
    @DisplayName("configEntries() delegates to the MetricsSource, unmodified")
    void configEntriesDelegatesToMetricsSource() {
        List<ConfigEntry> entries = List.of(entry("zebra"), entry("alpha"));
        DashboardConfigService service = new DashboardConfigService(new MockEnvironment(),
                stubSource(entries, List.of()));

        assertThat(service.configEntries()).isEqualTo(entries);
    }

    @Test
    @DisplayName("configEntries() is empty when no MetricsSource is wired")
    void configEntriesEmptyWithoutMetricsSource() {
        DashboardConfigService service = new DashboardConfigService(new MockEnvironment());

        assertThat(service.configEntries()).isEmpty();
    }

    @Test
    @DisplayName("failoverHealth() is UP with registered failovers and echoes config from the environment")
    void failoverHealthUp() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("failover.store.type", "JDBC")
                .withProperty("failover.store.jdbc.table-prefix", "MYAPP_");
        DashboardConfigService service = new DashboardConfigService(env,
                stubSource(List.of(entry("alpha"), entry("zebra")), List.of()));

        var health = service.failoverHealth();

        assertThat(health.status()).isEqualTo("UP");
        assertThat(health.details())
                .containsEntry("registered-failovers", "2")
                .containsEntry("enabled", "true")
                .containsEntry("type", "BASIC")
                .containsEntry("store.type", "JDBC")
                .containsEntry("store.jdbc.table-prefix", "MYAPP_")
                .containsEntry("scheduler.enabled", "true");
    }

    @Test
    @DisplayName("failoverHealth() falls back to the environment-only view when no MetricsSource is wired at all")
    void failoverHealthWithoutMetricsSource() {
        DashboardConfigService service = new DashboardConfigService(new MockEnvironment());

        var health = service.failoverHealth();

        assertThat(health.status()).isEqualTo("DOWN");
        assertThat(health.details()).containsEntry("registered-failovers", "0");
    }

    @Test
    @DisplayName("failoverHealth() is DOWN when no @Failover is registered")
    void failoverHealthDown() {
        DashboardConfigService service = new DashboardConfigService(new MockEnvironment(),
                stubSource(List.of(), List.of()));

        var health = service.failoverHealth();

        assertThat(health.status()).isEqualTo("DOWN");
        assertThat(health.details()).containsEntry("registered-failovers", "0");
    }

    @Test
    @DisplayName("failoverHealth() uses recovery rates when MetricsSource has health data — UP when none UNHEALTHY")
    void failoverHealthUsesRecoveryRatesWhenPresent() {
        MetricsSource source = stubSource(List.of(), List.of(
                new ApiHealth("country", "HEALTHY", 0.98),
                new ApiHealth("city", "DEGRADED", 0.92)));

        var health = new DashboardConfigService(new MockEnvironment(), source).failoverHealth();

        assertThat(health.status()).isEqualTo("UP");
        assertThat(health.details())
                .containsEntry("endpoints", "2")
                .containsKey("country")
                .containsKey("city");
        assertThat(health.details().get("country")).startsWith("HEALTHY");
        assertThat(health.details().get("city")).startsWith("DEGRADED");
    }

    @Test
    @DisplayName("failoverHealth() is DOWN when any endpoint is UNHEALTHY")
    void failoverHealthDownWhenAnyEndpointUnhealthy() {
        MetricsSource source = stubSource(List.of(), List.of(
                new ApiHealth("country", "HEALTHY", 0.99),
                new ApiHealth("city", "UNHEALTHY", 0.60)));

        var health = new DashboardConfigService(new MockEnvironment(), source).failoverHealth();

        assertThat(health.status()).isEqualTo("DOWN");
        assertThat(health.details().get("city")).startsWith("UNHEALTHY");
    }

    @Test
    @DisplayName("failoverHealth() in cluster cold-start (no calls yet) returns UP with note")
    void failoverHealthClusterColdStart() {
        MetricsSource source = stubSource(List.of(), List.of());  // no calls yet
        MockEnvironment env = new MockEnvironment()
                .withProperty("failover.dashboard.cluster.mode", "shared-store");

        var health = new DashboardConfigService(env, source).failoverHealth();

        assertThat(health.status()).isEqualTo("UP");
        assertThat(health.details()).containsEntry("note", "No calls recorded yet");
    }

    @Test
    @DisplayName("failoverHealth() falls back to the configEntries() count in local mode when no health data yet")
    void failoverHealthFallsBackToConfigEntriesCountInLocalMode() {
        MetricsSource source = stubSource(List.of(entry("alpha")), List.of());  // no calls yet

        var health = new DashboardConfigService(new MockEnvironment(), source).failoverHealth();

        assertThat(health.status()).isEqualTo("UP");
        assertThat(health.details()).containsEntry("registered-failovers", "1");
    }

    @Test
    @DisplayName("globalSettings() groups effective config, applying defaults and environment overrides")
    void globalSettingsGroupsAndOverrides() {
        MockEnvironment env = new MockEnvironment()
                .withProperty("failover.type", "resilience")                              // override
                .withProperty("failover.store.async-executor.concurrency-limit", "256")   // override
                .withProperty("failover.dashboard.history.enabled", "true");             // override

        var settings = new DashboardConfigService(env).globalSettings();

        assertThat(settings).containsOnlyKeys("Core", "Store", "Scheduler", "Scatter", "Dashboard");
        assertThat(settings.get("Core"))
                .containsEntry("failover.enabled", "true")           // default applied
                .containsEntry("failover.type", "resilience");       // override honoured
        assertThat(settings.get("Store"))
                .containsEntry("failover.store.type", "inmemory")    // default applied
                .containsEntry("failover.store.async-executor.concurrency-limit", "256")
                .containsEntry("failover.store.async-executor.rejection-policy", "DISCARD");
        assertThat(settings.get("Scatter"))
                .containsEntry("failover.scatter.parallel", "true")
                .containsEntry("failover.scatter.rejection-policy", "DISCARD");
        assertThat(settings.get("Dashboard"))
                .containsEntry("failover.dashboard.history.enabled", "true")
                .containsEntry("failover.dashboard.security.role", "FAILOVER_ADMIN");
    }
}
