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

package com.societegenerale.failover.dashboard.metrics.source;

import com.societegenerale.failover.dashboard.service.DashboardMetricsService;
import com.societegenerale.failover.dashboard.service.DashboardHistoryService;
import com.societegenerale.failover.dashboard.metrics.ApiHealth;
import com.societegenerale.failover.dashboard.metrics.InstanceMetrics;
import com.societegenerale.failover.dashboard.metrics.MetricsSummary;
import com.societegenerale.failover.dashboard.metrics.SeriesPoint;
import com.societegenerale.failover.dashboard.metrics.SourceInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalRegistryMetricsSourceTest {

    private static final String INSTANCE_ID = "myapp:host-1";

    private final DashboardMetricsService metricsService = mock(DashboardMetricsService.class);
    private final DashboardHistoryService history = mock(DashboardHistoryService.class);
    private final LocalRegistryMetricsSource source = new LocalRegistryMetricsSource(metricsService, history, INSTANCE_ID);

    @Test
    @DisplayName("summary() delegates to the metrics service")
    void summaryDelegates() {
        MetricsSummary summary = new MetricsSummary(null, List.of(), List.of(), 7L);
        when(metricsService.metricsSummary()).thenReturn(summary);

        assertThat(source.summary()).isSameAs(summary);
    }

    @Test
    @DisplayName("health() delegates to the metrics service")
    void healthDelegates() {
        List<ApiHealth> health = List.of(new ApiHealth("country", "HEALTHY", 0.999));
        when(metricsService.health()).thenReturn(health);

        assertThat(source.health()).isSameAs(health);
    }

    @Test
    @DisplayName("info() reports local provenance — one instance, unknown cluster size, not partial")
    void infoReportsLocal() {
        long before = System.currentTimeMillis();

        SourceInfo info = source.info();

        assertThat(info.mode()).isEqualTo("local");
        assertThat(info.instancesReporting()).isEqualTo(1);
        assertThat(info.instancesExpected()).isEqualTo(-1);   // cluster size unknown in local mode
        assertThat(info.partial()).isFalse();
        assertThat(info.asOfEpochMs()).isGreaterThanOrEqualTo(before);
    }

    @Test
    @DisplayName("series() delegates to the history ring when present")
    void seriesDelegatesToHistory() {
        List<SeriesPoint> points = List.of(new SeriesPoint(1L, 10, 1, 1, 0, 9, 1, Map.of("svc", 1L)));
        when(history.series(120L)).thenReturn(points);

        assertThat(source.series(120L)).isSameAs(points);
    }

    @Test
    @DisplayName("series() is empty when history is disabled (null)")
    void seriesEmptyWithoutHistory() {
        LocalRegistryMetricsSource noHistory = new LocalRegistryMetricsSource(metricsService, null, INSTANCE_ID);

        assertThat(noHistory.series(0)).isEmpty();
    }

    @Test
    @DisplayName("instances() always returns one entry for this instance so the Instances tab is always visible")
    void instancesAlwaysReturnsSingleEntry() {
        MetricsSummary summary = new MetricsSummary(null, List.of(), List.of(), 7L);
        when(metricsService.metricsSummary()).thenReturn(summary);
        long before = System.currentTimeMillis();

        List<InstanceMetrics> result = source.instances();

        assertThat(result).hasSize(1);
        InstanceMetrics inst = result.getFirst();
        assertThat(inst.instanceId()).isEqualTo(INSTANCE_ID);
        assertThat(inst.summary()).isSameAs(summary);
        assertThat(inst.lastSeenEpochMs()).isGreaterThanOrEqualTo(before);
    }
}
