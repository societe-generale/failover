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

import com.societegenerale.failover.dashboard.metrics.ApiHealth;
import com.societegenerale.failover.dashboard.metrics.ApiKpis;
import com.societegenerale.failover.dashboard.metrics.Latency;
import com.societegenerale.failover.dashboard.metrics.MetricsSummary;
import com.societegenerale.failover.dashboard.metrics.SeriesPoint;
import com.societegenerale.failover.dashboard.metrics.SourceInfo;
import com.societegenerale.failover.dashboard.metrics.source.DashboardKpis;
import com.societegenerale.failover.dashboard.metrics.source.MetricsSource;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;

class ClusterSeriesSamplerTest {

    /** A source whose aggregate summary we can mutate between samples (simulating cluster activity / a reset). */
    private static final class MutableSource implements MetricsSource {
        private final AtomicReference<MetricsSummary> current = new AtomicReference<>();
        void setSuccess(long success) {
            ApiKpis overall = DashboardKpis.build("__overall__", "__overall__", success, 0, 0, 0, 0, 0, new Latency(0, 0, 0, 0));
            current.set(new MetricsSummary(overall, List.of(), List.of(), 0L));
        }
        void setPerApiFailover(String name, long failover) {
            ApiKpis k = DashboardKpis.build(name, name, 0, failover, 0, 0, 0, 0, new Latency(0, 0, 0, 0));
            ApiKpis overall = DashboardKpis.build("__overall__", "__overall__", 0, failover, 0, 0, 0, 0, new Latency(0, 0, 0, 0));
            current.set(new MetricsSummary(overall, List.of(k), List.of(), 0L));
        }
        public MetricsSummary summary() { return current.get(); }
        public List<ApiHealth> health() { return List.of(); }
        public SourceInfo info() { return new SourceInfo("shared-store", 1, 10, 0L, false); }
        public List<SeriesPoint> series(long windowSec) { return List.of(); }
    }

    @Test
    void per_api_failover_trend_is_reset_aware_monotonic() {
        MutableSource source = new MutableSource();
        ClusterSeriesStore store = new ClusterSeriesStore(new RetentionPolicy(Duration.ofDays(1), 100));
        try (ClusterSeriesSampler sampler = new ClusterSeriesSampler(source, store, 3600)) {
            source.setPerApiFailover("country", 4);   // first sample seeds baseline
            sampler.sample();
            source.setPerApiFailover("country", 10);  // grew by 6
            sampler.sample();
            source.setPerApiFailover("country", 3);    // peer reset → raw dropped; counts the fresh 3
            sampler.sample();

            List<Long> perApi = store.series(0).stream().map(p -> p.failoverByApi().get("country")).toList();
            assertThat(perApi).containsExactly(4L, 10L, 13L);   // never decreases
        }
    }

    @Test
    void sample_failure_is_swallowed_by_the_scheduled_runner() {
        MetricsSource boom = new MetricsSource() {
            public MetricsSummary summary() { throw new IllegalStateException("kaboom"); }
            public List<ApiHealth> health() { return List.of(); }
            public SourceInfo info() { return new SourceInfo("shared-store", 0, 0, 0L, false); }
            public List<SeriesPoint> series(long windowSec) { return List.of(); }
        };
        ClusterSeriesStore store = new ClusterSeriesStore(new RetentionPolicy(Duration.ofDays(1), 100));
        try (ClusterSeriesSampler sampler = new ClusterSeriesSampler(boom, store, 3600)) {
            org.assertj.core.api.Assertions.assertThatCode(sampler::safeSample).doesNotThrowAnyException();
            assertThat(store.series(0)).isEmpty();
        }
    }

    @Test
    void series_is_monotonic_across_a_peer_reset() {
        MutableSource source = new MutableSource();
        ClusterSeriesStore store = new ClusterSeriesStore(new RetentionPolicy(Duration.ofDays(1), 100));
        try (ClusterSeriesSampler sampler = new ClusterSeriesSampler(source, store, 3600)) {
            source.setSuccess(10);
            sampler.sample();
            source.setSuccess(25);   // cluster grew
            sampler.sample();
            source.setSuccess(5);    // a peer restarted → raw aggregate dropped
            sampler.sample();

            List<Long> calls = store.series(0).stream().map(SeriesPoint::calls).toList();
            assertThat(calls).containsExactly(10L, 25L, 30L);   // never decreases; post-reset (+5) still counted
        }
    }

    @Test
    void first_sample_seeds_the_cumulative_baseline() {
        MutableSource source = new MutableSource();
        ClusterSeriesStore store = new ClusterSeriesStore(new RetentionPolicy(Duration.ofDays(1), 100));
        try (ClusterSeriesSampler sampler = new ClusterSeriesSampler(source, store, 3600)) {
            source.setSuccess(42);
            sampler.sample();

            assertThat(store.series(0)).singleElement().satisfies(p -> assertThat(p.calls()).isEqualTo(42L));
        }
    }
}
