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
import com.societegenerale.failover.observable.metrics.InstanceMetrics;

import java.util.List;

/**
 * Where pushed per-instance {@link ClusterSnapshot}s are held for the {@code shared-store} tier. The default
 * {@link SnapshotStoreInmemory} keeps the latest snapshot per instance in memory; a {@code @ConditionalOnMissingBean}
 * lets a consumer supply a durable (JDBC) or distributed (Redis/Hazelcast) implementation without touching the
 * source or UI.
 *
 * <p>All snapshots are retained regardless of age — every instance always contributes its last-known values
 * to the cluster aggregate, so the dashboard never silently zeroes a quiet or crashed peer. Staleness is
 * visible through the per-instance {@code lastSeenEpochMs} timestamp shown in the Instances tab.
 *
 * @author Anand Manissery
 */
public interface SnapshotStore {

    /** Records (or replaces) the latest snapshot for the snapshot's instance, stamping the current receive time. */
    void upsert(ClusterSnapshot snapshot);

    /**
     * All known per-instance entries (id + last-seen + summary), regardless of snapshot age.
     * {@link com.societegenerale.failover.observable.metrics.LiveStatus} is set to {@code UNKNOWN}
     * by the store — callers enrich it from a {@link HeartbeatStore} when liveness tracking is enabled.
     *
     * <p>Stale instances retain their last-known metric values so the cluster aggregate does not silently
     * drop when a peer crashes. Per-instance staleness is visible through {@code lastSeenEpochMs}.
     *
     * @return one {@link InstanceMetrics} per known instance, with {@code liveStatus = UNKNOWN}
     */
    List<InstanceMetrics> allInstances();
}
