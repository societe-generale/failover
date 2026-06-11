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

class FailoverTypeTest {

    @Test
    @DisplayName("should have exactly 3 values: BASIC, RESILIENCE, CUSTOM")
    void shouldHaveExpectedValues() {
        assertThat(FailoverType.values())
                .containsExactly(FailoverType.BASIC, FailoverType.RESILIENCE, FailoverType.CUSTOM);
    }

    @Test
    @DisplayName("BASIC resolves by name")
    void basicResolvesFromName() {
        assertThat(FailoverType.valueOf("BASIC")).isEqualTo(FailoverType.BASIC);
    }

    @Test
    @DisplayName("RESILIENCE resolves by name")
    void resilienceResolvesFromName() {
        assertThat(FailoverType.valueOf("RESILIENCE")).isEqualTo(FailoverType.RESILIENCE);
    }

    @Test
    @DisplayName("CUSTOM resolves by name")
    void customResolvesFromName() {
        assertThat(FailoverType.valueOf("CUSTOM")).isEqualTo(FailoverType.CUSTOM);
    }
}
