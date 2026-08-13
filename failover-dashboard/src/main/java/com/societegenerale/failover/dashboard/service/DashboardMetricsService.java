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

import com.societegenerale.failover.dashboard.config.DashboardProperties;
import com.societegenerale.failover.observable.metrics.ApiHealth;
import com.societegenerale.failover.observable.metrics.ApiKpis;
import com.societegenerale.failover.observable.metrics.ExceptionStat;
import com.societegenerale.failover.observable.metrics.FailoverMetricsSnapshotService;
import com.societegenerale.failover.observable.metrics.MetricsKpis;
import com.societegenerale.failover.observable.metrics.MetricsSummary;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Dashboard-level wrapper around {@link FailoverMetricsSnapshotService} that adds
 * health classification using the configured {@link DashboardProperties.Health} thresholds.
 *
 * <p>{@link #metricsSummary()} delegates entirely to the shared snapshot service, so the dashboard
 * and peer apps (running only the failover starter) produce identical KPI shapes from the same code.
 * {@link #health()} converts those KPIs into per-API {@link ApiHealth} using the dashboard's own
 * configured thresholds — which is why it lives here rather than in the shared module.
 *
 * <p>Both {@link #health()} and {@link #upstreamWindows()} classify against a rolling last-
 * {@code sampleSize}-calls window ({@link RollingHealthWindow}), not the lifetime-cumulative rate —
 * see {@link DashboardProperties.Health#sampleSize()}.
 *
 * @author Anand Manissery
 */
public class DashboardMetricsService {

    private final FailoverMetricsSnapshotService snapshotService;
    private final DashboardProperties properties;
    private final RollingHealthWindow window;

    /**
     * Creates a new service.
     *
     * @param snapshotService source of the current metrics summary
     * @param properties      the bound {@code failover.dashboard.*} properties (health thresholds)
     */
    public DashboardMetricsService(FailoverMetricsSnapshotService snapshotService, DashboardProperties properties) {
        this.snapshotService = snapshotService;
        this.properties = properties;
        this.window = new RollingHealthWindow(properties.health().sampleSize());
    }

    /**
     * Aggregates the current counter totals.
     *
     * @return per-API and overall KPIs derived from the current counter totals.
     */
    public MetricsSummary metricsSummary() {
        return snapshotService.metricsSummary();
    }

    /**
     * Per-failover-point exception counts.
     *
     * @return exception counts grouped by failover name
     */
    public Map<String, List<ExceptionStat>> exceptionsByApi() {
        return snapshotService.exceptionsByApi();
    }

    /**
     * Classifies each API's health against the configured thresholds, using the rolling windowed
     * healthy-rate (last {@link DashboardProperties.Health#sampleSize()} calls) rather than the
     * lifetime-cumulative rate — see {@link RollingHealthWindow}.
     *
     * @return per-API health classification, ordered by name.
     */
    public List<ApiHealth> health() {
        DashboardProperties.Health thresholds = properties.health();
        return snapshotService.metricsSummary().perApi().stream()
                .map(k -> MetricsKpis.classify(k.name(), sample(k).healthyRate(),
                        thresholds.degradedThreshold(), thresholds.unhealthyThreshold()))
                .toList();
    }

    /**
     * Rolling last-{@link DashboardProperties.Health#sampleSize()}-calls rates per failover point,
     * scored on the upstream call alone — powers the dashboard's Upstream call health cards, which are
     * deliberately not masked by how well failover recovered.
     *
     * @return windowed rates per failover name, ordered by name; empty when there is no traffic yet
     */
    public Map<String, UpstreamWindow> upstreamWindows() {
        Map<String, UpstreamWindow> result = new LinkedHashMap<>();
        for (ApiKpis k : snapshotService.metricsSummary().perApi()) {
            RollingHealthWindow.WindowedRates w = sample(k);
            result.put(k.name(), new UpstreamWindow(w.failoverRate(), w.healthyRate(), w.recoveryRate(), w.sampleCount()));
        }
        return result;
    }

    /** Feeds this poll's cumulative totals for {@code k} into the shared rolling window. */
    private RollingHealthWindow.WindowedRates sample(ApiKpis k) {
        return window.sample(k.name(), k.upstreamSuccess(), k.recovered(), k.notRecovered() + k.errors());
    }
}
