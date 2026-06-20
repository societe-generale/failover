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

package com.societegenerale.failover.dashboard;

import com.societegenerale.failover.dashboard.dto.ApiHealth;
import com.societegenerale.failover.dashboard.dto.MetricsSummary;
import com.societegenerale.failover.dashboard.dto.SourceInfo;

import java.util.List;

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
}
