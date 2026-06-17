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
import com.societegenerale.failover.dashboard.dto.ApiKpis;
import com.societegenerale.failover.dashboard.dto.ExceptionStat;
import com.societegenerale.failover.dashboard.dto.Latency;
import com.societegenerale.failover.dashboard.dto.MetricsSummary;
import com.societegenerale.failover.dashboard.dto.Rates;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@Import({DashboardMetricsController.class, DashboardMetricsControllerTest.TestApp.class})
@DisplayName("DashboardMetricsController — /failover-dashboard/api/{metrics,health}")
class DashboardMetricsControllerTest {

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    static class TestApp { }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardMetricsService metricsService;

    @Test
    @DisplayName("GET /api/metrics returns the summary JSON")
    void metricsJson() throws Exception {
        Rates rates = new Rates(0.9, 0.1, 0.8, 0.2, 0.98);
        Latency latency = new Latency(1.5, 4.0, 2.2, 9.0);
        ApiKpis kpis = new ApiKpis("country", "geo", 100, 90, 10, 8, 1, 1, 0, 2, latency, rates);
        when(metricsService.metricsSummary()).thenReturn(new MetricsSummary(
                kpis, List.of(kpis), List.of(new ExceptionStat("java.net.SocketTimeoutException", 7)), 123L));

        mockMvc.perform(get("/failover-dashboard/api/metrics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.overall.totalCalls").value(100))
                .andExpect(jsonPath("$.overall.asyncFailed").value(2))
                .andExpect(jsonPath("$.perApi[0].name").value("country"))
                .andExpect(jsonPath("$.perApi[0].latency.recoverMaxMs").value(9.0))
                .andExpect(jsonPath("$.perApi[0].rates.healthyRate").value(0.98))
                .andExpect(jsonPath("$.topExceptions[0].type").value("java.net.SocketTimeoutException"))
                .andExpect(jsonPath("$.topExceptions[0].count").value(7));
    }

    @Test
    @DisplayName("GET /api/health returns the per-API health list")
    void healthJson() throws Exception {
        when(metricsService.health()).thenReturn(List.of(new ApiHealth("country", "DEGRADED", 0.95)));

        mockMvc.perform(get("/failover-dashboard/api/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("country"))
                .andExpect(jsonPath("$[0].status").value("DEGRADED"));
    }
}
