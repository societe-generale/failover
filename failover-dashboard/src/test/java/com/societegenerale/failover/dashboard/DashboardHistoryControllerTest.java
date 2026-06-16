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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest
@Import({DashboardHistoryController.class, DashboardHistoryControllerTest.TestApp.class})
@DisplayName("DashboardHistoryController — /failover-dashboard/api/metrics/series")
class DashboardHistoryControllerTest {

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    static class TestApp { }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardHistoryService historyService;

    @Test
    @DisplayName("GET /series returns the points and honours the windowSec param")
    void seriesJson() throws Exception {
        when(historyService.series(eq(120L)))
                .thenReturn(List.of(new SeriesPoint(1000L, 50, 5, 4, 1, 45, 5)));

        mockMvc.perform(get("/failover-dashboard/api/metrics/series").param("windowSec", "120"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].calls").value(50))
                .andExpect(jsonPath("$[0].store").value(45));
    }
}
