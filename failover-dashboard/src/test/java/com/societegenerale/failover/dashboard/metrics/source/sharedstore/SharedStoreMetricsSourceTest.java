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
            public List<MetricsSummary> live() { return list; }
            public List<InstanceMetrics> liveInstances() {
                List<InstanceMetrics> out = new java.util.ArrayList<>();
                for (int i = 0; i < list.size(); i++) {
                    out.add(new InstanceMetrics("instance-" + i, 100L + i, list.get(i)));
                }
                return out;
            }
            public int liveCount() { return list.size(); }
            public long newestEpochMs() { return list.isEmpty() ? 0L : 123L; }
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
        assertThat(live.info().asOfEpochMs()).isEqualTo(123L);          // stub newestEpochMs() = 123 when non-empty

        SharedStoreMetricsSource empty = new SharedStoreMetricsSource(
                stubStore(), THRESHOLDS, fallback("local"), 10);
        assertThat(empty.info().asOfEpochMs()).isGreaterThan(0L);       // newest==0 → falls back to now
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
    void instancesAlwaysIncludesDashboardHostEvenWhenStoreIsEmpty() {
        // Production scenario: no peers have pushed yet (or dashboard just restarted).
        // The dashboard host must still appear so the Instances tab is never blank.
        MetricsSummary localSummary = snapshot("country", 5, 0, 0, 0, List.of());
        MetricsSource fb = fallbackWithInstance("dashboard-host:8080", localSummary);
        SharedStoreMetricsSource source = new SharedStoreMetricsSource(stubStore(), THRESHOLDS, fb, 10);

        assertThat(source.instances())
                .extracting(InstanceMetrics::instanceId)
                .containsExactly("dashboard-host:8080");
        assertThat(source.info().instancesReporting()).isEqualTo(1);
    }

    @Test
    void instancesDeduplicatesWhenDashboardHostPushedToItself() {
        // Host configured publish-url=http://localhost/... → store already contains its snapshot.
        MetricsSummary localSummary = snapshot("country", 5, 0, 0, 0, List.of());
        MetricsSource fb = fallbackWithInstance("dashboard-host:8080", localSummary);
        SnapshotStore storeWithHost = new SnapshotStore() {
            public void upsert(ClusterSnapshot s) { /* no-op stub */ }
            public List<MetricsSummary> live() { return List.of(localSummary); }
            public List<InstanceMetrics> liveInstances() {
                return List.of(new InstanceMetrics("dashboard-host:8080", 100L, localSummary));
            }
            public int liveCount() { return 1; }
            public long newestEpochMs() { return 123L; }
        };
        SharedStoreMetricsSource source = new SharedStoreMetricsSource(storeWithHost, THRESHOLDS, fb, 10);

        assertThat(source.instances())
                .extracting(InstanceMetrics::instanceId)
                .containsExactly("dashboard-host:8080");   // not duplicated
    }

    /** Fallback with a real {@code instances()} implementation (simulates {@link com.societegenerale.failover.dashboard.metrics.source.LocalRegistryMetricsSource}). */
    private static MetricsSource fallbackWithInstance(String instanceId, MetricsSummary summary) {
        return new MetricsSource() {
            @Override public MetricsSummary summary() { return summary; }
            @Override public List<ApiHealth> health() { return List.of(); }
            @Override public SourceInfo info() { return new SourceInfo("local", 1, -1, 0L, false); }
            @Override public List<SeriesPoint> series(long w) { return List.of(); }
            @Override
            public List<InstanceMetrics> instances() {
                return List.of(new InstanceMetrics(instanceId, currentTimeMillis(), summary));
            }
        };
    }
}
