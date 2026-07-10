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

import com.societegenerale.failover.observable.metrics.ClusterSnapshot;
import com.societegenerale.failover.observable.metrics.ConfigEntry;
import com.societegenerale.failover.observable.metrics.InstanceMetrics;
import com.societegenerale.failover.observable.metrics.MetricsSummary;

import java.util.List;

/**
 * Where pushed per-instance {@link ClusterSnapshot}s are held for the {@code shared-store} tier. The default
 * {@link SnapshotStoreInmemory} keeps the latest snapshot per instance in memory; a {@code @ConditionalOnMissingBean}
 * lets a consumer supply a durable (JDBC) or distributed (Redis/Hazelcast) implementation without touching the
 * source or UI.
 *
 * <p><strong>Counts are never dropped from the aggregate.</strong> Every instance contributes its last-known
 * values, so the dashboard never silently zeroes a quiet or crashed peer, and implementations must apply the
 * {@link SnapshotBaseline} carry-forward so a peer restart (counter reset) never shrinks the cluster totals.
 * An implementation may <em>retire</em> instances not seen within a retention window — removing them from
 * {@link #allInstances()} to keep the list bounded and readable — but their counts must keep contributing
 * through {@link #retiredAggregate()}. Staleness of live entries is visible through the per-instance
 * {@code lastSeenEpochMs} timestamp shown in the Instances tab.
 *
 * @author Anand Manissery
 */
public interface SnapshotStore {

    /**
     * Records (or replaces) the latest snapshot for the snapshot's instance, stamping the current receive time.
     *
     * @param snapshot the pushed peer snapshot
     */
    void upsert(ClusterSnapshot snapshot);

    /**
     * The accumulated counts of instances retired from {@link #allInstances()} (not seen within the
     * implementation's retention window), still contributing to the cluster aggregate so totals never drop.
     *
     * @return the retired-instances aggregate, or {@code null} when nothing has been retired (the default)
     */
    default MetricsSummary retiredAggregate() {
        return null;
    }

    /**
     * All non-retired per-instance entries (id + last-seen + summary).
     * {@link com.societegenerale.failover.observable.metrics.LiveStatus} is set to {@code UNKNOWN}
     * by the store — callers enrich it from a {@link HeartbeatStore} when liveness tracking is enabled.
     *
     * <p>Stale instances retain their last-known metric values so the cluster aggregate does not silently
     * drop when a peer crashes; those past the retention window move to {@link #retiredAggregate()}.
     * Per-instance staleness is visible through {@code lastSeenEpochMs}.
     *
     * @return one {@link InstanceMetrics} per known instance, with {@code liveStatus = UNKNOWN}
     */
    List<InstanceMetrics> allInstances();

    /**
     * The {@code @Failover} configuration, merged across every active instance (config is expected identical
     * cluster-wide; when instances disagree the most recently pushed value per name wins).
     *
     * @return one {@link ConfigEntry} per distinct failover name; empty when nothing has been pushed yet
     */
    default List<ConfigEntry> configEntries() {
        return List.of();
    }
}
