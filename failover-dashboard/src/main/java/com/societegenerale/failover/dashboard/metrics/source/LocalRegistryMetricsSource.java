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

package com.societegenerale.failover.dashboard.metrics.source;

import com.societegenerale.failover.dashboard.service.DashboardMetricsService;
import com.societegenerale.failover.dashboard.service.DashboardHistoryService;

import com.societegenerale.failover.dashboard.metrics.ApiHealth;
import com.societegenerale.failover.dashboard.metrics.InstanceMetrics;
import com.societegenerale.failover.dashboard.metrics.MetricsSummary;
import com.societegenerale.failover.dashboard.metrics.SeriesPoint;
import com.societegenerale.failover.dashboard.metrics.SourceInfo;

import java.util.List;

/**
 * Default {@link MetricsSource}: the figures come from <em>this</em> instance's in-process
 * {@code MeterRegistry}, via {@link DashboardMetricsService}. Correct for a single instance and for
 * development; behind a load balancer it reflects only the node that answered, which {@link #info()}
 * declares as {@code mode = "local"}, {@code instancesReporting = 1} so the UI shows a "this instance only"
 * badge (see the distributed-dashboard design document).
 *
 * <p>{@link #instances()} always returns a single entry for this instance so the Instances tab is
 * always visible (showing "1 of 1"), not only when a cluster source is configured.
 *
 * @author Anand Manissery
 */
public class LocalRegistryMetricsSource implements MetricsSource {

    /** {@link SourceInfo#instancesExpected()} sentinel: the cluster size is unknown in local mode. */
    static final int UNKNOWN = -1;

    private final DashboardMetricsService metricsService;
    private final DashboardHistoryService history;   // nullable — present only when history is enabled
    private final String instanceId;

    public LocalRegistryMetricsSource(DashboardMetricsService metricsService, DashboardHistoryService history,
                                      String instanceId) {
        this.metricsService = metricsService;
        this.history = history;
        this.instanceId = instanceId;
    }

    @Override
    public MetricsSummary summary() {
        return metricsService.metricsSummary();
    }

    @Override
    public List<ApiHealth> health() {
        return metricsService.health();
    }

    @Override
    public SourceInfo info() {
        return new SourceInfo("local", 1, UNKNOWN, System.currentTimeMillis(), false);
    }

    @Override
    public List<SeriesPoint> series(long windowSec) {
        return history != null ? history.series(windowSec) : List.of();
    }

    @Override
    public List<InstanceMetrics> instances() {
        return List.of(new InstanceMetrics(instanceId, System.currentTimeMillis(), summary()));
    }
}
