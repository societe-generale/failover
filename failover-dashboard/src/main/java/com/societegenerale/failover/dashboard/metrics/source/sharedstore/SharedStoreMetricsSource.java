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
import com.societegenerale.failover.observable.metrics.ApiKpis;
import com.societegenerale.failover.observable.metrics.ExceptionStat;
import com.societegenerale.failover.observable.metrics.InstanceMetrics;
import com.societegenerale.failover.observable.metrics.Latency;
import com.societegenerale.failover.observable.metrics.MetricsKpis;
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import com.societegenerale.failover.observable.metrics.SeriesPoint;
import com.societegenerale.failover.observable.metrics.SourceInfo;
import com.societegenerale.failover.dashboard.metrics.source.MetricsSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Cluster-wide {@link MetricsSource} for {@code cluster.mode=shared-store}: it merges the live per-instance
 * snapshots held in a {@link SnapshotStore} into one aggregate, using the same {@link MetricsKpis} math as
 * every other source, so the shapes and rates are identical to local / Prometheus.
 *
 * <p>Aggregation is exact for counters (summed per API across instances) and approximate for latency (count-weighted
 * mean, max-of-max) — acceptable for the bounded small-cluster tier. When no instance is currently reporting it
 * falls back to this instance's {@code local} source, so the dashboard never goes dark.
 *
 * @author Anand Manissery
 */
public class SharedStoreMetricsSource implements MetricsSource {

    private static final int TOP_EXCEPTIONS = 8;

    private final SnapshotStore store;
    private final DashboardProperties.Health thresholds;
    private final MetricsSource fallback;
    private final int maxInstances;
    private final ClusterSeriesStore seriesStore;   // nullable — null ⇒ no cluster trend, fall back to local

    public SharedStoreMetricsSource(SnapshotStore store, DashboardProperties.Health thresholds,
                                    MetricsSource fallback, int maxInstances) {
        this(store, thresholds, fallback, maxInstances, null);
    }

    public SharedStoreMetricsSource(SnapshotStore store, DashboardProperties.Health thresholds,
                                    MetricsSource fallback, int maxInstances, ClusterSeriesStore seriesStore) {
        this.store = store;
        this.thresholds = thresholds;
        this.fallback = fallback;
        this.maxInstances = maxInstances;
        this.seriesStore = seriesStore;
    }

    @Override
    public MetricsSummary summary() {
        List<MetricsSummary> live = store.live();
        return live.isEmpty() ? fallback.summary() : merge(live);
    }

    @Override
    public List<ApiHealth> health() {
        List<MetricsSummary> live = store.live();
        if (live.isEmpty()) {
            return fallback.health();
        }
        return merge(live).perApi().stream()
                .map(k -> MetricsKpis.classify(k.name(), k.rates().healthyRate(),
                        thresholds.degradedThreshold(), thresholds.unhealthyThreshold()))
                .toList();
    }

    @Override
    public SourceInfo info() {
        long newest = store.newestEpochMs();
        return new SourceInfo("shared-store", store.liveCount(), maxInstances,
                newest > 0 ? newest : System.currentTimeMillis(), false);
    }

    @Override
    public List<SeriesPoint> series(long windowSec) {
        // Cluster-wide reset-aware trend from the series ring; if disabled, serve this instance's local trend.
        return seriesStore != null ? seriesStore.series(windowSec) : fallback.series(windowSec);
    }

    @Override
    public List<InstanceMetrics> instances() {
        return store.liveInstances();
    }

    // ── aggregation ─────────────────────────────────────────────────────────────

    /** Sums per-API counters across instances and rebuilds KPIs/rates via {@link MetricsKpis}. */
    private MetricsSummary merge(List<MetricsSummary> snapshots) {
        Map<String, long[]> counts = new LinkedHashMap<>();        // [success, recovered, notRecovered, errors, partial, asyncFailed]
        Map<String, String> domainByName = new LinkedHashMap<>();
        Map<String, double[]> latency = new LinkedHashMap<>();      // [storeMeanW, storeMax, recoverMeanW, recoverMax, weight]
        Map<String, Long> exceptions = new LinkedHashMap<>();

        for (MetricsSummary snapshot : snapshots) {
            for (ApiKpis k : snapshot.perApi()) {
                String name = k.name();
                long[] c = counts.computeIfAbsent(name, x -> new long[6]);
                c[0] += k.upstreamSuccess();
                c[1] += k.recovered();
                c[2] += k.notRecovered();
                c[3] += k.errors();
                c[4] += k.partial();
                c[5] += k.asyncFailed();
                domainByName.putIfAbsent(name, k.domain());
                accumulateLatency(latency.computeIfAbsent(name, x -> new double[5]), k.latency(), k.totalCalls());
            }
            for (ExceptionStat e : snapshot.topExceptions()) {
                exceptions.merge(e.type(), e.count(), Long::sum);
            }
        }

        List<ApiKpis> perApi = new ArrayList<>();
        for (Map.Entry<String, long[]> entry : counts.entrySet()) {
            String name = entry.getKey();
            long[] c = entry.getValue();
            perApi.add(MetricsKpis.build(name, domainByName.getOrDefault(name, name),
                    c[0], c[1], c[2], c[3], c[4], c[5], toLatency(latency.get(name))));
        }

        ApiKpis overall = MetricsKpis.overall(perApi, overallLatency(perApi));
        return new MetricsSummary(overall, perApi, topExceptions(exceptions), System.currentTimeMillis());
    }

    private static void accumulateLatency(double[] acc, Latency l, long weight) {
        acc[0] += l.storeMeanMs() * weight;
        acc[1] = Math.max(acc[1], l.storeMaxMs());
        acc[2] += l.recoverMeanMs() * weight;
        acc[3] = Math.max(acc[3], l.recoverMaxMs());
        acc[4] += weight;
    }

    private static Latency toLatency(double[] acc) {
        double w = acc[4];
        return new Latency(w > 0 ? round2(acc[0] / w) : 0.0, acc[1], w > 0 ? round2(acc[2] / w) : 0.0, acc[3]);
    }

    /** Cluster-wide latency: count-weighted mean across every API, max-of-max. */
    private static Latency overallLatency(List<ApiKpis> perApi) {
        double[] acc = new double[5];
        for (ApiKpis k : perApi) {
            accumulateLatency(acc, k.latency(), k.totalCalls());
        }
        return toLatency(acc);
    }

    private static List<ExceptionStat> topExceptions(Map<String, Long> byType) {
        return byType.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(TOP_EXCEPTIONS)
                .map(e -> new ExceptionStat(e.getKey(), e.getValue()))
                .toList();
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }
}
