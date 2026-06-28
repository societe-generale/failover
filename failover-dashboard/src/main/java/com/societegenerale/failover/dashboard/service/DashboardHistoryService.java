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

import com.societegenerale.failover.observable.metrics.ApiKpis;
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import com.societegenerale.failover.observable.metrics.SeriesPoint;
import org.springframework.scheduling.annotation.Scheduled;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Opt-in server-side trend history (design doc §8 option B): a bounded in-memory ring buffer of global
 * counter snapshots, sampled on a fixed schedule. Survives browser reloads (unlike the client-side
 * delta trend) but is process-local and lost on restart — deliberately not a TSDB.
 *
 * <p>Thread-safety: the ring is guarded by an intrinsic lock; the scheduler writes and the controller
 * reads, both briefly.
 *
 * @author Anand Manissery
 */
public class DashboardHistoryService {

    private final DashboardMetricsService metricsService;
    private final int capacity;
    private final Deque<SeriesPoint> ring = new ArrayDeque<>();

    public DashboardHistoryService(DashboardMetricsService metricsService, int capacity) {
        this.metricsService = metricsService;
        this.capacity = Math.max(1, capacity);
    }

    /**
     * Snapshots the current global counters into the ring, evicting the oldest sample when full.
     * Driven by the scheduler at {@code failover.dashboard.history.sample-interval-seconds}.
     */
    @Scheduled(
            fixedRateString = "${failover.dashboard.history.sample-interval-seconds:15}",
            timeUnit = TimeUnit.SECONDS)
    public void sample() {
        MetricsSummary summary = metricsService.metricsSummary();
        ApiKpis overall = summary.overall();
        Map<String, Long> failoverByApi = new LinkedHashMap<>();
        for (ApiKpis api : summary.perApi()) {
            failoverByApi.put(api.name(), api.failoverInvoked());
        }
        SeriesPoint point = new SeriesPoint(
                System.currentTimeMillis(),
                overall.totalCalls(),
                overall.failoverInvoked(),
                overall.recovered(),
                overall.notRecovered(),
                overall.upstreamSuccess(),
                overall.failoverInvoked(),
                failoverByApi);
        synchronized (ring) {
            if (ring.size() >= capacity) {
                ring.removeFirst();
            }
            ring.addLast(point);
        }
    }

    /**
     * @param windowSec only return samples captured within this many seconds of now ({@code <= 0} returns all retained)
     * @return retained samples in chronological order
     */
    public List<SeriesPoint> series(long windowSec) {
        long floor = windowSec > 0 ? System.currentTimeMillis() - windowSec * 1000 : Long.MIN_VALUE;
        synchronized (ring) {
            List<SeriesPoint> out = new ArrayList<>(ring.size());
            for (SeriesPoint p : ring) {
                if (p.timestamp() >= floor) {
                    out.add(p);
                }
            }
            return out;
        }
    }
}
