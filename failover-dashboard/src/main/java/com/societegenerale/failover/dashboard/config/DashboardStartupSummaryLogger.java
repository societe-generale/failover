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

import com.societegenerale.failover.dashboard.metrics.source.MetricsSource;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.HeartbeatStore;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.SnapshotStore;
import com.societegenerale.failover.dashboard.web.ClusterHeartbeatController;
import com.societegenerale.failover.dashboard.web.ClusterSnapshotController;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;

/**
 * Logs a single consolidated INFO summary of the dashboard's own configuration at startup (on
 * {@link ApplicationReadyEvent}), mirroring {@code FailoverStartupSummaryLogger} on the peer side. The
 * dashboard already logs scattered per-bean lines as it wires up (base path, security posture, cluster
 * mode); this adds one place that also cross-checks <em>configured</em> against <em>actually wired</em> —
 * {@code @ConditionalOnBean} failures (a missing {@code DataSource}, the JDBC module not on the classpath)
 * are otherwise silent, so a {@code store=jdbc} typo or forgotten dependency shows up nowhere except a
 * fallback-to-local metrics source nobody expected.
 *
 * @author Anand Manissery
 */
@Slf4j
@RequiredArgsConstructor
public class DashboardStartupSummaryLogger {

    private final DashboardProperties properties;
    private final ApplicationContext applicationContext;

    /** Logs the startup configuration summary once the application context is fully ready. */
    @EventListener(ApplicationReadyEvent.class)
    public void logSummary() {
        log.info("{}", buildSummary());
    }

    String buildSummary() {
        var sb = new StringBuilder("Failover dashboard startup configuration summary:");

        sb.append("\n  [dashboard]");
        sb.append("\n  base-path      : ").append(properties.basePath());
        sb.append("\n  exposure       : ui=").append(properties.exposure().ui())
          .append(", api=").append(properties.exposure().api())
          .append(", include=").append(properties.exposure().include());
        sb.append("\n  security       : ").append(detectSecurity());
        sb.append("\n  metrics-source : ").append(detectMetricsSource());

        String mode = properties.cluster().mode();
        sb.append("\n  cluster-mode   : ").append(mode);
        if ("shared-store".equalsIgnoreCase(mode)) {
            appendSharedStore(sb);
        } else if ("prometheus".equalsIgnoreCase(mode)) {
            appendPrometheus(sb);
        }

        return sb.toString();
    }

    private String detectSecurity() {
        var security = properties.security();
        var sb = new StringBuilder("type=").append(security.type());
        switch (security.type()) {
            case ROLE -> sb.append(", role='").append(security.role()).append("'");
            case AUTHORITY -> sb.append(", authority='").append(security.authority()).append("'");
            case EXPRESSION -> sb.append(", expression='").append(security.expression()).append("'");
        }
        if (!security.oauth2ClientRegistrationId().isBlank()) {
            sb.append(", oauth2-login='").append(security.oauth2ClientRegistrationId()).append("'");
        }
        if (security.oauth2ResourceServer()) {
            sb.append(", oauth2-resource-server=true");
        }
        if (security.allowInsecure()) {
            sb.append(" — INSECURE (allow-insecure=true; dev/local only, refused under 'prod')");
        }
        return sb.toString();
    }

    private String detectMetricsSource() {
        String[] names = applicationContext.getBeanNamesForType(MetricsSource.class);
        if (names.length == 0) {
            return "none";
        }
        return applicationContext.getBean(names[0]).getClass().getSimpleName();
    }

    private void appendSharedStore(StringBuilder sb) {
        var sharedStore = properties.cluster().sharedStore();
        sb.append("\n  shared-store   : store=").append(sharedStore.store())
          .append(", max-instances=").append(sharedStore.maxInstances());
        if (applicationContext.getBeanNamesForType(SnapshotStore.class).length == 0) {
            sb.append(" — NOT WIRED (check the DataSource bean and, for store=jdbc, that "
                    + "failover-dashboard-snapshotstore-jdbc is on the classpath)");
        }

        boolean ingestEnabled = properties.cluster().snapshot().ingest().enabled();
        boolean ingestWired = applicationContext.getBeanNamesForType(ClusterSnapshotController.class).length > 0;
        sb.append("\n  ingest-endpoint: ").append(ingestEnabled ? "enabled" : "disabled (JDBC-direct peers expected)");
        if (ingestEnabled && !ingestWired) {
            sb.append(" — NOT MAPPED (no SnapshotStore bean; see shared-store line above)");
        }

        boolean livenessEnabled = sharedStore.liveness().enabled();
        sb.append("\n  liveness       : ").append(livenessEnabled ? "enabled" : "disabled");
        if (livenessEnabled) {
            sb.append(", liveness-seconds=").append(sharedStore.livenessSeconds());
            if (applicationContext.getBeanNamesForType(HeartbeatStore.class).length == 0) {
                sb.append(" — NOT WIRED");
            } else if (ingestEnabled && applicationContext.getBeanNamesForType(ClusterHeartbeatController.class).length == 0) {
                sb.append(" — heartbeat ingest NOT MAPPED");
            }
        }
    }

    private void appendPrometheus(StringBuilder sb) {
        var prometheus = properties.cluster().prometheus();
        String baseUrl = prometheus.baseUrl();
        sb.append("\n  prometheus     : base-url='")
          .append(baseUrl.isBlank() ? "(none — falls back to local)" : baseUrl)
          .append("'");
    }
}
