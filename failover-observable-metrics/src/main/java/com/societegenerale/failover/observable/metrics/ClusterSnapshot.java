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
 * The payload one instance pushes to the dashboard in {@code cluster.mode=shared-store}: its own identity plus
 * the local {@link MetricsSummary} snapshot and its {@code @Failover} configuration. The receive time is
 * stamped server-side on ingest (the peer clock is not trusted for liveness), so it is intentionally not part
 * of this record.
 *
 * @param instanceId    the emitting instance's identifier (e.g. {@code orders-service:host-7})
 * @param summary       that instance's local KPI snapshot
 * @param configEntries that instance's {@code @Failover} configuration, from {@link FailoverConfigSnapshotService};
 *                      never {@code null} — a missing value (older peer, or the 2-arg constructor) normalises to empty
 * @author Anand Manissery
 */
public record ClusterSnapshot(String instanceId, MetricsSummary summary, List<ConfigEntry> configEntries) {

    /** Normalises a {@code null} {@code configEntries} (e.g. an older stored row) to an empty list. */
    public ClusterSnapshot {
        configEntries = configEntries == null ? List.of() : configEntries;
    }

    /**
     * Backward-compat: no configuration payload.
     *
     * @param instanceId the emitting instance's identifier
     * @param summary    that instance's local KPI snapshot
     */
    public ClusterSnapshot(String instanceId, MetricsSummary summary) {
        this(instanceId, summary, List.of());
    }
}
