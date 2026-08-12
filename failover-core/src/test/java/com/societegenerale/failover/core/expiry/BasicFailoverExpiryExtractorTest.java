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

package com.societegenerale.failover.core.expiry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.time.temporal.ChronoUnit.DAYS;
import static org.assertj.core.api.Assertions.assertThat;

class BasicFailoverExpiryExtractorTest {

    private final BasicFailoverExpiryExtractor basicFailoverExpiryExtractor = new BasicFailoverExpiryExtractor();

    @Test
    @DisplayName("should resolve expiry duration expression")
    void shouldResolveExpiryDurationExpression() {
        var result = basicFailoverExpiryExtractor.resolveExpiryDuration("1");
        assertThat(result).isOne();
    }

    @Test
    @DisplayName("resolve expiry unit expression")
    void resolveExpiryUnitExpression() {
        var result = basicFailoverExpiryExtractor.resolveExpiryUnit("DAYS");
        assertThat(result).isEqualTo(DAYS);
    }

    @Test
    @DisplayName("resolve expiry unit expression when unit in lower case")
    void resolveExpiryUnitExpressionWhenUnitInLowerCase() {
        var result = basicFailoverExpiryExtractor.resolveExpiryUnit("days");
        assertThat(result).isEqualTo(DAYS);
    }
}