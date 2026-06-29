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
import com.societegenerale.failover.observable.metrics.ExceptionStat;
import com.societegenerale.failover.observable.metrics.FailoverMetricsSnapshotService;
import com.societegenerale.failover.observable.metrics.MetricsKpis;
import com.societegenerale.failover.observable.metrics.MetricsSummary;

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
 * @author Anand Manissery
 */
public class DashboardMetricsService {

    private final FailoverMetricsSnapshotService snapshotService;
    private final DashboardProperties properties;

    public DashboardMetricsService(FailoverMetricsSnapshotService snapshotService, DashboardProperties properties) {
        this.snapshotService = snapshotService;
        this.properties = properties;
    }

    /**
     * @return per-API and overall KPIs derived from the current counter totals.
     */
    public MetricsSummary metricsSummary() {
        return snapshotService.metricsSummary();
    }

    public Map<String, List<ExceptionStat>> exceptionsByApi() {
        return snapshotService.exceptionsByApi();
    }

    /**
     * @return per-API health classification, ordered by name.
     */
    public List<ApiHealth> health() {
        DashboardProperties.Health thresholds = properties.health();
        return snapshotService.metricsSummary().perApi().stream()
                .map(k -> MetricsKpis.classify(k.name(), k.rates().healthyRate(),
                        thresholds.degradedThreshold(), thresholds.unhealthyThreshold()))
                .toList();
    }
}
