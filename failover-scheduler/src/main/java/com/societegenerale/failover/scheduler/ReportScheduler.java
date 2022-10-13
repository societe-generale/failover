/*
 * Copyright 2022-2023, Société Générale All rights reserved.
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

package com.societegenerale.failover.scheduler;

import com.societegenerale.failover.core.report.FailoverReporter;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * @author Anand Manissery
 */
@AllArgsConstructor
@Slf4j
public class ReportScheduler {

    private final FailoverReporter failoverReporter;

    @Async
    @Scheduled(cron = "${failover.scheduler.report-cron:0 0 0 * * *}")
    public void report() {
        log.info("Publishing report...");
        failoverReporter.report();
    }
}
