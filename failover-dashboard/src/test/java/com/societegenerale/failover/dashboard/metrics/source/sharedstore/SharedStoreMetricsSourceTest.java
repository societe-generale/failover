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

import com.societegenerale.failover.dashboard.config.DashboardProperties;
import com.societegenerale.failover.observable.metrics.ApiHealth;
import com.societegenerale.failover.observable.metrics.ClusterSnapshot;
import com.societegenerale.failover.observable.metrics.ApiKpis;
import com.societegenerale.failover.observable.metrics.Latency;
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import com.societegenerale.failover.observable.metrics.SeriesPoint;
import com.societegenerale.failover.observable.metrics.SourceInfo;
import com.societegenerale.failover.observable.metrics.ExceptionStat;
import com.societegenerale.failover.observable.metrics.InstanceMetrics;
import com.societegenerale.failover.observable.metrics.MetricsKpis;
import com.societegenerale.failover.dashboard.metrics.source.MetricsSource;
import org.junit.jupiter.api.Test;

import java.util.List;

import static java.lang.System.currentTimeMillis;
import static org.assertj.core.api.Assertions.assertThat;

class SharedStoreMetricsSourceTest {

    private static final DashboardProperties.Health THRESHOLDS = new DashboardProperties.Health(0.99, 0.90);

    private static MetricsSummary snapshot(String name, long success, long recovered, long notRecovered,
                                           long errors, List<ExceptionStat> exceptions) {
        ApiKpis k = MetricsKpis.build(name, name, success, recovered, notRecovered, errors, 0, 0,
                new Latency(1, 2, 3, 4));
        return new MetricsSummary(k, List.of(k), exceptions, 0L);
    }

    /** Fallback marker source — used to prove fallback is hit when no instance is live. */
    private static MetricsSource fallback(String marker) {
        return new MetricsSource() {
            public MetricsSummary summary() {
                ApiKpis k = MetricsKpis.build(marker, marker, 1, 0, 0, 0, 0, 0, new Latency(0, 0, 0, 0));
                return new MetricsSummary(k, List.of(k), List.of(), 0L);
            }
            public List<ApiHealth> health() { return List.of(new ApiHealth(marker, "HEALTHY", 1.0)); }
            public SourceInfo info() { return new SourceInfo("local", 1, -1, 0L, false); }
            public List<SeriesPoint> series(long windowSec) { return List.of(); }
        };
    }

    @Test
    void mergesCountersAcrossLiveInstances() {
        SnapshotStore store = stubStore(
                snapshot("country", 10, 2, 1, 0, List.of(new ExceptionStat("IOEx", 3))),
                snapshot("country", 20, 3, 0, 1, List.of(new ExceptionStat("IOEx", 2), new ExceptionStat("TimeoutEx", 5))));
        SharedStoreMetricsSource source = new SharedStoreMetricsSource(store, THRESHOLDS, fallback("local"), 10);

        MetricsSummary merged = source.summary();

        assertThat(merged.perApi()).singleElement().satisfies(k -> {
            assertThat(k.name()).isEqualTo("country");
            assertThat(k.upstreamSuccess()).isEqualTo(30);
            assertThat(k.recovered()).isEqualTo(5);
            assertThat(k.notRecovered()).isEqualTo(1);
            assertThat(k.errors()).isEqualTo(1);
            assertThat(k.totalCalls()).isEqualTo(37); // 30 success + (5+1+1) failover
        });
        assertThat(merged.topExceptions())
                .extracting(ExceptionStat::type, ExceptionStat::count)
                .containsExactlyInAnyOrder(org.assertj.core.groups.Tuple.tuple("IOEx", 5L),
                        org.assertj.core.groups.Tuple.tuple("TimeoutEx", 5L));
    }

    @Test
    void infoReportsSharedStoreModeAndLiveCount() {
        SnapshotStore store = stubStore(snapshot("country", 1, 0, 0, 0, List.of()));
        SharedStoreMetricsSource source = new SharedStoreMetricsSource(store, THRESHOLDS, fallback("local"), 10);

        SourceInfo info = source.info();

        assertThat(info.mode()).isEqualTo("shared-store");
        assertThat(info.instancesReporting()).isEqualTo(1);
        assertThat(info.instancesExpected()).isEqualTo(10);
    }

    @Test
    void fallsBackToLocalWhenNoInstanceIsLive() {
        SharedStoreMetricsSource source = new SharedStoreMetricsSource(stubStore(), THRESHOLDS, fallback("local"), 10);

        assertThat(source.summary().perApi()).singleElement()
                .satisfies(k -> assertThat(k.name()).isEqualTo("local"));
        assertThat(source.health()).singleElement().satisfies(h -> assertThat(h.name()).isEqualTo("local"));
    }

    private static SnapshotStore stubStore(MetricsSummary... live) {
        List<MetricsSummary> list = List.of(live);
        return new SnapshotStore() {
            public void upsert(ClusterSnapshot snapshot) { /* no-op: stub is pre-seeded via the constructor */ }
            public List<InstanceMetrics> allInstances() {
                List<InstanceMetrics> out = new java.util.ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    out.add(new InstanceMetrics("instance-" + i, 100L + i, list.get(i)));
                }
                return out;
            }
        };
    }

    @Test
    void healthClassifiesEachApiWhenInstancesAreLive() {
        SnapshotStore store = stubStore(
                snapshot("country", 100, 0, 0, 0, List.of()),   // 100% healthy
                snapshot("fx", 50, 5, 40, 5, List.of()));       // degraded/unhealthy
        SharedStoreMetricsSource source = new SharedStoreMetricsSource(store, THRESHOLDS, fallback("local"), 10);

        assertThat(source.health()).extracting(ApiHealth::name).contains("country", "fx");
    }

    @Test
    void seriesReadsTheRingWhenPresentElseFallsBackToLocal() {
        // with a series ring → reads it
        ClusterSeriesStore ring = new ClusterSeriesStore(new RetentionPolicy(java.time.Duration.ofDays(1), 100));
        ring.append(new SeriesPoint(currentTimeMillis(), 5, 1, 1, 0, 4, 1, java.util.Map.of()));
        SharedStoreMetricsSource withRing = new SharedStoreMetricsSource(stubStore(), THRESHOLDS, fallback("local"), 10, ring);
        assertThat(withRing.series(0)).singleElement().satisfies(p -> assertThat(p.calls()).isEqualTo(5));

        // without a ring (4-arg ctor → null) → falls back to local source
        MetricsSource fb = fallback("local");
        SharedStoreMetricsSource noRing = new SharedStoreMetricsSource(stubStore(), THRESHOLDS, fb, 10);
        assertThat(noRing.series(0)).isEqualTo(fb.series(0));   // local fallback (empty)
    }

    @Test
    void infoUsesNewestSnapshotTimeWhenPresentElseNow() {
        SharedStoreMetricsSource live = new SharedStoreMetricsSource(
                stubStore(snapshot("country", 1, 0, 0, 0, List.of())), THRESHOLDS, fallback("local"), 10);
        assertThat(live.info().mode()).isEqualTo("shared-store");
        // stub allInstances() returns instance-0 with lastSeenEpochMs=100L (100L + 0)
        assertThat(live.info().asOfEpochMs()).isEqualTo(100L);

        SharedStoreMetricsSource empty = new SharedStoreMetricsSource(
                stubStore(), THRESHOLDS, fallback("local"), 10);
        assertThat(empty.info().asOfEpochMs()).isGreaterThan(0L);       // no instances → falls back to now
        assertThat(empty.info().instancesReporting()).isZero();
    }

    @Test
    void mergeWithZeroCallInstanceYieldsZeroLatencyNotNan() {
        // a snapshot whose API has no calls → zero latency weight → mean must be 0.0, never NaN
        SnapshotStore store = stubStore(snapshot("idle", 0, 0, 0, 0, List.of()));
        SharedStoreMetricsSource source = new SharedStoreMetricsSource(store, THRESHOLDS, fallback("local"), 10);

        MetricsSummary merged = source.summary();
        assertThat(merged.perApi()).singleElement().satisfies(k -> {
            assertThat(k.totalCalls()).isZero();
            assertThat(k.latency().storeMeanMs()).isZero();
            assertThat(k.latency().recoverMeanMs()).isZero();
        });
        assertThat(merged.overall().latency().recoverMeanMs()).isZero();
    }

    @Test
    void instancesReturnsPerInstanceMetricsFromTheStore() {
        SnapshotStore store = stubStore(
                snapshot("country", 10, 2, 1, 0, List.of()),
                snapshot("country", 20, 3, 0, 1, List.of()));
        SharedStoreMetricsSource source = new SharedStoreMetricsSource(store, THRESHOLDS, fallback("local"), 10);

        assertThat(source.instances())
                .extracting(InstanceMetrics::instanceId)
                .containsExactly("instance-0", "instance-1");
    }

    @Test
    void instancesIsEmptyWhenNoSnapshotsPushed() {
        // No peers have pushed yet → instances tab empty, not polluted with dashboard host
        SharedStoreMetricsSource source = new SharedStoreMetricsSource(stubStore(), THRESHOLDS, fallback("local"), 10);

        assertThat(source.instances()).isEmpty();
        assertThat(source.info().instancesReporting()).isZero();
        // summary/health still fall back to local when store empty
        assertThat(source.summary().perApi()).singleElement()
                .satisfies(k -> assertThat(k.name()).isEqualTo("local"));
    }

    @Test
    void livenessTrackingDisabled_allInstancesHaveUnknownStatus() {
        SnapshotStore store = stubStore(snapshot("country", 10, 0, 0, 0, List.of()));
        SharedStoreMetricsSource source = new SharedStoreMetricsSource(
                store, THRESHOLDS, fallback("local"), 10, null, null, 0);

        assertThat(source.instances())
                .extracting(InstanceMetrics::liveStatus)
                .containsOnly(com.societegenerale.failover.observable.metrics.LiveStatus.UNKNOWN);
    }

    @Test
    void livenessTrackingEnabled_liveInstanceMarkedLive() {
        SnapshotStore store = stubStore(snapshot("country", 10, 0, 0, 0, List.of()));
        HeartbeatStoreInmemory heartbeatStore = new HeartbeatStoreInmemory();
        heartbeatStore.record("instance-0");   // fresh heartbeat

        SharedStoreMetricsSource source = new SharedStoreMetricsSource(
                store, THRESHOLDS, fallback("local"), 10, null, heartbeatStore, 30_000);

        assertThat(source.instances())
                .filteredOn(i -> "instance-0".equals(i.instanceId()))
                .extracting(InstanceMetrics::liveStatus)
                .containsOnly(com.societegenerale.failover.observable.metrics.LiveStatus.LIVE);
    }

    @Test
    void livenessTracking_noHeartbeatReceived_instanceUnknown() {
        SnapshotStore store = stubStore(snapshot("country", 10, 0, 0, 0, List.of()));
        HeartbeatStoreInmemory heartbeatStore = new HeartbeatStoreInmemory(); // no heartbeat recorded

        SharedStoreMetricsSource source = new SharedStoreMetricsSource(
                store, THRESHOLDS, fallback("local"), 10, null, heartbeatStore, 30_000);

        // No heartbeat received → peer has not enabled heartbeat → UNKNOWN (not DOWN)
        assertThat(source.instances())
                .filteredOn(i -> "instance-0".equals(i.instanceId()))
                .extracting(InstanceMetrics::liveStatus)
                .containsOnly(com.societegenerale.failover.observable.metrics.LiveStatus.UNKNOWN);
    }

    @Test
    void downInstanceMetricsStillContributeToAggregate() {
        // Key invariant: DOWN instance last-known metrics are preserved in cluster total
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000);
        SnapshotStore store = stubStore(
                snapshot("country", 100, 0, 0, 0, List.of()),
                snapshot("country", 50, 0, 0, 0, List.of()));
        HeartbeatStoreInmemory heartbeatStore = new HeartbeatStoreInmemory(clock::get);
        heartbeatStore.record("instance-1"); // recorded at t=1000; will be stale when liveness check runs
        clock.set(40_000);                   // advance 39s — exceeds 30s window → instance-1 DOWN
        heartbeatStore.record("instance-0"); // fresh at t=40000

        SharedStoreMetricsSource source = new SharedStoreMetricsSource(
                store, THRESHOLDS, fallback("local"), 10, null, heartbeatStore, 30_000, clock::get);

        // Both instances contribute to aggregate (150 total calls), even though instance-1 is DOWN
        assertThat(source.summary().overall().totalCalls()).isEqualTo(150);
        // Only instance-0 (LIVE) counts as reporting; instance-1 (DOWN) excluded
        assertThat(source.info().instancesReporting()).isEqualTo(1);
    }

    @Test
    void infoReportingCountExcludesDownInstances() {
        java.util.concurrent.atomic.AtomicLong clock = new java.util.concurrent.atomic.AtomicLong(1_000);
        SnapshotStore store = stubStore(
                snapshot("country", 10, 0, 0, 0, List.of()),
                snapshot("country", 10, 0, 0, 0, List.of()));
        HeartbeatStoreInmemory heartbeatStore = new HeartbeatStoreInmemory(clock::get);
        heartbeatStore.record("instance-1"); // recorded at t=1000; will be stale
        clock.set(40_000);                   // advance past 30s window → instance-1 DOWN
        heartbeatStore.record("instance-0"); // fresh at t=40000

        SharedStoreMetricsSource source = new SharedStoreMetricsSource(
                store, THRESHOLDS, fallback("local"), 10, null, heartbeatStore, 30_000, clock::get);

        assertThat(source.info().instancesReporting()).isEqualTo(1); // instance-1 DOWN → not counted
        assertThat(source.instances()).hasSize(2);                   // but both still visible
    }

}
