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

import com.societegenerale.failover.dashboard.metrics.InstanceMetrics;
import com.societegenerale.failover.dashboard.metrics.MetricsSummary;

import java.util.List;

/**
 * Where pushed per-instance {@link ClusterSnapshot}s are held for the {@code shared-store} tier. The default
 * {@link SnapshotStoreInmemory} keeps the latest snapshot per instance in memory; a {@code @ConditionalOnMissingBean}
 * lets a consumer supply a durable (JDBC) or distributed (Redis/Hazelcast) implementation without touching the
 * source or UI.
 *
 * <p><strong>Consistency over durability (design §5.3):</strong> exactly one snapshot per instance (latest wins on
 * {@code upsert}, so a duplicate push never double-counts), and {@link #live()} returns only snapshots fresh within
 * the liveness window — stale peers are dropped from the aggregate, never silently summed.
 *
 * @author Anand Manissery
 */
public interface SnapshotStore {

    /** Records (or replaces) the latest snapshot for the snapshot's instance, stamping the current receive time. */
    void upsert(ClusterSnapshot snapshot);

    /** The freshest snapshot per instance whose age is within the liveness window. */
    List<MetricsSummary> live();

    /**
     * The live snapshots as per-instance entries (id + last-seen + that instance's summary), for the dashboard's
     * Instances view. Same liveness filtering as {@link #live()}.
     *
     * @return one {@link InstanceMetrics} per live instance
     */
    List<InstanceMetrics> liveInstances();

    /** Number of instances currently reporting (live within the liveness window). */
    int liveCount();

    /** Epoch-millis of the most recent live snapshot, or {@code 0} when none are live. */
    long newestEpochMs();
}
