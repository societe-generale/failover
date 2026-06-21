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

package com.societegenerale.failover.dashboard.web;

import com.societegenerale.failover.dashboard.service.DashboardConfigService;
import com.societegenerale.failover.dashboard.metrics.ConfigEntry;
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
@Import({DashboardController.class, DashboardControllerTest.TestApp.class})
@DisplayName("DashboardController — GET /failover-dashboard/api/config")
class DashboardControllerTest {

    @org.springframework.boot.SpringBootConfiguration
    @org.springframework.boot.autoconfigure.EnableAutoConfiguration
    static class TestApp { }

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private DashboardConfigService configService;

    @Test
    @DisplayName("returns the config entries as JSON at the default base path")
    void returnsConfigJson() throws Exception {
        when(configService.configEntries()).thenReturn(List.of(new ConfigEntry(
                "country-by-code", "country", 24, "HOURS", false,
                "default", "default", "default",
                "jdbc", "basic", "rethrow", true)));

        mockMvc.perform(get("/failover-dashboard/api/config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].name").value("country-by-code"))
                .andExpect(jsonPath("$[0].storeType").value("jdbc"))
                .andExpect(jsonPath("$[0].keyGenerator").value("default"));
    }

    @Test
    @DisplayName("returns grouped global settings as JSON at /config/settings")
    void returnsSettingsJson() throws Exception {
        when(configService.globalSettings()).thenReturn(java.util.Map.of(
                "Core", java.util.Map.of("failover.enabled", "true", "failover.type", "basic"),
                "Dashboard", java.util.Map.of("failover.dashboard.history.enabled", "true")));

        mockMvc.perform(get("/failover-dashboard/api/config/settings"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.Core['failover.type']").value("basic"))
                .andExpect(jsonPath("$.Dashboard['failover.dashboard.history.enabled']").value("true"));
    }

    @Test
    @DisplayName("returns the actuator-style failover health as JSON")
    void returnsFailoverHealthJson() throws Exception {
        when(configService.failoverHealth()).thenReturn(new com.societegenerale.failover.dashboard.metrics.FailoverHealth(
                "UP", java.util.Map.of("registered-failovers", "3", "store.type", "JDBC")));

        mockMvc.perform(get("/failover-dashboard/api/failover-health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.details['store.type']").value("JDBC"))
                .andExpect(jsonPath("$.details['registered-failovers']").value("3"));
    }
}
