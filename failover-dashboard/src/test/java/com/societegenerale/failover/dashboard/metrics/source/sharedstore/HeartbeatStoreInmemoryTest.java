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
        HeartbeatStoreInmemory store = new HeartbeatStoreInmemory(now::get);

        store.record("i1");
        assertThat(store.lastSeen("i1")).isEqualTo(10_000L);
    }

    @Test
    @DisplayName("record updates last-seen; subsequent record refreshes the timestamp")
    void recordRefreshesTimestamp() {
        AtomicLong now = new AtomicLong(10_000);
        HeartbeatStoreInmemory store = new HeartbeatStoreInmemory(now::get);

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
        HeartbeatStoreInmemory store = new HeartbeatStoreInmemory(now::get);

        store.record("i1");              // t=10000
        now.set(10_200);
        store.record("i2");              // t=10200

        assertThat(store.lastSeen("i1")).isEqualTo(10_000L);
        assertThat(store.lastSeen("i2")).isEqualTo(10_200L);
        assertThat(store.lastSeen("i3")).isNull();
    }
}
