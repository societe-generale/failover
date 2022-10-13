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
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.test.context.TestPropertySource;

import java.util.concurrent.TimeUnit;

import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.*;

/**
 * @author Anand Manissery
 */
@EnableScheduling
@SpringBootTest(classes = {ReportSchedulerTest.ConfigurationClass.class})
@TestPropertySource(properties = {"failover.scheduler.report-cron=* * * * * *"})
class ReportSchedulerTest {

    @Autowired
    private FailoverReporter failoverReporter;

    @Test
    void shouldReportOnAGivenInterval() {
        await().atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() -> verify(failoverReporter, atLeast(2)).report());
    }

    @EnableAsync
    @EnableScheduling
    @Configuration
    static class ConfigurationClass {

        @Bean
        public FailoverReporter failoverReporter() {
            return mock(FailoverReporter.class);
        }

        @Bean
        public ReportScheduler reportScheduler(FailoverReporter failoverReporter) {
            return new ReportScheduler(failoverReporter);
        }
    }
}