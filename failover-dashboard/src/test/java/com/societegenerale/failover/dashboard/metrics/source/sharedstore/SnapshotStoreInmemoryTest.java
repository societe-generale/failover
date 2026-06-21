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

import com.societegenerale.failover.dashboard.metrics.ApiKpis;
import com.societegenerale.failover.dashboard.metrics.Latency;
import com.societegenerale.failover.dashboard.metrics.MetricsSummary;
import com.societegenerale.failover.dashboard.metrics.source.DashboardKpis;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotStoreInmemoryTest {

    private final AtomicLong now = new AtomicLong(10_000);

    private SnapshotStoreInmemory store(long livenessMs, int maxInstances) {
        return new SnapshotStoreInmemory(livenessMs, maxInstances, now::get);
    }

    private static MetricsSummary summaryFor(String name, long success) {
        ApiKpis k = DashboardKpis.build(name, name, success, 0, 0, 0, 0, 0, new Latency(0, 0, 0, 0));
        return new MetricsSummary(k, List.of(k), List.of(), 0L);
    }

    @Test
    void upsertKeepsLatestPerInstance() {
        SnapshotStoreInmemory store = store(60_000, 10);
        store.upsert(new ClusterSnapshot("i1", summaryFor("a", 1)));
        store.upsert(new ClusterSnapshot("i1", summaryFor("a", 5)));   // replaces

        assertThat(store.liveCount()).isEqualTo(1);
        assertThat(store.live()).singleElement()
                .satisfies(s -> assertThat(s.perApi().getFirst().upstreamSuccess()).isEqualTo(5));
    }

    @Test
    void liveExcludesSnapshotsOlderThanTheLivenessWindow() {
        SnapshotStoreInmemory store = store(1_000, 10);
        store.upsert(new ClusterSnapshot("i1", summaryFor("a", 1)));   // at t=10000
        now.set(10_500);
        store.upsert(new ClusterSnapshot("i2", summaryFor("a", 2)));   // at t=10500
        now.set(11_200);                                               // i1 now 1200ms old (> 1000), i2 700ms

        assertThat(store.liveCount()).isEqualTo(1);
        assertThat(store.live()).singleElement()
                .satisfies(s -> assertThat(s.perApi().getFirst().upstreamSuccess()).isEqualTo(2));
        assertThat(store.newestEpochMs()).isEqualTo(10_500);
    }

    @Test
    void newestEpochIsZeroWhenNothingLive() {
        SnapshotStoreInmemory store = store(1_000, 10);
        store.upsert(new ClusterSnapshot("i1", summaryFor("a", 1)));
        now.addAndGet(5_000);   // expire

        assertThat(store.liveCount()).isZero();
        assertThat(store.newestEpochMs()).isZero();
        assertThat(store.live()).isEmpty();
    }

    @Test
    void recordsBeyondMaxInstancesStillStoredAsWarnOnlyGuard() {
        SnapshotStoreInmemory store = store(60_000, 2);
        store.upsert(new ClusterSnapshot("i1", summaryFor("a", 1)));
        store.upsert(new ClusterSnapshot("i2", summaryFor("a", 1)));
        store.upsert(new ClusterSnapshot("i3", summaryFor("a", 1)));   // beyond ceiling

        assertThat(store.liveCount()).isEqualTo(3);
    }

    @Test
    void reUpsertExistingInstanceAtCeilingDoesNotWarnOrGrow() {
        SnapshotStoreInmemory store = store(60_000, 1);
        store.upsert(new ClusterSnapshot("i1", summaryFor("a", 1)));
        store.upsert(new ClusterSnapshot("i1", summaryFor("a", 2)));   // same id, at ceiling — update, no growth

        assertThat(store.liveCount()).isEqualTo(1);
    }

    @Test
    void liveInstancesCarryIdAndExcludeStale() {
        SnapshotStoreInmemory store = store(1_000, 10);
        store.upsert(new ClusterSnapshot("i1", summaryFor("a", 1)));   // t=10000
        now.set(10_400);
        store.upsert(new ClusterSnapshot("i2", summaryFor("a", 2)));   // t=10400
        now.set(11_100);                                               // i1 1100ms old (>1000) → excluded

        assertThat(store.liveInstances())
                .singleElement()
                .satisfies(im -> {
                    assertThat(im.instanceId()).isEqualTo("i2");
                    assertThat(im.lastSeenEpochMs()).isEqualTo(10_400);
                    assertThat(im.summary().perApi().getFirst().upstreamSuccess()).isEqualTo(2);
                });
    }

    @Test
    void liveInstancesEmptyWhenAllStale() {
        SnapshotStoreInmemory store = store(1_000, 10);
        store.upsert(new ClusterSnapshot("i1", summaryFor("a", 1)));
        now.addAndGet(5_000);

        assertThat(store.liveInstances()).isEmpty();
    }
}
