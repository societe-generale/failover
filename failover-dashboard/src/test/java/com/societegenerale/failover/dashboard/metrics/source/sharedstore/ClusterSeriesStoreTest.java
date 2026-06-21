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

import com.societegenerale.failover.dashboard.metrics.SeriesPoint;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ClusterSeriesStoreTest {

    private final AtomicLong now = new AtomicLong(100_000);

    private static SeriesPoint point(long ts) {
        return new SeriesPoint(ts, 1, 0, 0, 0, 1, 0, Map.of());
    }

    @Test
    void evicts_oldest_when_over_max_entries() {
        ClusterSeriesStore store = new ClusterSeriesStore(new RetentionPolicy(Duration.ofDays(1), 2), now::get);
        store.append(point(1));
        store.append(point(2));
        store.append(point(3));   // over cap of 2 → point(1) evicted

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.series(0)).extracting(SeriesPoint::timestamp).containsExactly(2L, 3L);
    }

    @Test
    void evicts_points_older_than_max_age() {
        ClusterSeriesStore store = new ClusterSeriesStore(new RetentionPolicy(Duration.ofSeconds(10), 100), now::get);
        store.append(point(now.get() - 20_000));   // 20s old
        store.append(point(now.get() - 5_000));    // 5s old
        store.append(point(now.get()));            // appending prunes the 20s-old one

        assertThat(store.size()).isEqualTo(2);
        assertThat(store.series(0)).allSatisfy(p -> assertThat(now.get() - p.timestamp()).isLessThanOrEqualTo(10_000));
    }

    @Test
    void series_filters_by_window() {
        ClusterSeriesStore store = new ClusterSeriesStore(new RetentionPolicy(Duration.ofDays(1), 100), now::get);
        store.append(point(now.get() - 30_000));
        store.append(point(now.get() - 5_000));

        assertThat(store.series(10)).hasSize(1);   // last 10s ⇒ only the 5s-old point
        assertThat(store.series(0)).hasSize(2);    // 0 ⇒ all retained
    }

    @Test
    void retention_policy_rejects_invalid_bounds() {
        assertThatThrownBy(() -> new RetentionPolicy(Duration.ZERO, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionPolicy(null, 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionPolicy(Duration.ofSeconds(-5), 10)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionPolicy(Duration.ofDays(1), 0)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new RetentionPolicy(Duration.ofDays(1), -1)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void is_expired_boundary() {
        RetentionPolicy p = new RetentionPolicy(Duration.ofSeconds(10), 100);
        assertThat(p.isExpired(1_000, 12_000)).isTrue();    // 11s old > 10s
        assertThat(p.isExpired(5_000, 12_000)).isFalse();   // 7s old <= 10s
    }
}
