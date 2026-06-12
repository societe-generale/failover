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

package com.societegenerale.failover.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class SchedulerTest {

    private final Scheduler scheduler = new Scheduler();

    @Test
    @DisplayName("enabled defaults to true")
    void enabledDefaultsToTrue() {
        assertThat(scheduler.isEnabled()).isTrue();
    }

    @Test
    @DisplayName("reportCron defaults to daily midnight (0 0 0 * * *)")
    void reportCronDefaultsToMidnight() {
        assertThat(scheduler.getReportCron()).isEqualTo("0 0 0 * * *");
    }

    @Test
    @DisplayName("cleanupCron defaults to hourly (0 0 * * * *)")
    void cleanupCronDefaultsToHourly() {
        assertThat(scheduler.getCleanupCron()).isEqualTo("0 0 * * * *");
    }

    @Test
    @DisplayName("enabled can be set to false")
    void enabledCanBeDisabled() {
        scheduler.setEnabled(false);
        assertThat(scheduler.isEnabled()).isFalse();
    }

    @Test
    @DisplayName("reportCron can be overridden")
    void reportCronCanBeOverridden() {
        scheduler.setReportCron("0 0 6 * * *");
        assertThat(scheduler.getReportCron()).isEqualTo("0 0 6 * * *");
    }

    @Test
    @DisplayName("cleanupCron can be overridden")
    void cleanupCronCanBeOverridden() {
        scheduler.setCleanupCron("0 30 * * * *");
        assertThat(scheduler.getCleanupCron()).isEqualTo("0 30 * * * *");
    }
}
