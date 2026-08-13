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

import java.util.List;

/**
 * Reset-aware carry-forward shared by every JDBC-writing side of the shared-store tier (consistency rule,
 * design §5.3) — the dashboard's own {@code SnapshotStoreJdbc} and any peer writing directly to the same
 * table (JDBC-direct publisher). Peer snapshots carry <em>cumulative</em> counter totals that reset to zero
 * when the peer restarts; without correction the cluster aggregate would drop by that instance's pre-restart
 * counts.
 *
 * <p>On each upsert the writer keeps the incoming snapshot as the instance's <em>raw</em> value and,
 * when a counter reset is detected (cumulative total went backwards), folds the previous raw snapshot
 * into a per-instance <em>baseline</em>. The summary served for the instance is {@code baseline + raw},
 * so the instant aggregate stays monotonic across peer restarts — matching the behaviour the series
 * sampler already guarantees for the trend graph.
 *
 * <p>Limitation: a reset is invisible if the peer regrows past its previous total between two pushes;
 * that window is one push interval (15s by default), the same limitation Prometheus {@code rate()} has.
 *
 * <p>Lives here (rather than the dashboard module) so both the dashboard-side JDBC store and a peer's
 * JDBC-direct publisher can share the same carry-forward logic without either depending on the other.
 *
 * @author Anand Manissery
 */
public final class SnapshotBaseline {

    private SnapshotBaseline() {
    }

    /**
     * Computes the baseline to store alongside an incoming snapshot.
     *
     * @param previousRaw      the instance's previously stored raw snapshot ({@code null} if first push)
     * @param previousBaseline the instance's current baseline ({@code null} if none accumulated yet)
     * @param incoming         the snapshot just pushed
     * @return the new baseline: unchanged unless {@code incoming} reveals a counter reset, in which case
     *         the previous raw totals are folded in; {@code null} when there is nothing to carry forward
     */
    public static MetricsSummary next(MetricsSummary previousRaw, MetricsSummary previousBaseline, MetricsSummary incoming) {
        if (previousRaw == null || !MetricsSummaryAggregator.isCounterReset(previousRaw, incoming)) {
            return previousBaseline;
        }
        return previousBaseline == null ? previousRaw
                : MetricsSummaryAggregator.merge(List.of(previousBaseline, previousRaw));
    }

    /**
     * Combines the carried baseline with the current raw snapshot.
     *
     * @param baseline the instance's carried baseline, or {@code null} if none accumulated yet
     * @param raw      the instance's current raw snapshot
     * @return the summary to serve for an instance: {@code baseline + raw} when a baseline exists,
     *         otherwise {@code raw} as-is
     */
    public static MetricsSummary combined(MetricsSummary baseline, MetricsSummary raw) {
        return baseline == null ? raw : MetricsSummaryAggregator.merge(List.of(baseline, raw));
    }
}
