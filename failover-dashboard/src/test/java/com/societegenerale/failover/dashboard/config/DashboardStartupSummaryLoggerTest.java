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

import com.societegenerale.failover.dashboard.metrics.source.LocalRegistryMetricsSource;
import com.societegenerale.failover.dashboard.metrics.source.MetricsSource;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.HeartbeatStore;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.SnapshotStore;
import com.societegenerale.failover.dashboard.web.ClusterHeartbeatController;
import com.societegenerale.failover.dashboard.web.ClusterSnapshotController;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationContext;

import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class DashboardStartupSummaryLoggerTest {

    @Mock ApplicationContext applicationContext;

    @BeforeEach
    void defaultBeanLookups() {
        lenient().when(applicationContext.getBeanNamesForType(MetricsSource.class)).thenReturn(new String[]{});
        lenient().when(applicationContext.getBeanNamesForType(SnapshotStore.class)).thenReturn(new String[]{});
        lenient().when(applicationContext.getBeanNamesForType(HeartbeatStore.class)).thenReturn(new String[]{});
        lenient().when(applicationContext.getBeanNamesForType(ClusterSnapshotController.class)).thenReturn(new String[]{});
        lenient().when(applicationContext.getBeanNamesForType(ClusterHeartbeatController.class)).thenReturn(new String[]{});
    }

    // ── builders ─────────────────────────────────────────────────────────────

    private static DashboardProperties.Exposure exposure() {
        return new DashboardProperties.Exposure(true, true,
                List.of("config", "failover-health", "metrics", "health", "cluster", "instances"));
    }

    private static DashboardProperties.Security security(DashboardProperties.SecurityType type, boolean allowInsecure) {
        return new DashboardProperties.Security(type, "FAILOVER_ADMIN", "FAILOVER_ADMIN", "hasRole('X')",
                allowInsecure, "", false);
    }

    private static DashboardProperties localMode(DashboardProperties.Security security) {
        return new DashboardProperties(true, "/failover-dashboard", exposure(), security,
                new DashboardProperties.History(false, 120, 15), new DashboardProperties.Health(0.99, 0.90, 100),
                new DashboardProperties.Cluster("local"));
    }

    private static DashboardProperties sharedStoreMode(String store, boolean ingestEnabled, boolean livenessEnabled) {
        var sharedStore = new DashboardProperties.SharedStore(store, 180, 10, Duration.ofDays(7),
                new DashboardProperties.Retention(), 30, new DashboardProperties.Jdbc(""),
                new DashboardProperties.Liveness(livenessEnabled));
        var snapshot = new DashboardProperties.Snapshot("", 15, "", "", "", false,
                new DashboardProperties.Ingest(ingestEnabled));
        var cluster = new DashboardProperties.Cluster("shared-store", new DashboardProperties.Prometheus("", "", 5),
                sharedStore, snapshot);
        return new DashboardProperties(true, "/failover-dashboard", exposure(), security(DashboardProperties.SecurityType.AUTHORITY, false),
                new DashboardProperties.History(false, 120, 15), new DashboardProperties.Health(0.99, 0.90, 100), cluster);
    }

    private static DashboardProperties prometheusMode(String baseUrl) {
        var cluster = new DashboardProperties.Cluster("prometheus", new DashboardProperties.Prometheus(baseUrl, "", 5),
                new DashboardProperties.SharedStore(), new DashboardProperties.Snapshot());
        return new DashboardProperties(true, "/failover-dashboard", exposure(), security(DashboardProperties.SecurityType.AUTHORITY, false),
                new DashboardProperties.History(false, 120, 15), new DashboardProperties.Health(0.99, 0.90, 100), cluster);
    }

    // ── dashboard-level fields ──────────────────────────────────────────────

    @Nested
    @DisplayName("buildSummary — dashboard-level fields")
    class DashboardLevel {

        @Test
        @DisplayName("base-path and exposure are echoed verbatim")
        void basePathAndExposure() {
            var logger = new DashboardStartupSummaryLogger(localMode(security(DashboardProperties.SecurityType.AUTHORITY, false)), applicationContext);
            assertThat(logger.buildSummary())
                    .contains("base-path      : /failover-dashboard")
                    .contains("exposure       : ui=true, api=true, include=[config, failover-health, metrics, health, cluster, instances]");
        }

        @Test
        @DisplayName("security.type=AUTHORITY — shows authority, no INSECURE suffix")
        void authoritySecurity() {
            var logger = new DashboardStartupSummaryLogger(localMode(security(DashboardProperties.SecurityType.AUTHORITY, false)), applicationContext);
            assertThat(logger.buildSummary())
                    .contains("security       : type=AUTHORITY, authority='FAILOVER_ADMIN'")
                    .doesNotContain("INSECURE");
        }

        @Test
        @DisplayName("security.type=ROLE — shows role")
        void roleSecurity() {
            var logger = new DashboardStartupSummaryLogger(localMode(security(DashboardProperties.SecurityType.ROLE, false)), applicationContext);
            assertThat(logger.buildSummary()).contains("type=ROLE, role='FAILOVER_ADMIN'");
        }

        @Test
        @DisplayName("security.type=EXPRESSION — shows expression")
        void expressionSecurity() {
            var logger = new DashboardStartupSummaryLogger(localMode(security(DashboardProperties.SecurityType.EXPRESSION, false)), applicationContext);
            assertThat(logger.buildSummary()).contains("type=EXPRESSION, expression='hasRole('X')'");
        }

        @Test
        @DisplayName("allow-insecure=true — flagged loudly")
        void allowInsecureFlagged() {
            var logger = new DashboardStartupSummaryLogger(localMode(security(DashboardProperties.SecurityType.AUTHORITY, true)), applicationContext);
            assertThat(logger.buildSummary())
                    .contains("INSECURE (allow-insecure=true; dev/local only, refused under 'prod')");
        }

        @Test
        @DisplayName("no MetricsSource bean — shows none")
        void noMetricsSource() {
            var logger = new DashboardStartupSummaryLogger(localMode(security(DashboardProperties.SecurityType.AUTHORITY, false)), applicationContext);
            assertThat(logger.buildSummary()).contains("metrics-source : none");
        }

        @Test
        @DisplayName("MetricsSource bean present — shows its simple class name")
        void metricsSourcePresent() {
            when(applicationContext.getBeanNamesForType(MetricsSource.class)).thenReturn(new String[]{"metricsSource"});
            when(applicationContext.getBean("metricsSource")).thenReturn(mock(LocalRegistryMetricsSource.class));
            var logger = new DashboardStartupSummaryLogger(localMode(security(DashboardProperties.SecurityType.AUTHORITY, false)), applicationContext);
            assertThat(logger.buildSummary()).contains("metrics-source : LocalRegistryMetricsSource");
        }

        @Test
        @DisplayName("local mode — no shared-store or prometheus block")
        void localModeNoExtras() {
            var logger = new DashboardStartupSummaryLogger(localMode(security(DashboardProperties.SecurityType.AUTHORITY, false)), applicationContext);
            String summary = logger.buildSummary();
            assertThat(summary).contains("cluster-mode   : local")
                    .doesNotContain("shared-store")
                    .doesNotContain("prometheus");
        }
    }

    // ── shared-store mode ────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildSummary — cluster.mode=shared-store")
    class SharedStoreMode {

        @Test
        @DisplayName("store wired, ingest enabled and mapped, liveness off — no NOT WIRED / NOT MAPPED")
        void fullyWired() {
            when(applicationContext.getBeanNamesForType(SnapshotStore.class)).thenReturn(new String[]{"snapshotStore"});
            when(applicationContext.getBeanNamesForType(ClusterSnapshotController.class)).thenReturn(new String[]{"clusterSnapshotController"});

            var logger = new DashboardStartupSummaryLogger(sharedStoreMode("jdbc", true, false), applicationContext);
            String summary = logger.buildSummary();
            assertThat(summary)
                    .contains("shared-store   : store=jdbc, max-instances=10")
                    .contains("ingest-endpoint: enabled")
                    .contains("liveness       : disabled")
                    .doesNotContain("NOT WIRED")
                    .doesNotContain("NOT MAPPED");
        }

        @Test
        @DisplayName("store=jdbc but SnapshotStore bean absent — flagged NOT WIRED")
        void storeNotWired() {
            var logger = new DashboardStartupSummaryLogger(sharedStoreMode("jdbc", true, false), applicationContext);
            assertThat(logger.buildSummary())
                    .contains("NOT WIRED (check the DataSource bean and, for store=jdbc, that "
                            + "failover-dashboard-snapshotstore-jdbc is on the classpath)");
        }

        @Test
        @DisplayName("ingest disabled (JDBC-direct peers) — shown as disabled, no NOT MAPPED noise")
        void ingestDisabled() {
            when(applicationContext.getBeanNamesForType(SnapshotStore.class)).thenReturn(new String[]{"snapshotStore"});
            var logger = new DashboardStartupSummaryLogger(sharedStoreMode("jdbc", false, false), applicationContext);
            assertThat(logger.buildSummary())
                    .contains("ingest-endpoint: disabled (JDBC-direct peers expected)")
                    .doesNotContain("NOT MAPPED");
        }

        @Test
        @DisplayName("ingest enabled but controller absent (no SnapshotStore) — flagged NOT MAPPED")
        void ingestEnabledButNotMapped() {
            var logger = new DashboardStartupSummaryLogger(sharedStoreMode("inmemory", true, false), applicationContext);
            assertThat(logger.buildSummary())
                    .contains("ingest-endpoint: enabled — NOT MAPPED (no SnapshotStore bean; see shared-store line above)");
        }

        @Test
        @DisplayName("liveness enabled and wired — shows liveness-seconds, no NOT WIRED")
        void livenessWired() {
            when(applicationContext.getBeanNamesForType(SnapshotStore.class)).thenReturn(new String[]{"snapshotStore"});
            when(applicationContext.getBeanNamesForType(ClusterSnapshotController.class)).thenReturn(new String[]{"clusterSnapshotController"});
            when(applicationContext.getBeanNamesForType(HeartbeatStore.class)).thenReturn(new String[]{"heartbeatStore"});
            when(applicationContext.getBeanNamesForType(ClusterHeartbeatController.class)).thenReturn(new String[]{"clusterHeartbeatController"});

            var logger = new DashboardStartupSummaryLogger(sharedStoreMode("jdbc", true, true), applicationContext);
            assertThat(logger.buildSummary())
                    .contains("liveness       : enabled, liveness-seconds=180")
                    .doesNotContain("liveness       : enabled, liveness-seconds=180 — NOT WIRED");
        }

        @Test
        @DisplayName("liveness enabled but HeartbeatStore absent — flagged NOT WIRED")
        void livenessNotWired() {
            var logger = new DashboardStartupSummaryLogger(sharedStoreMode("jdbc", true, true), applicationContext);
            assertThat(logger.buildSummary()).contains("liveness       : enabled, liveness-seconds=180 — NOT WIRED");
        }

        @Test
        @DisplayName("liveness enabled, HeartbeatStore wired but ingest disables the heartbeat controller — flagged")
        void livenessHeartbeatIngestNotMapped() {
            when(applicationContext.getBeanNamesForType(SnapshotStore.class)).thenReturn(new String[]{"snapshotStore"});
            when(applicationContext.getBeanNamesForType(HeartbeatStore.class)).thenReturn(new String[]{"heartbeatStore"});
            // ingest enabled=true in this scenario but the fixture leaves ClusterSnapshotController absent too;
            // what matters here is the heartbeat-controller check specifically:
            when(applicationContext.getBeanNamesForType(ClusterSnapshotController.class)).thenReturn(new String[]{"clusterSnapshotController"});

            var logger = new DashboardStartupSummaryLogger(sharedStoreMode("jdbc", true, true), applicationContext);
            assertThat(logger.buildSummary()).contains("heartbeat ingest NOT MAPPED");
        }
    }

    // ── prometheus mode ──────────────────────────────────────────────────────

    @Nested
    @DisplayName("buildSummary — cluster.mode=prometheus")
    class PrometheusMode {

        @Test
        @DisplayName("base-url set — echoed verbatim")
        void baseUrlSet() {
            var logger = new DashboardStartupSummaryLogger(prometheusMode("http://prometheus:9090"), applicationContext);
            assertThat(logger.buildSummary()).contains("prometheus     : base-url='http://prometheus:9090'");
        }

        @Test
        @DisplayName("base-url blank — notes the local fallback")
        void baseUrlBlank() {
            var logger = new DashboardStartupSummaryLogger(prometheusMode(""), applicationContext);
            assertThat(logger.buildSummary()).contains("prometheus     : base-url='(none — falls back to local)'");
        }
    }
}
