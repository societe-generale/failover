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
import com.societegenerale.failover.observable.metrics.LiveStatus;
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import com.societegenerale.failover.observable.metrics.MetricsSummaryAggregator;
import com.societegenerale.failover.observable.metrics.SnapshotBaseline;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.LongSupplier;

/**
 * Default {@link SnapshotStore}: latest snapshot per instance in memory, with the {@link SnapshotBaseline}
 * reset-aware carry-forward — a peer restart (counter reset) folds the pre-restart totals into a per-instance
 * baseline, so the served summary ({@code baseline + raw}) and the cluster aggregate never shrink.
 *
 * <p><strong>Bounded (design §5.4 spirit — this is not a TSDB).</strong> Instances not seen within
 * {@code instanceRetention} are <em>retired</em>: dropped from {@link #allInstances()} (so churned pod ids do not
 * grow the map and clutter the Instances tab forever) while their counts keep contributing through
 * {@link #retiredAggregate()}. A retired instance that reports again is restored with its history intact.
 * At most {@link #MAX_RETIRED} retired entries are kept individually; beyond that the oldest are compacted
 * into a single immutable tombstone aggregate. A retention of zero (or negative) disables retirement.
 *
 * <p>The supported small-cluster ceiling {@code maxInstances} is enforced as a loud warning (not a hard reject):
 * pushing beyond it still records, but signals the deployment has outgrown shared-store and should move to
 * {@code prometheus}.
 *
 * @author Anand Manissery
 */
@Slf4j
public class SnapshotStoreInmemory implements SnapshotStore {

    /** Retired entries kept individually (for reappearance) before compaction into the tombstone. */
    static final int MAX_RETIRED = 100;

    private record Entry(MetricsSummary raw, MetricsSummary baseline, long receivedAtMs, List<ConfigEntry> configEntries) {
    }

    /** Insertion-ordered so iteration is stable; retirement order = eviction order. */
    private final Map<String, Entry> active = new LinkedHashMap<>();
    private final Map<String, Entry> retired = new LinkedHashMap<>();
    private MetricsSummary tombstone;

    private final int maxInstances;
    private final long retentionMillis;
    private final LongSupplier nowMillis;

    /**
     * Retains instances forever (no retirement) — programmatic/test convenience.
     *
     * @param maxInstances supported small-cluster ceiling; beyond it a warning is logged
     */
    public SnapshotStoreInmemory(int maxInstances) {
        this(maxInstances, Duration.ZERO);
    }

    /**
     * Creates a new store.
     *
     * @param maxInstances      supported small-cluster ceiling; beyond it a warning is logged
     * @param instanceRetention retire instances not seen for this long; {@code 0} keeps every instance forever
     */
    public SnapshotStoreInmemory(int maxInstances, Duration instanceRetention) {
        this(maxInstances, instanceRetention, System::currentTimeMillis);
    }

    /** Test seam: inject a clock to control retirement age. */
    SnapshotStoreInmemory(int maxInstances, Duration instanceRetention, LongSupplier nowMillis) {
        this.maxInstances = maxInstances;
        this.retentionMillis = instanceRetention == null ? 0 : instanceRetention.toMillis();
        this.nowMillis = nowMillis;
    }

    @Override
    public synchronized void upsert(ClusterSnapshot snapshot) {
        retireExpired();
        String id = snapshot.instanceId();
        Entry previous = active.get(id);
        if (previous == null) {
            previous = retired.remove(id);   // reappearing peer — resume its history
            if (previous == null && active.size() >= maxInstances) {
                log.warn("Failover shared-store has {} reporting instances (max-instances={}); '{}' exceeds the supported "
                        + "ceiling. Consider cluster.mode=prometheus for clusters this large.", active.size(), maxInstances, id);
            }
        }
        MetricsSummary baseline = previous == null ? null
                : SnapshotBaseline.next(previous.raw(), previous.baseline(), snapshot.summary());
        active.put(id, new Entry(snapshot.summary(), baseline, nowMillis.getAsLong(), snapshot.configEntries()));
    }

    @Override
    public synchronized List<InstanceMetrics> allInstances() {
        retireExpired();
        List<InstanceMetrics> out = new ArrayList<>();
        for (Map.Entry<String, Entry> e : active.entrySet()) {
            Entry entry = e.getValue();
            out.add(new InstanceMetrics(e.getKey(), entry.receivedAtMs(),
                    SnapshotBaseline.combined(entry.baseline(), entry.raw()), LiveStatus.UNKNOWN));
        }
        return out;
    }

    @Override
    public synchronized MetricsSummary retiredAggregate() {
        retireExpired();
        if (tombstone == null && retired.isEmpty()) {
            return null;
        }
        List<MetricsSummary> parts = new ArrayList<>();
        if (tombstone != null) {
            parts.add(tombstone);
        }
        for (Entry entry : retired.values()) {
            parts.add(SnapshotBaseline.combined(entry.baseline(), entry.raw()));
        }
        return parts.size() == 1 ? parts.getFirst() : MetricsSummaryAggregator.merge(parts);
    }

    @Override
    public synchronized List<ConfigEntry> configEntries() {
        retireExpired();
        Map<String, ConfigEntry> byName = new LinkedHashMap<>();
        // Retired instances first, active last: config is expected identical cluster-wide, but if it ever
        // differs, the most recently reporting instance should win — and config must not disappear just
        // because every instance briefly retired (e.g. a rolling restart), unlike a churned-away tombstone.
        for (Entry entry : retired.values()) {
            for (ConfigEntry configEntry : entry.configEntries()) {
                byName.put(configEntry.name(), configEntry);
            }
        }
        for (Entry entry : active.values()) {
            for (ConfigEntry configEntry : entry.configEntries()) {
                byName.put(configEntry.name(), configEntry);   // last-seen instance wins per name
            }
        }
        return byName.values().stream().sorted(Comparator.comparing(ConfigEntry::name)).toList();
    }

    /** Current number of individually retained retired entries (diagnostics / tests). */
    synchronized int retiredCount() {
        return retired.size();
    }

    /** Moves instances not seen within the retention window to the retired set, compacting beyond the cap. */
    private void retireExpired() {
        if (retentionMillis <= 0) {
            return;
        }
        long cutoff = nowMillis.getAsLong() - retentionMillis;
        Iterator<Map.Entry<String, Entry>> it = active.entrySet().iterator();
        while (it.hasNext()) {
            Map.Entry<String, Entry> e = it.next();
            if (e.getValue().receivedAtMs() < cutoff) {
                it.remove();
                retired.put(e.getKey(), e.getValue());
                log.info("Failover shared-store retired instance '{}' (no snapshot for {}); its counts remain "
                        + "in the cluster aggregate.", e.getKey(), Duration.ofMillis(retentionMillis));
            }
        }
        while (retired.size() > MAX_RETIRED) {
            Iterator<Entry> oldest = retired.values().iterator();
            Entry entry = oldest.next();
            oldest.remove();
            MetricsSummary counts = SnapshotBaseline.combined(entry.baseline(), entry.raw());
            tombstone = tombstone == null ? counts : MetricsSummaryAggregator.merge(List.of(tombstone, counts));
        }
    }
}
