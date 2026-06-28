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

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.scanner.FailoverScanner;
import com.societegenerale.failover.dashboard.metrics.ConfigEntry;
import com.societegenerale.failover.dashboard.metrics.source.MetricsSource;
import com.societegenerale.failover.observable.metrics.ApiHealth;
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import com.societegenerale.failover.observable.metrics.SeriesPoint;
import com.societegenerale.failover.observable.metrics.SourceInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class DashboardConfigServiceTest {

    private final FailoverScanner scanner = mock(FailoverScanner.class);

    /** Real {@code @Failover} fixtures — read off methods rather than mocking the annotation. */
    @SuppressWarnings("unused")
    static class Fixtures {
        @Failover(name = "country-by-code", domain = "country", expiryDuration = 24)
        void countryByCode() { /* fixture: only the @Failover annotation is read reflectively */ }

        @Failover(name = "alpha")
        void alpha() { /* fixture: only the @Failover annotation is read reflectively */ }

        @Failover(name = "zebra")
        void zebra() { /* fixture: only the @Failover annotation is read reflectively */ }
    }

    private static Failover annotation(String methodName) {
        Method m = Arrays.stream(Fixtures.class.getDeclaredMethods())
                .filter(it -> it.getName().equals(methodName)).findFirst().orElseThrow();
        return m.getAnnotation(Failover.class);
    }

    private DashboardConfigService serviceWith(MockEnvironment env) {
        return new DashboardConfigService(scanner, env);
    }

    private DashboardConfigService clusterServiceWith(MockEnvironment env, MetricsSource metricsSource) {
        return new DashboardConfigService(scanner, env, metricsSource);
    }

    /** Minimal stub MetricsSource that returns a fixed ApiHealth list. */
    private static MetricsSource stubSourceWithHealth(List<ApiHealth> apiHealthList) {
        return new MetricsSource() {
            public MetricsSummary summary() { return null; }
            public List<ApiHealth> health() { return apiHealthList; }
            public SourceInfo info() { return null; }
            public List<SeriesPoint> series(long w) { return List.of(); }
        };
    }

    @Test
    @DisplayName("maps each @Failover to a ConfigEntry and echoes global settings from the environment")
    void mapsAnnotationsAndGlobals() {
        when(scanner.findAllFailover()).thenReturn(List.of(annotation("countryByCode")));
        MockEnvironment env = new MockEnvironment()
                .withProperty("failover.store.type", "jdbc")
                .withProperty("failover.type", "resilience")
                .withProperty("failover.exception-policy", "never_throw")
                .withProperty("failover.store.async", "false");

        ConfigEntry e = serviceWith(env).configEntries().getFirst();

        assertThat(e.name()).isEqualTo("country-by-code");
        assertThat(e.domain()).isEqualTo("country");
        assertThat(e.expiryDuration()).isEqualTo(24L);
        assertThat(e.expiryUnit()).isEqualTo("HOURS");
        assertThat(e.storeType()).isEqualTo("jdbc");
        assertThat(e.executionType()).isEqualTo("resilience");
        assertThat(e.exceptionPolicy()).isEqualTo("never_throw");
        assertThat(e.asyncStore()).isFalse();
    }

    @Test
    @DisplayName("empty per-annotation overrides render as 'default'")
    void emptyOverridesBecomeDefault() {
        when(scanner.findAllFailover()).thenReturn(List.of(annotation("alpha")));

        ConfigEntry e = serviceWith(new MockEnvironment()).configEntries().getFirst();

        assertThat(e.domain()).isEqualTo("default");
        assertThat(e.keyGenerator()).isEqualTo("default");
        assertThat(e.payloadSplitter()).isEqualTo("default");
        assertThat(e.expiryPolicy()).isEqualTo("default");
    }

    @Test
    @DisplayName("global defaults applied when environment has no failover.* keys")
    void globalDefaults() {
        when(scanner.findAllFailover()).thenReturn(List.of(annotation("alpha")));

        ConfigEntry e = serviceWith(new MockEnvironment()).configEntries().getFirst();

        assertThat(e.storeType()).isEqualTo("inmemory");
        assertThat(e.executionType()).isEqualTo("basic");
        assertThat(e.exceptionPolicy()).isEqualTo("rethrow");
        assertThat(e.asyncStore()).isTrue();
    }

    @Test
    @DisplayName("entries are sorted by name")
    void sortedByName() {
        when(scanner.findAllFailover()).thenReturn(List.of(annotation("zebra"), annotation("alpha")));

        List<ConfigEntry> entries = serviceWith(new MockEnvironment()).configEntries();

        assertThat(entries).extracting(ConfigEntry::name).containsExactly("alpha", "zebra");
    }

    @Test
    @DisplayName("failoverHealth() is UP with registered failovers and echoes config from the environment")
    void failoverHealthUp() {
        when(scanner.findAllFailover()).thenReturn(List.of(annotation("alpha"), annotation("zebra")));
        MockEnvironment env = new MockEnvironment()
                .withProperty("failover.store.type", "JDBC")
                .withProperty("failover.store.jdbc.table-prefix", "MYAPP_");

        var health = serviceWith(env).failoverHealth();

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
    @DisplayName("failoverHealth() is DOWN when no @Failover is registered")
    void failoverHealthDown() {
        when(scanner.findAllFailover()).thenReturn(List.of());

        var health = serviceWith(new MockEnvironment()).failoverHealth();

        assertThat(health.status()).isEqualTo("DOWN");
        assertThat(health.details()).containsEntry("registered-failovers", "0");
    }

    @Test
    @DisplayName("failoverHealth() uses recovery rates when MetricsSource has health data — UP when none UNHEALTHY")
    void failoverHealthUsesRecoveryRatesWhenPresent() {
        MetricsSource source = stubSourceWithHealth(List.of(
                new ApiHealth("country", "HEALTHY", 0.98),
                new ApiHealth("city", "DEGRADED", 0.92)));

        var health = clusterServiceWith(new MockEnvironment(), source).failoverHealth();

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
        MetricsSource source = stubSourceWithHealth(List.of(
                new ApiHealth("country", "HEALTHY", 0.99),
                new ApiHealth("city", "UNHEALTHY", 0.60)));

        var health = clusterServiceWith(new MockEnvironment(), source).failoverHealth();

        assertThat(health.status()).isEqualTo("DOWN");
        assertThat(health.details().get("city")).startsWith("UNHEALTHY");
    }

    @Test
    @DisplayName("failoverHealth() in cluster cold-start (no calls yet) returns UP with note")
    void failoverHealthClusterColdStart() {
        MetricsSource source = stubSourceWithHealth(List.of());  // no calls yet
        MockEnvironment env = new MockEnvironment()
                .withProperty("failover.dashboard.cluster.mode", "shared-store");

        var health = clusterServiceWith(env, source).failoverHealth();

        assertThat(health.status()).isEqualTo("UP");
        assertThat(health.details()).containsEntry("note", "No calls recorded yet");
    }

    @Test
    @DisplayName("failoverHealth() falls back to scanner in local mode when no health data yet")
    void failoverHealthFallsBackToScannerInLocalMode() {
        MetricsSource source = stubSourceWithHealth(List.of());  // no calls yet
        when(scanner.findAllFailover()).thenReturn(List.of(annotation("alpha")));

        var health = clusterServiceWith(new MockEnvironment(), source).failoverHealth();

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

        var settings = serviceWith(env).globalSettings();

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
