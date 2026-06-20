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

import com.societegenerale.failover.dashboard.dto.ApiHealth;
import com.societegenerale.failover.dashboard.dto.MetricsSummary;
import com.societegenerale.failover.dashboard.dto.SourceInfo;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

class LocalRegistryMetricsSourceTest {

    private final DashboardMetricsService metricsService = mock(DashboardMetricsService.class);
    private final LocalRegistryMetricsSource source = new LocalRegistryMetricsSource(metricsService);

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
}
