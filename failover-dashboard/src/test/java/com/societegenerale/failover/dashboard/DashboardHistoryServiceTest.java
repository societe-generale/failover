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

package com.societegenerale.failover.dashboard;

import com.societegenerale.failover.dashboard.dto.SeriesPoint;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class DashboardHistoryServiceTest {

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private final DashboardMetricsService metrics =
            new DashboardMetricsService(registry, new DashboardProperties(true, "/failover-dashboard"));

    private void storeSuccess(int times) {
        Counter.builder("failover.store.total").tag("name", "svc").tag("stored", "true")
                .register(registry).increment(times);
    }

    @Test
    @DisplayName("sample() snapshots the current global counters into the ring")
    void sampleSnapshots() {
        storeSuccess(10);
        DashboardHistoryService history = new DashboardHistoryService(metrics, 120);

        history.sample();

        List<SeriesPoint> series = history.series(0);
        assertThat(series).hasSize(1);
        assertThat(series.get(0).calls()).isEqualTo(10);
        assertThat(series.get(0).store()).isEqualTo(10);
    }

    @Test
    @DisplayName("ring evicts the oldest sample beyond capacity")
    void ringEvicts() {
        DashboardHistoryService history = new DashboardHistoryService(metrics, 3);

        for (int i = 0; i < 5; i++) {
            history.sample();
        }

        assertThat(history.series(0)).hasSize(3);
    }

    @Test
    @DisplayName("series(windowSec) filters out samples older than the window")
    void windowFilters() {
        DashboardHistoryService history = new DashboardHistoryService(metrics, 120);
        history.sample();

        // window of 1s keeps the just-taken sample...
        assertThat(history.series(1)).hasSize(1);
        // ...a zero/negative window returns everything retained
        assertThat(history.series(0)).hasSize(1);
        assertThat(history.series(-5)).hasSize(1);
    }

    @Test
    @DisplayName("series(windowSec) excludes a sample older than the window")
    void windowExcludesOldSample() throws InterruptedException {
        DashboardHistoryService history = new DashboardHistoryService(metrics, 120);
        history.sample();                       // taken now
        Thread.sleep(1100);                     // age it past a 1-second window

        assertThat(history.series(1)).isEmpty();        // older than 1s ⇒ excluded
        assertThat(history.series(0)).hasSize(1);       // unbounded window ⇒ retained
    }

    @Test
    @DisplayName("capacity floors at 1 even if misconfigured to <= 0")
    void capacityFloor() {
        DashboardHistoryService history = new DashboardHistoryService(metrics, 0);
        history.sample();
        history.sample();
        assertThat(history.series(0)).hasSize(1);
    }
}
