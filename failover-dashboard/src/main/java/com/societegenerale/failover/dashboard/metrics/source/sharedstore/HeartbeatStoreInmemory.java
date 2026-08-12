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

import com.societegenerale.failover.core.clock.DefaultFailoverClock;
import com.societegenerale.failover.core.clock.FailoverClock;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.Comparator;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory {@link HeartbeatStore}: last-seen epoch per instance in a {@link ConcurrentHashMap}.
 * Zero infra; process-local and lost on restart — consistent with {@link SnapshotStoreInmemory}.
 *
 * <p><strong>Bounded.</strong> Heartbeats arrive from the network at high frequency and carry a
 * caller-supplied instance id, so an unbounded map here is a permanent leak: ordinary pod churn
 * leaves one dead entry per rolled instance forever, and a looping or hostile peer inventing fresh
 * ids fills the heap. Two bounds apply, mirroring {@link SnapshotStoreInmemory}:
 *
 * <ul>
 *   <li><b>Age</b> — entries not refreshed within {@code retention} are dropped. Retention is meant to
 *       match {@code cluster.shared-store.instance-retention}, the window after which
 *       {@link SnapshotStore} retires an instance from {@link SnapshotStore#allInstances()}. Past that
 *       point nothing ever looks the heartbeat up again, so dropping it changes no reported status.
 *       A retention of zero (or negative) disables age-based pruning, matching
 *       {@link SnapshotStoreInmemory}'s contract.</li>
 *   <li><b>Size</b> — at most {@link #MAX_INSTANCES} entries. Past the cap the oldest heartbeat is
 *       evicted to admit a new instance. Unlike a retired snapshot, a dropped heartbeat costs nothing
 *       cumulative: the instance simply reads {@code UNKNOWN} instead of {@code LIVE}/{@code DOWN}
 *       until its next ping. The cap exists so a burst cannot outrun age-based pruning.</li>
 * </ul>
 *
 * @author Anand Manissery
 */
@Slf4j
public class HeartbeatStoreInmemory implements HeartbeatStore {

    /** Hard ceiling on tracked instances; beyond it the oldest heartbeat is evicted. */
    static final int MAX_INSTANCES = 10_000;

    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final long retentionMillis;
    private final FailoverClock clock;

    /** Creates a store that never prunes by age, on the system clock (programmatic/test convenience). */
    public HeartbeatStoreInmemory() {
        this(Duration.ZERO);
    }

    /**
     * Creates a new store on the system clock.
     *
     * @param retention drop heartbeats not refreshed within this window; {@code 0} (or negative)
     *                  keeps every heartbeat until the size cap forces an eviction
     */
    public HeartbeatStoreInmemory(Duration retention) {
        this(retention, new DefaultFailoverClock());
    }

    /**
     * Creates a new store.
     *
     * @param retention drop heartbeats not refreshed within this window; {@code 0} (or negative)
     *                  keeps every heartbeat until the size cap forces an eviction
     * @param clock     time source for stamping and ageing heartbeats — the framework's
     *                  {@link FailoverClock} rather than a direct {@code System.currentTimeMillis()}
     *                  call, so a co-located deployment ages heartbeats on the same clock the rest of
     *                  failover uses for expiry, and tests can drive it
     */
    public HeartbeatStoreInmemory(Duration retention, FailoverClock clock) {
        this.retentionMillis = retention == null ? 0 : retention.toMillis();
        this.clock = clock;
    }

    @Override
    public void record(String instanceId) {
        long now = clock.now().toEpochMilli();
        pruneExpired(now);
        if (lastSeen.size() >= MAX_INSTANCES && !lastSeen.containsKey(instanceId)) {
            evictOldest();
        }
        lastSeen.put(instanceId, now);
    }

    @Override
    public Long lastSeen(String instanceId) {
        return lastSeen.get(instanceId);
    }

    /** Current number of tracked instances (diagnostics / tests). */
    int size() {
        return lastSeen.size();
    }

    /** Drops entries not refreshed within the retention window; no-op when retention is disabled. */
    private void pruneExpired(long now) {
        if (retentionMillis <= 0) {
            return;
        }
        long cutoff = now - retentionMillis;
        lastSeen.entrySet().removeIf(entry -> entry.getValue() < cutoff);
    }

    /**
     * Evicts the least-recently-seen entry so a new instance can be admitted at the cap. Racy by
     * design: concurrent writers may each pick the same victim or admit one entry over the cap, which
     * is acceptable for a liveness cache — the cap is a heap bound, not an invariant.
     */
    private void evictOldest() {
        lastSeen.entrySet().stream()
                .min(Comparator.comparingLong(Map.Entry::getValue))
                .map(Map.Entry::getKey)
                .ifPresent(oldest -> {
                    lastSeen.remove(oldest);
                    log.warn("Failover heartbeat store is at capacity ({} instances); evicting the oldest "
                            + "heartbeat '{}'. It will read UNKNOWN until its next ping. This many reporting "
                            + "instances is well past the shared-store design point — consider "
                            + "cluster.mode=prometheus.", MAX_INSTANCES, oldest);
                });
    }
}
