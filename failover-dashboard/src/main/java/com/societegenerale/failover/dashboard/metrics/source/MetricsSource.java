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

import com.societegenerale.failover.observable.metrics.ApiHealth;
import com.societegenerale.failover.observable.metrics.ExceptionStat;
import com.societegenerale.failover.observable.metrics.InstanceMetrics;
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import com.societegenerale.failover.observable.metrics.SeriesPoint;
import com.societegenerale.failover.observable.metrics.SourceInfo;

import java.util.List;
import java.util.Map;

/**
 * Where the dashboard's metrics come from. This seam decouples the controllers from "read the local
 * {@code MeterRegistry}", so the same UI can be backed by a single instance (default) or, in a clustered
 * deployment, by a cluster-wide aggregate — see the distributed-dashboard design document.
 *
 * <p>P0 ships the default {@link LocalRegistryMetricsSource} (this instance's in-process registry). The
 * cluster-aware implementations (Prometheus query layer, shared snapshot store) plug in here later without
 * touching the controllers or the UI.
 *
 * @author Anand Manissery
 */
public interface MetricsSource {

    /** Global aggregate plus per-API KPIs. */
    MetricsSummary summary();

    /** Per-API health classification. */
    List<ApiHealth> health();

    /** Provenance of the above (mode, instances reporting, freshness) so the UI can label the figures. */
    SourceInfo info();

    /**
     * Trend samples for the timeline chart, newest last. {@code windowSec <= 0} means "all retained".
     * Local mode reads the optional in-memory ring (empty when history is disabled); Prometheus mode
     * derives a cluster-wide, reload-surviving trend from {@code query_range}.
     */
    List<SeriesPoint> series(long windowSec);

    /**
     * Per-instance metrics for the cluster's Instances view — one entry per reporting member, each with that
     * member's own summary and last-seen time.
     *
     * <p>The default returns empty (sources with no per-instance dimension — the UI then hides the tab).
     * {@code local} always returns exactly one entry (itself). {@code shared-store} always returns at least
     * one entry (the dashboard host itself plus any peer snapshots in the store). {@code prometheus} returns
     * one entry per instance label.
     *
     * @return per-instance metrics; never {@code null}; at least one element in any active source
     */
    default List<InstanceMetrics> instances() {
        return List.of();
    }

    /**
     * Per-failover-endpoint exception counts: endpoint name → list of (type, count), sorted by count descending.
     * Cluster-aware sources that cannot provide per-endpoint exception breakdowns return an empty map —
     * the UI chart is hidden when empty.
     */
    default Map<String, List<ExceptionStat>> exceptionsByApi() {
        return Map.of();
    }
}
