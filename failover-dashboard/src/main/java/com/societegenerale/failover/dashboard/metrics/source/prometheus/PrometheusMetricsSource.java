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

package com.societegenerale.failover.dashboard.metrics.source.prometheus;

import com.societegenerale.failover.dashboard.config.DashboardProperties;
import com.societegenerale.failover.dashboard.metrics.source.MetricsSource;
import com.societegenerale.failover.dashboard.metrics.source.DashboardKpis;

import com.societegenerale.failover.dashboard.metrics.source.prometheus.PrometheusClient.RangePoint;
import com.societegenerale.failover.dashboard.metrics.source.prometheus.PrometheusClient.RangeSeries;
import com.societegenerale.failover.dashboard.metrics.source.prometheus.PrometheusClient.Sample;
import com.societegenerale.failover.dashboard.metrics.ApiHealth;
import com.societegenerale.failover.dashboard.metrics.ApiKpis;
import com.societegenerale.failover.dashboard.metrics.ExceptionStat;
import com.societegenerale.failover.dashboard.metrics.InstanceMetrics;
import com.societegenerale.failover.dashboard.metrics.Latency;
import com.societegenerale.failover.dashboard.metrics.MetricsSummary;
import com.societegenerale.failover.dashboard.metrics.SeriesPoint;
import com.societegenerale.failover.dashboard.metrics.SourceInfo;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;

/**
 * Cluster-wide {@link MetricsSource} that aggregates the {@code failover.*} meters across every instance
 * via the Prometheus HTTP API (design doc / distributed-dashboard design, Option A). Behind a load
 * balancer this yields stable, cluster-correct KPIs instead of the random single-instance figures a local
 * registry returns.
 *
 * <p>The KPIs are lifetime cumulative — {@code sum(metric)} across instances, matching the
 * "since process start" semantics of the local view. Any Prometheus failure falls back to the supplied
 * local {@code fallback} source (with a warning) so the dashboard never goes dark.
 *
 * <p>Assumes the standard Micrometer Prometheus naming of the {@code failover.*} meters (dots → underscores,
 * counters keep their {@code _total} suffix, the timer is exported in seconds with {@code _sum}/{@code _count}/{@code _max}).
 *
 * @author Anand Manissery
 */
@Slf4j
public class PrometheusMetricsSource implements MetricsSource {

    // PromQL — instant queries summed across instances (the 'instance' label is intentionally dropped).
    private static final String Q_SUCCESS  = "sum by (name) (failover_store_total{stored=\"true\"})";
    private static final String Q_OUTCOME  = "sum by (name, domain, outcome) (failover_recovery_outcome_total)";
    private static final String Q_PARTIAL  = "sum by (name) (failover_recovery_partial_total)";
    private static final String Q_ASYNC    = "sum by (name) (failover_store_async_failed_total)";
    private static final String Q_LAT_SUM  = "sum by (name, action) (failover_operation_duration_seconds_sum)";
    private static final String Q_LAT_CNT  = "sum by (name, action) (failover_operation_duration_seconds_count)";
    private static final String Q_LAT_MAX  = "max by (name, action) (failover_operation_duration_seconds_max)";
    // p95/p99 from the percentile-histogram buckets, cluster-wide per name+action.
    private static final String Q_LAT_P95  = "histogram_quantile(0.95, sum by (name, action, le) (failover_operation_duration_seconds_bucket))";
    private static final String Q_LAT_P99  = "histogram_quantile(0.99, sum by (name, action, le) (failover_operation_duration_seconds_bucket))";
    private static final String Q_EXC      = "sum by (final_cause_type, cause_type, exception_type) (failover_exception_total)";
    private static final String Q_INSTANCES = "count(group by (instance) (failover_store_total))";

    // PromQL — per-instance variants (the 'instance' label is kept) for the Instances view.
    private static final String QI_SUCCESS = "sum by (name, instance) (failover_store_total{stored=\"true\"})";
    private static final String QI_OUTCOME = "sum by (name, domain, instance, outcome) (failover_recovery_outcome_total)";
    private static final String QI_PARTIAL = "sum by (name, instance) (failover_recovery_partial_total)";
    private static final String QI_ASYNC   = "sum by (name, instance) (failover_store_async_failed_total)";
    private static final String QI_LAT_SUM = "sum by (name, action, instance) (failover_operation_duration_seconds_sum)";
    private static final String QI_LAT_CNT = "sum by (name, action, instance) (failover_operation_duration_seconds_count)";
    private static final String QI_LAT_MAX = "max by (name, action, instance) (failover_operation_duration_seconds_max)";
    private static final String QI_LAT_P95 = "histogram_quantile(0.95, sum by (name, action, instance, le) (failover_operation_duration_seconds_bucket))";
    private static final String QI_LAT_P99 = "histogram_quantile(0.99, sum by (name, action, instance, le) (failover_operation_duration_seconds_bucket))";
    private static final String QI_EXC     = "sum by (final_cause_type, cause_type, exception_type, instance) (failover_exception_total)";

    // PromQL — range queries for the cluster trend (cumulative totals; the UI deltas consecutive points).
    private static final String R_CALLS     = "sum(failover_store_total{stored=\"true\"}) + sum(failover_recovery_outcome_total)";
    private static final String R_STORE     = "sum(failover_store_total{stored=\"true\"})";
    private static final String R_FAILOVER  = "sum(failover_recovery_outcome_total)";
    private static final String R_RECOVERED = "sum(failover_recovery_outcome_total{outcome=\"recovered\"})";
    private static final String R_NOTREC    = "sum(failover_recovery_outcome_total{outcome=\"not_recovered\"})";
    private static final String R_BY_API    = "sum by (name) (failover_recovery_outcome_total)";

    /**
     * Every PromQL constant above, exposed package-private for the drift guard
     * ({@code PrometheusQueryDriftTest}) to assert no query references an unexported metric — without reflection.
     */
    static java.util.List<String> promQlConstants() {
        return java.util.List.of(
                Q_SUCCESS, Q_OUTCOME, Q_PARTIAL, Q_ASYNC, Q_LAT_SUM, Q_LAT_CNT, Q_LAT_MAX, Q_LAT_P95, Q_LAT_P99,
                Q_EXC, Q_INSTANCES,
                QI_SUCCESS, QI_OUTCOME, QI_PARTIAL, QI_ASYNC, QI_LAT_SUM, QI_LAT_CNT, QI_LAT_MAX, QI_LAT_P95,
                QI_LAT_P99, QI_EXC,
                R_CALLS, R_STORE, R_FAILOVER, R_RECOVERED, R_NOTREC, R_BY_API);
    }

    private static final int TOP_EXCEPTIONS = 8;

    private static final long DEFAULT_WINDOW_SEC = 1800;   // 30 min when windowSec <= 0 ("all retained")
    private static final long TARGET_POINTS = 60;          // aim for ~60 samples across the window
    private static final long MIN_STEP_SEC = 15;

    private final PrometheusClient client;
    private final MetricsSource fallback;
    private final DashboardProperties.Health thresholds;

    public PrometheusMetricsSource(PrometheusClient client, MetricsSource fallback,
                                   DashboardProperties.Health thresholds) {
        this.client = client;
        this.fallback = fallback;
        this.thresholds = thresholds;
    }

    @Override
    public MetricsSummary summary() {
        try {
            return buildSummary();
        } catch (PrometheusException e) {
            log.warn("Prometheus aggregation failed; falling back to this instance's local metrics. Cause: {}",
                    e.getMessage());
            return fallback.summary();
        }
    }

    @Override
    public List<ApiHealth> health() {
        try {
            return buildSummary().perApi().stream()
                    .map(k -> DashboardKpis.classify(k.name(), k.rates().healthyRate(), thresholds))
                    .toList();
        } catch (PrometheusException e) {
            log.warn("Prometheus health aggregation failed; falling back to local. Cause: {}", e.getMessage());
            return fallback.health();
        }
    }

    @Override
    public SourceInfo info() {
        try {
            return new SourceInfo("prometheus", instanceCount(), -1, System.currentTimeMillis(), false);
        } catch (PrometheusException e) {
            log.warn("Prometheus instance probe failed; reporting local provenance. Cause: {}", e.getMessage());
            return fallback.info();
        }
    }

    @Override
    public List<SeriesPoint> series(long windowSec) {
        try {
            return buildSeries(windowSec);
        } catch (PrometheusException e) {
            log.warn("Prometheus trend (query_range) failed; falling back to local. Cause: {}", e.getMessage());
            return fallback.series(windowSec);
        }
    }

    @Override
    public List<InstanceMetrics> instances() {
        try {
            return buildInstances();
        } catch (PrometheusException e) {
            log.warn("Prometheus per-instance aggregation failed; no instance breakdown shown. Cause: {}", e.getMessage());
            return List.of();
        }
    }

    // ── aggregation ───────────────────────────────────────────────────────────

    private List<SeriesPoint> buildSeries(long windowSec) {
        long end = System.currentTimeMillis() / 1000;
        long window = windowSec > 0 ? windowSec : DEFAULT_WINDOW_SEC;
        long start = end - window;
        long step = Math.max(MIN_STEP_SEC, window / TARGET_POINTS);

        Map<Long, Double> calls = scalarRange(client.queryRange(R_CALLS, start, end, step));
        Map<Long, Double> store = scalarRange(client.queryRange(R_STORE, start, end, step));
        Map<Long, Double> failover = scalarRange(client.queryRange(R_FAILOVER, start, end, step));
        Map<Long, Double> recovered = scalarRange(client.queryRange(R_RECOVERED, start, end, step));
        Map<Long, Double> notRecovered = scalarRange(client.queryRange(R_NOTREC, start, end, step));
        Map<String, Map<Long, Double>> byApi = byNameRange(client.queryRange(R_BY_API, start, end, step));

        TreeSet<Long> spine = new TreeSet<>(calls.keySet());
        if (spine.isEmpty()) {
            spine.addAll(failover.keySet());
        }
        List<SeriesPoint> out = new ArrayList<>(spine.size());
        for (Long ts : spine) {
            long f = round(failover.getOrDefault(ts, 0.0));
            Map<String, Long> failoverByApi = new LinkedHashMap<>();
            for (Map.Entry<String, Map<Long, Double>> e : byApi.entrySet()) {
                failoverByApi.put(e.getKey(), round(e.getValue().getOrDefault(ts, 0.0)));
            }
            out.add(new SeriesPoint(ts,
                    round(calls.getOrDefault(ts, 0.0)), f,
                    round(recovered.getOrDefault(ts, 0.0)), round(notRecovered.getOrDefault(ts, 0.0)),
                    round(store.getOrDefault(ts, 0.0)), f, failoverByApi));
        }
        return out;
    }

    /** A scalar {@code sum(...)} range result (0 or 1 series) → timestamp(ms) → value. */
    private static Map<Long, Double> scalarRange(List<RangeSeries> series) {
        Map<Long, Double> out = new LinkedHashMap<>();
        if (!series.isEmpty()) {
            for (RangePoint p : series.getFirst().points()) {
                out.put(p.timestampMs(), p.value());
            }
        }
        return out;
    }

    /** A {@code sum by (name)} range result → name → (timestamp(ms) → value). */
    private static Map<String, Map<Long, Double>> byNameRange(List<RangeSeries> series) {
        Map<String, Map<Long, Double>> out = new LinkedHashMap<>();
        for (RangeSeries s : series) {
            String name = s.label("name");
            if (name == null) {
                continue;
            }
            Map<Long, Double> points = out.computeIfAbsent(name, k -> new LinkedHashMap<>());
            for (RangePoint p : s.points()) {
                points.merge(p.timestampMs(), p.value(), Double::sum);
            }
        }
        return out;
    }

    // ── aggregation (summary) ──────────────────────────────────────────────────

    private MetricsSummary buildSummary() {
        return buildFrom(client.query(Q_SUCCESS), client.query(Q_OUTCOME), client.query(Q_PARTIAL),
                client.query(Q_ASYNC),
                new LatencyIndex(client.query(Q_LAT_SUM), client.query(Q_LAT_CNT), client.query(Q_LAT_MAX),
                        client.query(Q_LAT_P95), client.query(Q_LAT_P99)),
                client.query(Q_EXC));
    }

    /**
     * Assembles one {@link MetricsSummary} from already-fetched sample vectors (each {@code by (name, ...)}).
     * Used both for the cluster aggregate and, with per-instance-filtered samples, for each instance.
     */
    private MetricsSummary buildFrom(List<Sample> successSamples, List<Sample> outcomes, List<Sample> partialSamples,
                                     List<Sample> asyncSamples, LatencyIndex latency, List<Sample> exc) {
        Map<String, Long> success = sumByName(successSamples);
        Map<String, Long> partial = sumByName(partialSamples);
        Map<String, Long> async = sumByName(asyncSamples);

        // outcome counts + domain per name
        Map<String, long[]> outcomeByName = new LinkedHashMap<>();   // [recovered, notRecovered, error]
        Map<String, String> domainByName = new LinkedHashMap<>();
        for (Sample s : outcomes) {
            String name = s.label("name");
            if (name == null) {
                continue;
            }
            long[] o = outcomeByName.computeIfAbsent(name, k -> new long[3]);
            switch (s.label("outcome") == null ? "" : s.label("outcome")) {
                case "recovered" -> o[0] += round(s.value());
                case "not_recovered" -> o[1] += round(s.value());
                case "error" -> o[2] += round(s.value());
                default -> { /* ignore unknown outcomes */ }
            }
            domainByName.putIfAbsent(name, s.label("domain") != null ? s.label("domain") : name);
        }

        TreeSet<String> names = new TreeSet<>();
        names.addAll(success.keySet());
        names.addAll(outcomeByName.keySet());

        List<ApiKpis> perApi = new ArrayList<>();
        for (String name : names) {
            long[] o = outcomeByName.getOrDefault(name, new long[3]);
            perApi.add(DashboardKpis.build(
                    name, domainByName.getOrDefault(name, name),
                    success.getOrDefault(name, 0L), o[0], o[1], o[2],
                    partial.getOrDefault(name, 0L), async.getOrDefault(name, 0L),
                    latency.forName(name)));
        }

        ApiKpis overall = DashboardKpis.overall(perApi, latency.overall());
        return new MetricsSummary(overall, perApi, topExceptions(exc), System.currentTimeMillis());
    }

    /** Per-instance summaries: same queries grouped by {@code instance}, partitioned, one summary each. */
    private List<InstanceMetrics> buildInstances() {
        Map<String, List<Sample>> success = byInstance(client.query(QI_SUCCESS));
        Map<String, List<Sample>> outcomes = byInstance(client.query(QI_OUTCOME));
        Map<String, List<Sample>> partial = byInstance(client.query(QI_PARTIAL));
        Map<String, List<Sample>> async = byInstance(client.query(QI_ASYNC));
        Map<String, List<Sample>> latSum = byInstance(client.query(QI_LAT_SUM));
        Map<String, List<Sample>> latCnt = byInstance(client.query(QI_LAT_CNT));
        Map<String, List<Sample>> latMax = byInstance(client.query(QI_LAT_MAX));
        Map<String, List<Sample>> latP95 = byInstance(client.query(QI_LAT_P95));
        Map<String, List<Sample>> latP99 = byInstance(client.query(QI_LAT_P99));
        Map<String, List<Sample>> exc = byInstance(client.query(QI_EXC));

        TreeSet<String> ids = new TreeSet<>();
        ids.addAll(success.keySet());
        ids.addAll(outcomes.keySet());
        long now = System.currentTimeMillis();
        List<InstanceMetrics> out = new ArrayList<>();
        for (String id : ids) {
            LatencyIndex latency = new LatencyIndex(get(latSum, id), get(latCnt, id), get(latMax, id),
                    get(latP95, id), get(latP99, id));
            MetricsSummary summary = buildFrom(get(success, id), get(outcomes, id), get(partial, id),
                    get(async, id), latency, get(exc, id));
            out.add(new InstanceMetrics(id, now, summary));   // Prometheus is scraped → all returned are live
        }
        return out;
    }

    /** Groups a sample vector by its {@code instance} label (samples without one are dropped). */
    private static Map<String, List<Sample>> byInstance(List<Sample> samples) {
        Map<String, List<Sample>> out = new LinkedHashMap<>();
        for (Sample s : samples) {
            String inst = s.label("instance");
            if (inst != null) {
                out.computeIfAbsent(inst, k -> new ArrayList<>()).add(s);
            }
        }
        return out;
    }

    private static List<Sample> get(Map<String, List<Sample>> byId, String id) {
        return byId.getOrDefault(id, List.of());
    }

    private int instanceCount() {
        List<Sample> r = client.query(Q_INSTANCES);
        return r.isEmpty() ? 0 : (int) round(r.getFirst().value());
    }

    /** Sums a {@code by (name)} vector into name → rounded total. */
    private static Map<String, Long> sumByName(List<Sample> samples) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Sample s : samples) {
            String name = s.label("name");
            if (name != null) {
                out.merge(name, round(s.value()), Long::sum);
            }
        }
        return out;
    }

    /** Top exception types, preferring the innermost real cause (mirrors the local source's logic). */
    private static List<ExceptionStat> topExceptions(List<Sample> samples) {
        Map<String, Long> byType = new LinkedHashMap<>();
        for (Sample s : samples) {
            String type = firstPresent(s.label("final_cause_type"), s.label("cause_type"), s.label("exception_type"));
            if (type != null) {
                byType.merge(type, round(s.value()), Long::sum);
            }
        }
        return byType.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(TOP_EXCEPTIONS)
                .map(e -> new ExceptionStat(e.getKey(), e.getValue()))
                .toList();
    }

    private static String firstPresent(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank() && !"none".equals(v)) {
                return v;
            }
        }
        return null;
    }

    private static long round(double v) {
        return Math.round(v);
    }

    /**
     * Indexes the three latency vectors (seconds) by {@code name+action}, exposing mean/max in
     * milliseconds per name and across all names. Mean = Σsum / Σcount; never {@code NaN}.
     */
    private static final class LatencyIndex {
        private final Map<String, Double> sumByKey = new LinkedHashMap<>();   // name|action → seconds sum
        private final Map<String, Double> cntByKey = new LinkedHashMap<>();
        private final Map<String, Double> maxByKey = new LinkedHashMap<>();
        private final Map<String, Double> p95ByKey = new LinkedHashMap<>();   // name|action → p95 seconds
        private final Map<String, Double> p99ByKey = new LinkedHashMap<>();

        LatencyIndex(List<Sample> sums, List<Sample> counts, List<Sample> maxes, List<Sample> p95s, List<Sample> p99s) {
            sums.forEach(s -> put(sumByKey, s));
            counts.forEach(s -> put(cntByKey, s));
            maxes.forEach(s -> put(maxByKey, s));
            p95s.forEach(s -> put(p95ByKey, s));
            p99s.forEach(s -> put(p99ByKey, s));
        }

        private static void put(Map<String, Double> map, Sample s) {
            if (s.label("name") != null && s.label("action") != null) {
                map.merge(s.label("name") + "|" + s.label("action"), s.value(), Double::sum);
            }
        }

        Latency forName(String name) {
            return new Latency(meanMs(name, "store"), maxMs(name, "store"),
                    meanMs(name, "recover"), maxMs(name, "recover"),
                    percentileMs(p95ByKey, name, "store"), percentileMs(p99ByKey, name, "store"),
                    percentileMs(p95ByKey, name, "recover"), percentileMs(p99ByKey, name, "recover"));
        }

        /** Cluster-wide latency across every name, per action. Percentiles omitted (not mergeable across names). */
        Latency overall() {
            return new Latency(meanMs(null, "store"), maxMs(null, "store"),
                    meanMs(null, "recover"), maxMs(null, "recover"));
        }

        /**
         * Exact per-(name,action) quantile in ms; {@code 0} when absent or NaN. Not aggregated across names —
         * only {@link #forName} calls this (always with a non-null {@code name}); {@link #overall} omits percentiles.
         */
        private double percentileMs(Map<String, Double> map, String name, String action) {
            Double seconds = map.get(name + "|" + action);
            return seconds == null || Double.isNaN(seconds) ? 0.0 : round2(seconds * 1000.0);
        }

        private double meanMs(String name, String action) {
            double sum = aggregate(sumByKey, name, action, false);
            double cnt = aggregate(cntByKey, name, action, false);
            return cnt == 0 ? 0.0 : round2(sum / cnt * 1000.0);
        }

        private double maxMs(String name, String action) {
            return round2(aggregate(maxByKey, name, action, true) * 1000.0);
        }

        /** Sums (or maxes) entries matching {@code action} and, when {@code name} is non-null, that name. */
        private double aggregate(Map<String, Double> map, String name, String action, boolean max) {
            double acc = 0;
            for (Map.Entry<String, Double> e : map.entrySet()) {
                String[] parts = e.getKey().split("\\|", 2);
                if (!action.equals(parts[1])) {
                    continue;
                }
                if (name != null && !name.equals(parts[0])) {
                    continue;
                }
                acc = max ? Math.max(acc, e.getValue()) : acc + e.getValue();
            }
            return acc;
        }

        private static double round2(double v) {
            return Math.round(v * 100.0) / 100.0;
        }
    }
}
