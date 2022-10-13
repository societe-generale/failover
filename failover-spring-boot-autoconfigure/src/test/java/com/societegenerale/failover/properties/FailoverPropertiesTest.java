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

package com.societegenerale.failover.properties;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anand Manissery
 */
class FailoverPropertiesTest {

    private final FailoverProperties failoverProperties = new FailoverProperties();

    @Test
    @DisplayName("should enable the failover by default")
    void shouldEnableByDefault() {
        Map<String, String> result = failoverProperties.additionalInfo();
        assertThat(result).containsEntry("enabled", "true");
    }

    @Test
    @DisplayName("should have basic failover execution by default")
    void shouldHaveBasicFailoverExecutionByDefault() {
        Map<String, String> result = failoverProperties.additionalInfo();
        assertThat(result) .containsEntry("type", "BASIC");
    }

    @Test
    @DisplayName("should have inmemory store by default")
    void shouldHaveInmemoryStoreByDefault() {
        Map<String, String> result = failoverProperties.additionalInfo();
        assertThat(result) .containsEntry("store.type", "INMEMORY");
    }

    @Test
    @DisplayName("should not have any value on jdbc table prefix by default")
    void shouldNotHaveAnyValueOnTablePrefixByDefault() {
        Map<String, String> result = failoverProperties.additionalInfo();
        assertThat(result).containsEntry("store.jdbc.table-prefix", "");
    }

    @Test
    @DisplayName("should have default scheduler configuration metrics")
    void shouldHaveDefaultSchedulerConfigurationMetrics() {
        Map<String, String> result = failoverProperties.additionalInfo();
        assertThat(result).containsEntry("scheduler.enabled", "true")
                .containsEntry("scheduler.report-cron", "0 0 0 * * *")
                .containsEntry("scheduler.cleanup-cron", "0 0 * * * *");
    }
}