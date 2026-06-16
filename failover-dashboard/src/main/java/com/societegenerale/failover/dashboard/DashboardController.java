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

import com.societegenerale.failover.dashboard.dto.ConfigEntry;
import com.societegenerale.failover.dashboard.dto.FailoverHealth;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only JSON API for the dashboard. All endpoints are {@code GET} — the dashboard never mutates
 * runtime state (design doc §9).
 *
 * <p>The base path resolves the {@code failover.dashboard.base-path} property (default
 * {@code /failover-dashboard}) so the API tracks the configured UI path.
 *
 * <p>Serves the scanner-derived views that need no {@code MeterRegistry}: {@code /api/config} (every
 * {@code @Failover} point + global settings) and {@code /api/failover-health} (actuator-style overall
 * status). The metrics views live on {@link DashboardMetricsController}.
 *
 * @author Anand Manissery
 */
@RestController
@RequestMapping("${failover.dashboard.base-path:/failover-dashboard}/api")
public class DashboardController {

    private final DashboardConfigService configService;

    public DashboardController(DashboardConfigService configService) {
        this.configService = configService;
    }

    @GetMapping("/config")
    public List<ConfigEntry> config() {
        return configService.configEntries();
    }

    @GetMapping("/failover-health")
    public FailoverHealth failoverHealth() {
        return configService.failoverHealth();
    }
}
