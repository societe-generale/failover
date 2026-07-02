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

package com.societegenerale.failover.observable.metrics;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure {@link MetricsSummary} aggregation math, shared by every consumer that must combine several
 * summaries into one (the shared-store cluster source merging per-instance snapshots, and the snapshot
 * stores folding a pre-reset baseline into a live snapshot). Uses the same {@link MetricsKpis} formulas
 * as every other source, so the merged shapes and rates are identical to local / Prometheus (design §4.4).
 *
 * <p>Aggregation is exact for counters (summed per API) and approximate for latency (count-weighted
 * mean, max-of-max). Exception counts are summed by type, keeping the top {@value #TOP_EXCEPTIONS}.
 *
 * @author Anand Manissery
 */
public final class MetricsSummaryAggregator {

    /** How many top exception types a merged summary retains. */
    public static final int TOP_EXCEPTIONS = 8;

    private MetricsSummaryAggregator() {
    }

    /**
     * Merges several summaries into one: per-API counters summed, latency count-weighted (mean) and
     * max-of-max, exceptions summed by type (top {@value #TOP_EXCEPTIONS} kept).
     *
     * @param summaries the summaries to combine (must not be empty)
     * @return the combined summary, stamped with the current time
     */
    public static MetricsSummary merge(List<MetricsSummary> summaries) {
        Map<String, long[]> counts = new LinkedHashMap<>();        // [success, recovered, notRecovered, errors, partial, asyncFailed]
        Map<String, String> domainByName = new LinkedHashMap<>();
        Map<String, double[]> latency = new LinkedHashMap<>();      // [storeMeanW, storeMax, recoverMeanW, recoverMax, weight]
        Map<String, Long> exceptions = new LinkedHashMap<>();

        for (MetricsSummary snapshot : summaries) {
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

    /**
     * The monotonic cumulative total of a summary — every counter this framework publishes, summed. Grows
     * with every metric event within one process lifetime, so a drop between two snapshots of the same
     * instance can only mean the instance restarted and its counters reset.
     */
    public static long cumulativeTotal(MetricsSummary summary) {
        long total = 0;
        for (ApiKpis k : summary.perApi()) {
            total += k.totalCalls() + k.partial() + k.asyncFailed();
        }
        return total;
    }

    /**
     * @return {@code true} when {@code incoming} is a post-restart snapshot of the instance that produced
     * {@code previous} — i.e. its cumulative total went backwards (counter reset, design §5.3)
     */
    public static boolean isCounterReset(MetricsSummary previous, MetricsSummary incoming) {
        return cumulativeTotal(incoming) < cumulativeTotal(previous);
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
