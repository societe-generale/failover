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

package com.societegenerale.failover.scheduler;

import com.societegenerale.failover.core.observable.FailoverObserver;
import org.junit.jupiter.api.DisplayName;
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

/// @author Anand Manissery
@EnableScheduling
@SpringBootTest(classes = {ObservableSchedulerTest.ConfigurationClass.class})
@TestPropertySource(properties = {"failover.scheduler.report-cron=* * * * * *"})
class ObservableSchedulerTest {

    @Autowired
    private FailoverObserver failoverObserver;

    @Test
    @DisplayName("should report on a given interval")
    void shouldReportOnAGivenInterval() {
        await().atMost(3, TimeUnit.SECONDS)
                .untilAsserted(() -> verify(failoverObserver, atLeast(2)).observe());
    }

    @EnableAsync
    @EnableScheduling
    @Configuration
    static class ConfigurationClass {

        @Bean
        public FailoverObserver failoverObserver() {
            return mock(FailoverObserver.class);
        }

        @Bean
        public ObservableScheduler observableScheduler(FailoverObserver failoverObserver) {
            return new ObservableScheduler(failoverObserver);
        }
    }
}