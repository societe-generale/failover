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

import com.societegenerale.failover.dashboard.metrics.source.DashboardKpis;
import com.societegenerale.failover.dashboard.config.DashboardProperties;

import com.societegenerale.failover.dashboard.metrics.ApiHealth;
import com.societegenerale.failover.dashboard.metrics.ApiKpis;
import com.societegenerale.failover.dashboard.metrics.ExceptionStat;
import com.societegenerale.failover.dashboard.metrics.Latency;
import com.societegenerale.failover.dashboard.metrics.MetricsSummary;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.distribution.ValueAtPercentile;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.concurrent.TimeUnit;

/**
 * Aggregates the existing {@code failover.*} Micrometer counters into the dashboard KPIs and health
 * classification (design doc §4.3–§4.4). Reads only — it never registers a new meter, so the dashboard
 * is a pure consumer of signals the framework already publishes.
 *
 * <p>Rate denominators that are zero yield {@code 0.0} (never {@code NaN}): an unexercised failover
 * point reports clean zeros rather than divide-by-zero artefacts.
 *
 * @author Anand Manissery
 */
public class DashboardMetricsService {

    private static final String STORE_TOTAL = "failover.store.total";
    private static final String OUTCOME_TOTAL = "failover.recovery.outcome.total";
    private static final String PARTIAL_TOTAL = "failover.recovery.partial.total";
    private static final String ASYNC_FAILED_TOTAL = "failover.store.async.failed";
    private static final String DURATION = "failover.operation.duration";
    private static final String EXCEPTION_TOTAL = "failover.exception.total";

    /** How many top exception types {@link #metricsSummary()} returns. */
    private static final int TOP_EXCEPTIONS = 8;

    private final MeterRegistry registry;
    private final DashboardProperties properties;

    public DashboardMetricsService(MeterRegistry registry, DashboardProperties properties) {
        this.registry = registry;
        this.properties = properties;
    }

    /**
     * @return per-API and overall KPIs derived from the current counter totals.
     */
    public MetricsSummary metricsSummary() {
        List<ApiKpis> perApi = discoveredNames().stream()
                .map(this::kpisFor)
                .toList();
        return new MetricsSummary(overall(perApi), perApi, topExceptions(TOP_EXCEPTIONS), System.currentTimeMillis());
    }

    /**
     * @return per-API health classification, ordered by name.
     */
    public List<ApiHealth> health() {
        return discoveredNames().stream()
                .map(name -> classify(name, kpisFor(name).rates().healthyRate()))
                .toList();
    }

    // ── derivations ─────────────────────────────────────────────────────────

    private ApiKpis kpisFor(String name) {
        long success = (long) sum(STORE_TOTAL, "name", name, "stored", "true");
        long recovered = (long) sum(OUTCOME_TOTAL, "name", name, "outcome", "recovered");
        long notRecovered = (long) sum(OUTCOME_TOTAL, "name", name, "outcome", "not_recovered");
        long errors = (long) sum(OUTCOME_TOTAL, "name", name, "outcome", "error");
        long partial = (long) sum(PARTIAL_TOTAL, "name", name, null, null);
        long asyncFailed = (long) sum(ASYNC_FAILED_TOTAL, "name", name, null, null);
        String domain = domainFor(name);
        return buildKpis(name, domain, success, recovered, notRecovered, errors, partial, asyncFailed, latencyFor(name));
    }

    private ApiKpis overall(List<ApiKpis> perApi) {
        return DashboardKpis.overall(perApi, latencyFor(null));
    }

    private ApiKpis buildKpis(String name, String domain, long success, long recovered, long notRecovered,
                              long errors, long partial, long asyncFailed, Latency latency) {
        return DashboardKpis.build(name, domain, success, recovered, notRecovered, errors, partial, asyncFailed, latency);
    }

    /**
     * Mean/max and p95/p99 store and recover latency (ms) for {@code name}, or across all names when
     * {@code name} is null. Percentiles are available only when a single timer matches (the per-name case);
     * across many timers (overall view) they are reported as {@code 0} — percentiles cannot be merged.
     */
    private Latency latencyFor(String name) {
        double[] store = timerStats(name, "store");
        double[] recover = timerStats(name, "recover");
        return new Latency(store[0], store[1], recover[0], recover[1],
                store[2], store[3], recover[2], recover[3]);
    }

    /**
     * Aggregates the {@code failover.operation.duration} timers matching {@code action} (and {@code name}
     * unless null) into {@code [meanMs, maxMs, p95Ms, p99Ms]}. Mean is total-time / count across matched
     * timers; max is the max-of-maxes. p95/p99 come from the timer's client-side percentiles and are
     * meaningful only for a single matched timer — across several (overall view) they are {@code 0}.
     */
    private double[] timerStats(String name, String action) {
        long count = 0;
        double totalMs = 0;
        double maxMs = 0;
        int matched = 0;
        Timer singleTimer = null;
        for (Timer t : registry.find(DURATION).timers()) {
            if (name != null && !name.equals(t.getId().getTag("name"))) {
                continue;
            }
            if (!action.equals(t.getId().getTag("action"))) {
                continue;
            }
            count += t.count();
            totalMs += t.totalTime(TimeUnit.MILLISECONDS);
            maxMs = Math.max(maxMs, t.max(TimeUnit.MILLISECONDS));
            singleTimer = t;
            matched++;
        }
        double mean = count == 0 ? 0.0 : totalMs / count;
        double p95 = 0;
        double p99 = 0;
        if (matched == 1) {
            for (ValueAtPercentile v : singleTimer.takeSnapshot().percentileValues()) {
                if (v.percentile() == 0.95) {
                    p95 = v.value(TimeUnit.MILLISECONDS);
                } else if (v.percentile() == 0.99) {
                    p99 = v.value(TimeUnit.MILLISECONDS);
                }
            }
        }
        return new double[]{round2(mean), round2(maxMs), round2(p95), round2(p99)};
    }

    /** Top {@code limit} root exception types triggering failover, summed across all failover points. */
    private List<ExceptionStat> topExceptions(int limit) {
        Map<String, Long> byType = new LinkedHashMap<>();
        for (Counter c : registry.find(EXCEPTION_TOTAL).counters()) {
            // The aspect wraps every upstream throwable in a framework ExecutionException, so
            // 'exception_type' is always that wrapper. The real upstream exception is the innermost
            // cause ('final_cause_type'); 'cause_type' is the first-level cause. Both are "none" when
            // absent. Prefer the innermost cause, then the first-level cause, then the wrapper.
            String type = firstPresent(
                    c.getId().getTag("final_cause_type"),
                    c.getId().getTag("cause_type"),
                    c.getId().getTag("exception_type"));
            if (type != null) {
                byType.merge(type, (long) c.count(), Long::sum);
            }
        }
        return byType.entrySet().stream()
                .sorted((a, b) -> Long.compare(b.getValue(), a.getValue()))
                .limit(limit)
                .map(e -> new ExceptionStat(e.getKey(), e.getValue()))
                .toList();
    }

    /** First tag value that is present and not the {@code "none"} sentinel; {@code null} if none qualify. */
    private static String firstPresent(String... values) {
        for (String v : values) {
            if (v != null && !v.isBlank() && !"none".equals(v)) {
                return v;
            }
        }
        return null;
    }

    private static double round2(double v) {
        return Math.round(v * 100.0) / 100.0;
    }

    private ApiHealth classify(String name, double healthyRate) {
        return DashboardKpis.classify(name, healthyRate, properties.health());
    }

    // ── registry access ─────────────────────────────────────────────────────

    /** Distinct failover names across the store and outcome counters, sorted. */
    private TreeSet<String> discoveredNames() {
        TreeSet<String> names = new TreeSet<>();
        collectNames(STORE_TOTAL, names);
        collectNames(OUTCOME_TOTAL, names);
        return names;
    }

    private void collectNames(String meter, TreeSet<String> sink) {
        for (Counter c : registry.find(meter).counters()) {
            String name = c.getId().getTag("name");
            if (name != null) {
                sink.add(name);
            }
        }
    }

    private String domainFor(String name) {
        for (Counter c : registry.find(OUTCOME_TOTAL).counters()) {
            if (name.equals(c.getId().getTag("name"))) {
                String domain = c.getId().getTag("domain");
                if (domain != null) {
                    return domain;
                }
            }
        }
        return name;
    }

    /**
     * Sums {@code count()} over every counter of {@code meter} matching {@code nameTag=name} and,
     * when {@code filterKey} is non-null, {@code filterKey=filterValue}.
     */
    private double sum(String meter, String nameKey, String name, String filterKey, String filterValue) {
        double total = 0;
        for (Counter c : registry.find(meter).counters()) {
            if (!name.equals(c.getId().getTag(nameKey))) {
                continue;
            }
            if (filterKey != null && !filterValue.equals(c.getId().getTag(filterKey))) {
                continue;
            }
            total += c.count();
        }
        return total;
    }

}
