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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.societegenerale.failover.core.clock.FailoverClock;

import java.time.Duration;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("HeartbeatStoreInmemory")
class HeartbeatStoreInmemoryTest {

    @Test
    @DisplayName("lastSeen returns null when no heartbeat recorded")
    void nullWhenNoHeartbeat() {
        HeartbeatStoreInmemory store = new HeartbeatStoreInmemory();
        assertThat(store.lastSeen("i1")).isNull();
    }

    @Test
    @DisplayName("lastSeen returns the recorded timestamp")
    void returnsRecordedTimestamp() {
        AtomicLong now = new AtomicLong(10_000);
        HeartbeatStoreInmemory store = new HeartbeatStoreInmemory(Duration.ZERO, clockOf(now));

        store.record("i1");
        assertThat(store.lastSeen("i1")).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("record updates last-seen; subsequent record refreshes the timestamp")
    void recordRefreshesTimestamp() {
        AtomicLong now = new AtomicLong(10_000);
        HeartbeatStoreInmemory store = new HeartbeatStoreInmemory(Duration.ZERO, clockOf(now));

        store.record("i1");
        assertThat(store.lastSeen("i1")).isEqualTo(10_000L);

        now.set(11_500);
        store.record("i1");
        assertThat(store.lastSeen("i1")).isEqualTo(11_500L);
    }

    @Test
    @DisplayName("multiple instances tracked independently")
    void multipleInstancesIndependent() {
        AtomicLong now = new AtomicLong(10_000);
        HeartbeatStoreInmemory store = new HeartbeatStoreInmemory(Duration.ZERO, clockOf(now));

        store.record("i1");              // t=10000
        now.set(10_200);
        store.record("i2");              // t=10200

        assertThat(store.lastSeen("i1")).isEqualTo(10_000L);
        assertThat(store.lastSeen("i2")).isEqualTo(10_200L);
        assertThat(store.lastSeen("i3")).isNull();
    }

    @Test
    @DisplayName("entries not refreshed within the retention window are dropped")
    void expiredEntriesPruned() {
        AtomicLong now = new AtomicLong(10_000);
        HeartbeatStoreInmemory store = new HeartbeatStoreInmemory(Duration.ofMillis(1_000), clockOf(now));

        store.record("stale");
        now.set(10_500);
        store.record("fresh");

        now.set(11_200);                 // 'stale' is now 1200ms old, 'fresh' only 700ms
        store.record("trigger");

        assertThat(store.lastSeen("stale")).isNull();
        assertThat(store.lastSeen("fresh")).isEqualTo(10_500L);
        assertThat(store.lastSeen("trigger")).isEqualTo(11_200L);
    }

    @Test
    @DisplayName("a refreshed entry survives past the retention window")
    void refreshedEntrySurvives() {
        AtomicLong now = new AtomicLong(10_000);
        HeartbeatStoreInmemory store = new HeartbeatStoreInmemory(Duration.ofMillis(1_000), clockOf(now));

        store.record("i1");
        now.set(10_800);
        store.record("i1");              // refreshed before expiry
        now.set(11_500);                 // >1000ms after the first record, but only 700ms after the refresh
        store.record("other");

        assertThat(store.lastSeen("i1")).isEqualTo(10_800L);
    }

    @Test
    @DisplayName("zero retention disables age-based pruning")
    void zeroRetentionKeepsEverything() {
        AtomicLong now = new AtomicLong(10_000);
        HeartbeatStoreInmemory store = new HeartbeatStoreInmemory(Duration.ZERO, clockOf(now));

        store.record("ancient");
        now.set(10_000 + Duration.ofDays(3650).toMillis());
        store.record("recent");

        assertThat(store.lastSeen("ancient")).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("at the size cap a new instance evicts the oldest heartbeat")
    void sizeCapEvictsOldest() {
        AtomicLong now = new AtomicLong(1);
        HeartbeatStoreInmemory store = new HeartbeatStoreInmemory(Duration.ZERO, clockOf(now));

        for (int i = 0; i < HeartbeatStoreInmemory.MAX_INSTANCES; i++) {
            now.set(1_000L + i);
            store.record("instance-" + i);
        }
        assertThat(store.size()).isEqualTo(HeartbeatStoreInmemory.MAX_INSTANCES);
        assertThat(store.lastSeen("instance-0")).isEqualTo(1_000L);

        now.set(9_000_000L);
        store.record("newcomer");

        assertThat(store.lastSeen("instance-0")).isNull();                 // oldest evicted
        assertThat(store.lastSeen("newcomer")).isEqualTo(9_000_000L);
        assertThat(store.size()).isEqualTo(HeartbeatStoreInmemory.MAX_INSTANCES);
    }

    @Test
    @DisplayName("refreshing an existing instance at the cap evicts nothing")
    void refreshAtCapDoesNotEvict() {
        AtomicLong now = new AtomicLong(1);
        HeartbeatStoreInmemory store = new HeartbeatStoreInmemory(Duration.ZERO, clockOf(now));

        for (int i = 0; i < HeartbeatStoreInmemory.MAX_INSTANCES; i++) {
            now.set(1_000L + i);
            store.record("instance-" + i);
        }

        now.set(9_000_000L);
        store.record("instance-0");      // already tracked — an update, not an admission

        assertThat(store.size()).isEqualTo(HeartbeatStoreInmemory.MAX_INSTANCES);
        assertThat(store.lastSeen("instance-0")).isEqualTo(9_000_000L);
        assertThat(store.lastSeen("instance-1")).isEqualTo(1_001L);
    }

    /** Drives {@link HeartbeatStoreInmemory} off a test-controlled clock instead of wall time. */
    private static FailoverClock clockOf(AtomicLong epochMillis) {
        return () -> Instant.ofEpochMilli(epochMillis.get());
    }
}
