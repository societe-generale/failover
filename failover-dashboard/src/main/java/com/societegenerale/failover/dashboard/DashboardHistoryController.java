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
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Read-only trend-history endpoint, registered only when {@code failover.dashboard.history.enabled=true}.
 * Lives under {@code base-path/api/metrics/series}, so the exposure interceptor gates it together with
 * the {@code metrics} endpoint (design doc §9).
 *
 * @author Anand Manissery
 */
@RestController
@RequestMapping("${failover.dashboard.base-path:/failover-dashboard}/api/metrics")
public class DashboardHistoryController {

    private final DashboardHistoryService historyService;

    public DashboardHistoryController(DashboardHistoryService historyService) {
        this.historyService = historyService;
    }

    @GetMapping("/series")
    public List<SeriesPoint> series(@RequestParam(name = "windowSec", defaultValue = "300") long windowSec) {
        return historyService.series(windowSec);
    }
}
