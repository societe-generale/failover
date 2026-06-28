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
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import lombok.extern.slf4j.Slf4j;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * Default {@link SnapshotStore}: latest snapshot per instance in a {@link ConcurrentHashMap}, with a liveness
 * window so stale peers drop out of the aggregate. Zero infra; lost on restart — acceptable for the
 * shared-store tier where consistency is prioritised over durability (design §5).
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
    private final long livenessMillis;
    private final int maxInstances;
    private final LongSupplier nowMillis;

    /**
     * @param livenessMillis snapshots older than this are excluded from {@link #live()}
     * @param maxInstances   supported instance ceiling (warning only when exceeded)
     */
    public SnapshotStoreInmemory(long livenessMillis, int maxInstances) {
        this(livenessMillis, maxInstances, System::currentTimeMillis);
    }

    /** Test seam: inject a clock. */
    SnapshotStoreInmemory(long livenessMillis, int maxInstances, LongSupplier nowMillis) {
        this.livenessMillis = livenessMillis;
        this.maxInstances = maxInstances;
        this.nowMillis = nowMillis;
    }

    @Override
    public void upsert(ClusterSnapshot snapshot) {
        String id = snapshot.instanceId();
        if (!byInstance.containsKey(id) && byInstance.size() >= maxInstances) {
            log.warn("Failover shared-store has {} reporting instances (max-instances={}); '{}' exceeds the supported "
                    + "ceiling. Consider cluster.mode=prometheus for clusters this large.", byInstance.size(), maxInstances, id);
        }
        byInstance.put(id, new Entry(snapshot.summary(), nowMillis.getAsLong()));
    }

    @Override
    public List<MetricsSummary> live() {
        long cutoff = nowMillis.getAsLong() - livenessMillis;
        List<MetricsSummary> out = new ArrayList<>();
        for (Entry e : byInstance.values()) {
            if (e.receivedAtMs() >= cutoff) {
                out.add(e.summary());
            }
        }
        return out;
    }

    @Override
    public List<InstanceMetrics> liveInstances() {
        long cutoff = nowMillis.getAsLong() - livenessMillis;
        List<InstanceMetrics> out = new ArrayList<>();
        for (Map.Entry<String, Entry> e : byInstance.entrySet()) {
            if (e.getValue().receivedAtMs() >= cutoff) {
                out.add(new InstanceMetrics(e.getKey(), e.getValue().receivedAtMs(), e.getValue().summary()));
            }
        }
        return out;
    }

    @Override
    public int liveCount() {
        long cutoff = nowMillis.getAsLong() - livenessMillis;
        int n = 0;
        for (Entry e : byInstance.values()) {
            if (e.receivedAtMs() >= cutoff) {
                n++;
            }
        }
        return n;
    }

    @Override
    public long newestEpochMs() {
        long cutoff = nowMillis.getAsLong() - livenessMillis;
        long newest = 0;
        for (Entry e : byInstance.values()) {
            if (e.receivedAtMs() >= cutoff && e.receivedAtMs() > newest) {
                newest = e.receivedAtMs();
            }
        }
        return newest;
    }
}
