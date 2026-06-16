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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only metrics endpoints, mapped under the same {@code base-path/api} namespace as the config API.
 *
 * <p>Registered only when a {@link io.micrometer.core.instrument.MeterRegistry} is present: without
 * Micrometer the config view still works and the metrics view degrades gracefully (design doc §3).
 *
 * @author Anand Manissery
 */
@RestController
@RequestMapping("${failover.dashboard.base-path:/failover-dashboard}/api")
public class DashboardMetricsController {

    private final DashboardMetricsService metricsService;

    public DashboardMetricsController(DashboardMetricsService metricsService) {
        this.metricsService = metricsService;
    }

    @GetMapping("/metrics")
    public MetricsSummary metrics() {
        return metricsService.metricsSummary();
    }

    @GetMapping("/health")
    public List<ApiHealth> health() {
        return metricsService.health();
    }
}
