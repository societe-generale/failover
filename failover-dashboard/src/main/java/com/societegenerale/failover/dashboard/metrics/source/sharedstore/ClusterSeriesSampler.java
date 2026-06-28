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

import com.societegenerale.failover.observable.metrics.ApiKpis;
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import com.societegenerale.failover.observable.metrics.SeriesPoint;
import com.societegenerale.failover.dashboard.metrics.source.MetricsSource;
import lombok.extern.slf4j.Slf4j;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Periodically samples the merged cluster aggregate (a {@link MetricsSource}) into a {@link ClusterSeriesStore},
 * producing the cluster-wide trend for {@code cluster.mode=shared-store}.
 *
 * <p><strong>Reset-aware monotonic cumulative (consistency rule, design §5.3).</strong> {@link SeriesPoint}s are
 * cumulative and the UI deltas consecutive points. When a peer restarts, its counters reset to zero, so the raw
 * cluster sum <em>drops</em> — naive cumulative would yield a negative delta. This sampler instead accumulates a
 * monotonic adjusted total: per field it adds {@code max(increase, freshValueAfterReset)} so the series never
 * decreases and post-reset activity is still counted.
 *
 * <p>Runs on a single daemon thread; stopped on shutdown.
 *
 * @author Anand Manissery
 */
@Slf4j
public class ClusterSeriesSampler implements AutoCloseable {

    /** Overall fields tracked, in SeriesPoint order: calls, failover, recovered, notRecovered, store, recover. */
    private static final int FIELDS = 6;

    private final MetricsSource source;
    private final ClusterSeriesStore store;
    private final ScheduledExecutorService scheduler;

    private long[] lastOverall;                 // null until first sample
    private final long[] adjustedOverall = new long[FIELDS];
    private final Map<String, long[]> apiState = new LinkedHashMap<>();   // name -> [lastRaw, adjusted, seen]

    public ClusterSeriesSampler(MetricsSource source, ClusterSeriesStore store, int intervalSeconds) {
        this.source = source;
        this.store = store;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "failover-cluster-series-sampler");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleWithFixedDelay(this::safeSample, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
    }

    void safeSample() {
        try {
            sample();
        } catch (Exception e) {
            log.warn("Failover cluster series sample failed: {}", e.toString());
        }
    }

    /** Takes one reset-aware sample and appends it to the store. Package-visible for tests. */
    synchronized void sample() {
        MetricsSummary summary = source.summary();
        ApiKpis o = summary.overall();
        long[] raw = {o.totalCalls(), o.failoverInvoked(), o.recovered(), o.notRecovered(), o.upstreamSuccess(), o.failoverInvoked()};
        for (int i = 0; i < FIELDS; i++) {
            adjustedOverall[i] += increment(lastOverall == null ? -1 : lastOverall[i], raw[i]);
        }
        lastOverall = raw;

        Map<String, Long> failoverByApi = new LinkedHashMap<>();
        for (ApiKpis api : summary.perApi()) {
            long[] state = apiState.computeIfAbsent(api.name(), k -> new long[]{0, 0, 0});
            long rawFailover = api.failoverInvoked();
            long last = state[2] == 0 ? -1 : state[0];   // state[2]=seen flag
            state[1] += increment(last, rawFailover);
            state[0] = rawFailover;
            state[2] = 1;
            failoverByApi.put(api.name(), state[1]);
        }

        store.append(new SeriesPoint(System.currentTimeMillis(),
                adjustedOverall[0], adjustedOverall[1], adjustedOverall[2],
                adjustedOverall[3], adjustedOverall[4], adjustedOverall[5], failoverByApi));
    }

    /** Monotonic increment: first sample or post-reset (raw &lt; last) counts the raw value; else the increase. */
    private static long increment(long last, long raw) {
        if (last < 0) {
            return raw;                 // first sample
        }
        return raw >= last ? raw - last : raw;   // reset detected when raw < last
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
