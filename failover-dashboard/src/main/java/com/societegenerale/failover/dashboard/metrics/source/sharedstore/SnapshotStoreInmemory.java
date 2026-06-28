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
import com.societegenerale.failover.observable.metrics.LiveStatus;
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default {@link SnapshotStore}: latest snapshot per instance in a {@link ConcurrentHashMap}.
 * All snapshots are retained — last-known values are always included in the aggregate so a quiet or
 * crashed peer never silently drops out. Staleness is visible through each instance's {@code lastSeenEpochMs}.
 *
 * <p>The supported small-cluster ceiling {@code maxInstances} is enforced as a loud warning (not a hard reject):
 * pushing beyond it still records, but signals the deployment has outgrown shared-store and should move to
 * {@code prometheus}.
 *
 * @author Anand Manissery
 */
@Slf4j
public class SnapshotStoreInmemory implements SnapshotStore {

    private record Entry(MetricsSummary summary, long receivedAtMs) {
    }

    private final Map<String, Entry> byInstance = new ConcurrentHashMap<>();
    private final int maxInstances;

    public SnapshotStoreInmemory(int maxInstances) {
        this.maxInstances = maxInstances;
    }

    @Override
    public void upsert(ClusterSnapshot snapshot) {
        String id = snapshot.instanceId();
        if (!byInstance.containsKey(id) && byInstance.size() >= maxInstances) {
            log.warn("Failover shared-store has {} reporting instances (max-instances={}); '{}' exceeds the supported "
                    + "ceiling. Consider cluster.mode=prometheus for clusters this large.", byInstance.size(), maxInstances, id);
        }
        byInstance.put(id, new Entry(snapshot.summary(), System.currentTimeMillis()));
    }

    @Override
    public List<InstanceMetrics> allInstances() {
        List<InstanceMetrics> out = new ArrayList<>();
        for (Map.Entry<String, Entry> e : byInstance.entrySet()) {
            out.add(new InstanceMetrics(e.getKey(), e.getValue().receivedAtMs(), e.getValue().summary(), LiveStatus.UNKNOWN));
        }
        return out;
    }
}
