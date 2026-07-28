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

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.FailoverExecution;
import com.societegenerale.failover.core.scanner.FailoverScanner;
import com.societegenerale.failover.observable.micrometer.ClusterSnapshotPublisher;
import com.societegenerale.failover.observable.micrometer.HeartbeatPublisher;
import com.societegenerale.failover.observable.micrometer.MicrometerObservablePublisher;
import com.societegenerale.failover.properties.FailoverProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.ApplicationContext;
import org.springframework.context.event.EventListener;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import static com.societegenerale.failover.core.util.CommonsUtil.format;
import static com.societegenerale.failover.core.util.FailoverUtil.summary;

/**
 * Logs a single consolidated INFO summary of all failover infrastructure and per-endpoint
 * configuration at application startup (on {@link ApplicationReadyEvent}, after the
 * {@link com.societegenerale.failover.scanner.SpringContextFailoverScanner} has finished).
 *
 * @author Anand Manissery
 */
@Slf4j
@RequiredArgsConstructor
public class FailoverStartupSummaryLogger {

    private static final String SNAPSHOT_STORE_CLASS =
            "com.societegenerale.failover.dashboard.metrics.source.sharedstore.SnapshotStore";

    private final FailoverProperties properties;
    private final ApplicationContext applicationContext;
    private final ObjectProvider<FailoverScanner> scannerProvider;
    private final ObjectProvider<FailoverClusterPublisherProperties> clusterPublisherPropertiesProvider;

    /** Logs the startup configuration summary once the application context is fully ready. */
    @EventListener(ApplicationReadyEvent.class)
    public void logSummary() {
        log.info("{}", buildSummary());
    }

    String buildSummary() {
        var sb = new StringBuilder("Failover startup configuration summary:");

        var store   = properties.getStore();
        var scatter = properties.getScatter();

        sb.append("\n  [infrastructure]");
        sb.append("\n  enabled          : ").append(properties.isEnabled());
        sb.append("\n  execution        : ").append(detectExecution());
        sb.append("\n  exception-policy : ").append(properties.getExceptionPolicy());

        // store — base line
        sb.append("\n  store            : ").append(store.getType())
          .append(", async=").append(store.isAsync())
          .append(", multitenant=").append(store.getMultitenant().isEnabled());
        // store — type-specific details
        switch (store.getType()) {
            case JDBC -> {
                String prefix = store.getJdbc().getTablePrefix();
                sb.append(" [table-prefix='").append(prefix.isBlank() ? "(none)" : prefix).append("'");
                if (store.getMultitenant().isEnabled()) {
                    sb.append(", strategy=").append(store.getMultitenant().getStrategy());
                }
                sb.append("]");
            }
            case CAFFEINE -> sb.append(" [max-size=").append(store.getCaffeine().getMaxSize()).append("]");
            default -> {
                if (store.getMultitenant().isEnabled()) {
                    sb.append(" [strategy=").append(store.getMultitenant().getStrategy()).append("]");
                }
            }
        }
        // store async executor — only if bounded
        if (store.isAsync() && store.getAsyncExecutor().getConcurrencyLimit() > 0) {
            sb.append("\n  store-executor   : bounded, concurrencyLimit=")
              .append(store.getAsyncExecutor().getConcurrencyLimit())
              .append(", rejectionPolicy=").append(store.getAsyncExecutor().getRejectionPolicy());
        }

        // scatter
        sb.append("\n  scatter          : parallel=").append(scatter.isParallel())
          .append(", timeout=").append(format(scatter.getTimeout()));
        if (scatter.getConcurrencyLimit() > 0) {
            sb.append(", concurrencyLimit=").append(scatter.getConcurrencyLimit())
              .append(", rejectionPolicy=").append(scatter.getRejectionPolicy());
        }

        sb.append("\n  publisher        : ").append(detectPublisher());
        sb.append("\n  snapshot-store   : ").append(detectSnapshotStore());
        sb.append("\n  cluster-publisher: ").append(detectClusterPublisher());

        FailoverScanner scanner = scannerProvider.getIfAvailable();
        if (scanner != null) {
            List<Failover> failovers = scanner.findAllFailover();
            sb.append("\n\n  [failover endpoints] (").append(failovers.size()).append(")");
            failovers.stream()
                    .sorted(Comparator.comparing(Failover::name))
                    .forEach(f -> sb.append("\n  ").append(summary(f)));
        }

        return sb.toString();
    }

    private String detectExecution() {
        String[] names = applicationContext.getBeanNamesForType(FailoverExecution.class);
        if (names.length > 0) {
            return applicationContext.getBean(names[0]).getClass().getSimpleName();
        }
        return "unknown";
    }

    private String detectPublisher() {
        List<String> publishers = new ArrayList<>();
        publishers.add("MdcLogger");
        if (applicationContext.getBeanNamesForType(MicrometerObservablePublisher.class).length > 0) {
            publishers.add("Micrometer");
        }
        var async = properties.getObservable().getAsync();
        String suffix = async.isEnabled()
                ? " (async, queue=" + async.getQueueCapacity() + ")"
                : " (sync)";
        return String.join(" + ", publishers) + suffix;
    }

    private String detectSnapshotStore() {
        try {
            Class<?> snapshotStoreType = Class.forName(SNAPSHOT_STORE_CLASS);
            String[] names = applicationContext.getBeanNamesForType(snapshotStoreType);
            if (names.length > 0) {
                return applicationContext.getBean(names[0]).getClass().getSimpleName();
            }
            return "none";
        } catch (ClassNotFoundException e) {
            return "n/a";
        }
    }

    /**
     * Reports this instance's shared-store peer transport (Scenario C/D via {@code publish-url}, or D2's
     * JDBC-direct via {@code snapshot.jdbc.enabled}) and whether it actually wired up. A configured transport
     * that fails to wire (missing {@code DataSource}, missing {@code spring-web}, etc.) is otherwise
     * completely silent — {@code @ConditionalOnBean} just no-ops — so this is the one place that surfaces it.
     */
    private String detectClusterPublisher() {
        FailoverClusterPublisherProperties props = clusterPublisherPropertiesProvider.getIfAvailable();
        if (props == null) {
            return "none";
        }
        boolean jdbcConfigured = props.jdbc().enabled();
        boolean httpConfigured = props.publishUrl() != null && !props.publishUrl().isBlank();
        if (!jdbcConfigured && !httpConfigured) {
            return "none";
        }

        String transport = jdbcConfigured
                ? "jdbc-direct [table-prefix='" + (props.jdbc().tablePrefix().isBlank() ? "(none)" : props.jdbc().tablePrefix()) + "']"
                : "http -> " + props.publishUrl();
        var sb = new StringBuilder(transport);
        if (applicationContext.getBeanNamesForType(ClusterSnapshotPublisher.class).length == 0) {
            sb.append(" — NOT WIRED (check ").append(jdbcConfigured ? "for a DataSource bean" : "for spring-web on the classpath").append(")");
        }
        if (props.heartbeat().enabled()) {
            sb.append(", heartbeat=on");
            if (applicationContext.getBeanNamesForType(HeartbeatPublisher.class).length == 0) {
                sb.append(" — NOT WIRED");
            }
        }
        return sb.toString();
    }
}
