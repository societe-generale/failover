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

package com.societegenerale.failover.dashboard.metrics.source.sharedstore;

import com.societegenerale.failover.dashboard.config.DashboardProperties;
import com.societegenerale.failover.observable.metrics.ApiHealth;
import com.societegenerale.failover.observable.metrics.ConfigEntry;
import com.societegenerale.failover.observable.metrics.InstanceMetrics;
import com.societegenerale.failover.observable.metrics.LiveStatus;
import com.societegenerale.failover.observable.metrics.MetricsKpis;
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import com.societegenerale.failover.observable.metrics.MetricsSummaryAggregator;
import com.societegenerale.failover.observable.metrics.SeriesPoint;
import com.societegenerale.failover.observable.metrics.SourceInfo;
import com.societegenerale.failover.dashboard.metrics.source.MetricsSource;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Cluster-wide {@link MetricsSource} for {@code cluster.mode=shared-store}: it merges the live per-instance
 * snapshots held in a {@link SnapshotStore} into one aggregate via {@link MetricsSummaryAggregator} — the same
 * {@link MetricsKpis} math as every other source, so the shapes and rates are identical to local / Prometheus.
 * Instances retired by the store (not seen within the retention window) are excluded from the per-instance
 * view but their counts still contribute through {@link SnapshotStore#retiredAggregate()}, so cluster totals
 * never drop when a peer churns away.
 *
 * <p>Aggregation is exact for counters (summed per API across instances) and approximate for latency (count-weighted
 * mean, max-of-max) — acceptable for the bounded small-cluster tier. When no instance has ever reported it
 * falls back to this instance's {@code local} source, so the dashboard never goes dark.
 *
 * @author Anand Manissery
 */
public class SharedStoreMetricsSource implements MetricsSource {

    private final SnapshotStore store;
    private final DashboardProperties.Health thresholds;
    private final MetricsSource fallback;
    private final int maxInstances;
    private final ClusterSeriesStore seriesStore;       // nullable — null ⇒ no cluster trend, fall back to local
    private final HeartbeatStore heartbeatStore;        // nullable only if no shared-store (should not happen in normal wiring)
    private final long livenessMillis;                  // from liveness-seconds config
    private final LongSupplier nowMillis;

    public SharedStoreMetricsSource(SnapshotStore store, DashboardProperties.Health thresholds,
                                    MetricsSource fallback, int maxInstances) {
        this(store, thresholds, fallback, maxInstances, null, null, 0);
    }

    public SharedStoreMetricsSource(SnapshotStore store, DashboardProperties.Health thresholds,
                                    MetricsSource fallback, int maxInstances, ClusterSeriesStore seriesStore) {
        this(store, thresholds, fallback, maxInstances, seriesStore, null, 0);
    }

    public SharedStoreMetricsSource(SnapshotStore store, DashboardProperties.Health thresholds,
                                    MetricsSource fallback, int maxInstances, ClusterSeriesStore seriesStore,
                                    HeartbeatStore heartbeatStore, long livenessMillis) {
        this(store, thresholds, fallback, maxInstances, seriesStore, heartbeatStore, livenessMillis,
                System::currentTimeMillis);
    }

    /** Test seam: inject a clock to control liveness age. */
    SharedStoreMetricsSource(SnapshotStore store, DashboardProperties.Health thresholds,
                             MetricsSource fallback, int maxInstances, ClusterSeriesStore seriesStore,
                             HeartbeatStore heartbeatStore, long livenessMillis, LongSupplier nowMillis) {
        this.store = store;
        this.thresholds = thresholds;
        this.fallback = fallback;
        this.maxInstances = maxInstances;
        this.seriesStore = seriesStore;
        this.heartbeatStore = heartbeatStore;
        this.livenessMillis = livenessMillis;
        this.nowMillis = nowMillis;
    }

    @Override
    public MetricsSummary summary() {
        MetricsSummary merged = mergedSummary();
        return merged != null ? merged : fallback.summary();
    }

    @Override
    public List<ApiHealth> health() {
        MetricsSummary merged = mergedSummary();
        if (merged == null) {
            return fallback.health();
        }
        return merged.perApi().stream()
                .map(k -> MetricsKpis.classify(k.name(), k.rates().healthyRate(),
                        thresholds.degradedThreshold(), thresholds.unhealthyThreshold()))
                .toList();
    }

    @Override
    public SourceInfo info() {
        List<InstanceMetrics> all = instances();
        long newest = all.stream().mapToLong(InstanceMetrics::lastSeenEpochMs).max().orElse(0L);
        long reporting = all.stream().filter(i -> i.liveStatus() != LiveStatus.DOWN).count();
        return new SourceInfo("shared-store", (int) reporting, maxInstances,
                newest > 0 ? newest : System.currentTimeMillis(), false);
    }

    @Override
    public List<SeriesPoint> series(long windowSec) {
        // Cluster-wide reset-aware trend from the series ring; if disabled, serve this instance's local trend.
        return seriesStore != null ? seriesStore.series(windowSec) : fallback.series(windowSec);
    }

    /**
     * Returns per-instance metrics from the shared store only. The dashboard host's own local
     * registry is not included — instances are the remote failover services that push snapshots,
     * not the dashboard itself.
     */
    @Override
    public List<InstanceMetrics> instances() {
        return enrichWithLiveness(store.allInstances());
    }

    @Override
    public List<ConfigEntry> configEntries() {
        List<ConfigEntry> merged = store.configEntries();
        return merged.isEmpty() ? fallback.configEntries() : merged;
    }

    /**
     * Merges the current instances plus the store's retired aggregate, or {@code null} when the store
     * holds nothing at all (⇒ caller falls back to the local source).
     */
    private MetricsSummary mergedSummary() {
        List<MetricsSummary> parts = new ArrayList<>(instances().stream().map(InstanceMetrics::summary).toList());
        MetricsSummary retired = store.retiredAggregate();
        if (retired != null) {
            parts.add(retired);
        }
        return parts.isEmpty() ? null : MetricsSummaryAggregator.merge(parts);
    }

    private List<InstanceMetrics> enrichWithLiveness(List<InstanceMetrics> instances) {
        if (heartbeatStore == null) {
            return instances;
        }
        return instances.stream()
                .map(i -> {
                    Long last = heartbeatStore.lastSeen(i.instanceId());
                    if (last == null) {
                        return i; // peer has not sent any heartbeat — keep UNKNOWN
                    }
                    long age = nowMillis.getAsLong() - last;
                    return new InstanceMetrics(i.instanceId(), i.lastSeenEpochMs(), i.summary(),
                            age <= livenessMillis ? LiveStatus.LIVE : LiveStatus.DOWN);
                })
                .toList();
    }
}
