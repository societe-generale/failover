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

package com.societegenerale.failover.dashboard.web;

import com.societegenerale.failover.dashboard.metrics.source.MetricsSource;

import com.societegenerale.failover.observable.metrics.ApiHealth;
import com.societegenerale.failover.observable.metrics.ExceptionStat;
import com.societegenerale.failover.observable.metrics.InstanceMetrics;
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import com.societegenerale.failover.observable.metrics.SeriesPoint;
import com.societegenerale.failover.observable.metrics.SourceInfo;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.Map;

/**
 * Read-only metrics endpoints, mapped under the same {@code base-path/api} namespace as the config API.
 *
 * <p>Registered only when a {@link io.micrometer.core.instrument.MeterRegistry} is present: without
 * Micrometer the config view still works and the metrics view degrades gracefully (design doc §3).
 *
 * <p>The figures are obtained through a {@link MetricsSource} rather than the registry directly, so the
 * same endpoints can be backed by a single instance (default) or a cluster-wide aggregate (see the
 * distributed-dashboard design). {@code /metrics/source} exposes that provenance for the UI badge.
 *
 * @author Anand Manissery
 */
@RestController
@RequestMapping("${failover.dashboard.base-path:/failover-dashboard}/api")
public class DashboardMetricsController {

    private final MetricsSource metricsSource;

    public DashboardMetricsController(MetricsSource metricsSource) {
        this.metricsSource = metricsSource;
    }

    @GetMapping("/metrics")
    public MetricsSummary metrics() {
        return metricsSource.summary();
    }

    @GetMapping("/health")
    public List<ApiHealth> health() {
        return metricsSource.health();
    }

    /** Provenance of the metrics (mode, instances reporting, freshness) for the UI source badge. */
    @GetMapping("/metrics/source")
    public SourceInfo source() {
        return metricsSource.info();
    }

    /**
     * Trend samples for the timeline chart. Local mode serves the in-memory ring (empty when history is
     * disabled); Prometheus mode derives a cluster-wide, reload-surviving trend via {@code query_range}.
     *
     * @param windowSec only samples within this many seconds of now ({@code <= 0} ⇒ all retained)
     */
    @GetMapping("/metrics/series")
    public List<SeriesPoint> series(@RequestParam(name = "windowSec", defaultValue = "300") long windowSec) {
        return metricsSource.series(windowSec);
    }

    /**
     * Per-instance metrics for the cluster Instances view — one entry per reporting member. Empty for sources
     * without a per-instance dimension (the UI then hides the Instances tab).
     */
    @GetMapping("/instances")
    public List<InstanceMetrics> instances() {
        return metricsSource.instances();
    }

    /**
     * Per-failover-endpoint exception breakdown: endpoint name → list of (type, count), sorted by count descending.
     * Empty map when the backing source cannot provide per-endpoint exception data (e.g. shared-store).
     */
    @GetMapping("/metrics/exceptions")
    public Map<String, List<ExceptionStat>> exceptionsByApi() {
        return metricsSource.exceptionsByApi();
    }
}
