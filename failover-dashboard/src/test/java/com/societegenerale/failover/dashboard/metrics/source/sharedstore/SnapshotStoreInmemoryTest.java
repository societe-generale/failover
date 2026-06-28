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

import com.societegenerale.failover.observable.metrics.ApiKpis;
import com.societegenerale.failover.observable.metrics.ClusterSnapshot;
import com.societegenerale.failover.observable.metrics.Latency;
import com.societegenerale.failover.observable.metrics.LiveStatus;
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import com.societegenerale.failover.observable.metrics.MetricsKpis;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotStoreInmemoryTest {

    private static MetricsSummary summaryFor(String name, long success) {
        ApiKpis k = MetricsKpis.build(name, name, success, 0, 0, 0, 0, 0, new Latency(0, 0, 0, 0));
        return new MetricsSummary(k, List.of(k), List.of(), 0L);
    }

    @Test
    void upsertKeepsLatestPerInstance() {
        SnapshotStoreInmemory store = new SnapshotStoreInmemory(10);
        store.upsert(new ClusterSnapshot("i1", summaryFor("a", 1)));
        store.upsert(new ClusterSnapshot("i1", summaryFor("a", 5)));   // replaces

        assertThat(store.allInstances()).singleElement()
                .satisfies(im -> assertThat(im.summary().perApi().getFirst().upstreamSuccess()).isEqualTo(5));
    }

    @Test
    void allInstancesReturnsAllWithUnknownStatus() {
        SnapshotStoreInmemory store = new SnapshotStoreInmemory(10);
        store.upsert(new ClusterSnapshot("i1", summaryFor("a", 1)));
        store.upsert(new ClusterSnapshot("i2", summaryFor("a", 2)));

        assertThat(store.allInstances()).hasSize(2)
                .allMatch(im -> im.liveStatus() == LiveStatus.UNKNOWN);
    }

    @Test
    void recordsBeyondMaxInstancesStillStoredAsWarnOnlyGuard() {
        SnapshotStoreInmemory store = new SnapshotStoreInmemory(2);
        store.upsert(new ClusterSnapshot("i1", summaryFor("a", 1)));
        store.upsert(new ClusterSnapshot("i2", summaryFor("a", 1)));
        store.upsert(new ClusterSnapshot("i3", summaryFor("a", 1)));   // beyond ceiling — warn only

        assertThat(store.allInstances()).hasSize(3);
    }

    @Test
    void reUpsertExistingInstanceAtCeilingDoesNotWarnOrGrow() {
        SnapshotStoreInmemory store = new SnapshotStoreInmemory(1);
        store.upsert(new ClusterSnapshot("i1", summaryFor("a", 1)));
        store.upsert(new ClusterSnapshot("i1", summaryFor("a", 2)));   // same id — update, no growth

        assertThat(store.allInstances()).singleElement()
                .satisfies(im -> assertThat(im.summary().perApi().getFirst().upstreamSuccess()).isEqualTo(2));
    }

    @Test
    void allInstancesAlwaysIncludesOldSnapshots() {
        // Old data is retained forever — stale peer still contributes last-known values to the aggregate.
        // Per-instance staleness is visible through lastSeenEpochMs shown in the Instances tab.
        SnapshotStoreInmemory store = new SnapshotStoreInmemory(10);
        store.upsert(new ClusterSnapshot("i1", summaryFor("a", 42)));

        assertThat(store.allInstances())
                .singleElement()
                .satisfies(im -> {
                    assertThat(im.instanceId()).isEqualTo("i1");
                    assertThat(im.summary().perApi().getFirst().upstreamSuccess()).isEqualTo(42);
                    assertThat(im.liveStatus()).isEqualTo(LiveStatus.UNKNOWN);
                });
    }
}
