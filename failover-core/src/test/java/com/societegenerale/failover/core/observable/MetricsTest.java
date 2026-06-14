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

package com.societegenerale.failover.core.observable;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anand Manissery
 */
class MetricsTest {

    @Test
    @DisplayName("should prefix collected keys with 'failover-' and keep the value")
    void collectPrefixesKeys() {
        Metrics metrics = Metrics.of("my-failover").collect("action", "store");
        assertThat(metrics.getInfo())
                .containsEntry("failover-name", "my-failover")
                .containsEntry("failover-action", "store");
    }

    @Test
    @DisplayName("should coerce a null value to empty string so consumers never see null")
    void collectCoercesNullValueToEmptyString() {
        Metrics metrics = Metrics.of("my-failover").collect("recovery-failure-message", null);
        assertThat(metrics.getInfo()).containsEntry("failover-recovery-failure-message", "");
        assertThat(metrics.getInfo().values()).doesNotContainNull();
    }
}