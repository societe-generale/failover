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

import static org.assertj.core.api.Assertions.assertThat;

class ExceptionPolicyTest {

    @Test
    @DisplayName("should have exactly 3 values: RETHROW, NEVER_THROW, CUSTOM")
    void shouldHaveExpectedValues() {
        assertThat(ExceptionPolicy.values())
                .containsExactly(ExceptionPolicy.RETHROW, ExceptionPolicy.NEVER_THROW, ExceptionPolicy.CUSTOM);
    }

    @Test
    @DisplayName("RETHROW resolves by name")
    void rethrowResolvesFromName() {
        assertThat(ExceptionPolicy.valueOf("RETHROW")).isEqualTo(ExceptionPolicy.RETHROW);
    }

    @Test
    @DisplayName("NEVER_THROW resolves by name")
    void neverThrowResolvesFromName() {
        assertThat(ExceptionPolicy.valueOf("NEVER_THROW")).isEqualTo(ExceptionPolicy.NEVER_THROW);
    }

    @Test
    @DisplayName("CUSTOM resolves by name")
    void customResolvesFromName() {
        assertThat(ExceptionPolicy.valueOf("CUSTOM")).isEqualTo(ExceptionPolicy.CUSTOM);
    }
}
