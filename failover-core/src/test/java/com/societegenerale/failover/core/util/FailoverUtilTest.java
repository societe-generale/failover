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

package com.societegenerale.failover.core.util;

import com.societegenerale.failover.annotations.Failover;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.temporal.ChronoUnit;

import static com.societegenerale.failover.core.util.FailoverUtil.effectiveName;
import static com.societegenerale.failover.core.util.FailoverUtil.summary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class FailoverUtilTest {

    @Mock
    private Failover failover;

    @Test
    @DisplayName("domain blank → returns failover name")
    void domainBlankReturnsFailoverName() {
        given(failover.name()).willReturn("tp-by-id");
        given(failover.domain()).willReturn("");

        assertThat(effectiveName(failover)).isEqualTo("tp-by-id");
    }

    @Test
    @DisplayName("domain set → returns domain")
    void domainSetReturnsDomain() {
        given(failover.domain()).willReturn("tp-shared");

        assertThat(effectiveName(failover)).isEqualTo("tp-shared");
    }

    @Test
    @DisplayName("domain whitespace-only → treated as blank, returns name")
    void domainWhitespaceOnlyReturnsFailoverName() {
        given(failover.name()).willReturn("tp-by-id");
        given(failover.domain()).willReturn("   ");

        assertThat(effectiveName(failover)).isEqualTo("tp-by-id");
    }

    @Nested
    @DisplayName("summary")
    class Summary {

        /** All-blank/false fixture; individual tests override just the attribute under test. */
        private Failover minimal(long expiryDuration, ChronoUnit expiryUnit) {
            Failover f = mock(Failover.class);
            given(f.name()).willReturn("country-by-code");
            given(f.expiryDuration()).willReturn(expiryDuration);
            given(f.expiryUnit()).willReturn(expiryUnit);
            lenient().when(f.expiryDurationExpression()).thenReturn("");
            lenient().when(f.expiryUnitExpression()).thenReturn("");
            given(f.domain()).willReturn("");
            given(f.keyGenerator()).willReturn("");
            given(f.expiryPolicy()).willReturn("");
            given(f.payloadSplitter()).willReturn("");
            given(f.recoverAll()).willReturn(false);
            return f;
        }

        @Test
        @DisplayName("minimal config → name and literal expiry only")
        void minimalConfig() {
            Failover f = minimal(24, ChronoUnit.HOURS);

            assertThat(summary(f)).isEqualTo("country-by-code : expiry=24 HOURS");
        }

        @Test
        @DisplayName("literal expiry uses expiryDuration()/expiryUnit() when no expression is set")
        void literalExpiryUsedWhenNoExpression() {
            Failover f = minimal(30, ChronoUnit.MINUTES);

            assertThat(summary(f)).isEqualTo("country-by-code : expiry=30 MINUTES");
        }

        @Test
        @DisplayName("expiryDurationExpression set, expiryUnitExpression blank → expression duration + literal unit name")
        void expiryDurationExpressionWithLiteralUnit() {
            Failover f = minimal(24, ChronoUnit.HOURS);
            given(f.expiryDurationExpression()).willReturn("${my.ttl.hours}");

            assertThat(summary(f)).isEqualTo("country-by-code : expiry=${my.ttl.hours} HOURS");
        }

        @Test
        @DisplayName("both expiryDurationExpression and expiryUnitExpression set → both expressions used verbatim")
        void bothExpiryExpressionsUsed() {
            Failover f = minimal(24, ChronoUnit.HOURS);
            given(f.expiryDurationExpression()).willReturn("${my.ttl.duration}");
            given(f.expiryUnitExpression()).willReturn("${my.ttl.unit}");

            assertThat(summary(f)).isEqualTo("country-by-code : expiry=${my.ttl.duration} ${my.ttl.unit}");
        }

        @Test
        @DisplayName("expiryUnitExpression set but expiryDurationExpression blank → literal duration still used")
        void expiryUnitExpressionAloneDoesNotSwitchToExpressionDuration() {
            Failover f = minimal(24, ChronoUnit.HOURS);
            given(f.expiryUnitExpression()).willReturn("${my.ttl.unit}");

            assertThat(summary(f)).isEqualTo("country-by-code : expiry=24 ${my.ttl.unit}");
        }

        @Test
        @DisplayName("domain set → appends domain")
        void domainAppended() {
            Failover f = minimal(24, ChronoUnit.HOURS);
            given(f.domain()).willReturn("country");

            assertThat(summary(f)).isEqualTo("country-by-code : expiry=24 HOURS, domain='country'");
        }

        @Test
        @DisplayName("keyGenerator set → appends keyGenerator")
        void keyGeneratorAppended() {
            Failover f = minimal(24, ChronoUnit.HOURS);
            given(f.keyGenerator()).willReturn("myKeyGen");

            assertThat(summary(f)).isEqualTo("country-by-code : expiry=24 HOURS, keyGenerator='myKeyGen'");
        }

        @Test
        @DisplayName("expiryPolicy set → appends expiryPolicy")
        void expiryPolicyAppended() {
            Failover f = minimal(24, ChronoUnit.HOURS);
            given(f.expiryPolicy()).willReturn("myExpiryPolicy");

            assertThat(summary(f)).isEqualTo("country-by-code : expiry=24 HOURS, expiryPolicy='myExpiryPolicy'");
        }

        @Test
        @DisplayName("payloadSplitter set → appends splitter")
        void payloadSplitterAppended() {
            Failover f = minimal(24, ChronoUnit.HOURS);
            given(f.payloadSplitter()).willReturn("mySplitter");

            assertThat(summary(f)).isEqualTo("country-by-code : expiry=24 HOURS, splitter='mySplitter'");
        }

        @Test
        @DisplayName("recoverAll true → appends recoverAll=true")
        void recoverAllAppendedWhenTrue() {
            Failover f = minimal(24, ChronoUnit.HOURS);
            given(f.recoverAll()).willReturn(true);

            assertThat(summary(f)).isEqualTo("country-by-code : expiry=24 HOURS, recoverAll=true");
        }

        @Test
        @DisplayName("recoverAll false → omitted entirely (not 'recoverAll=false')")
        void recoverAllOmittedWhenFalse() {
            Failover f = minimal(24, ChronoUnit.HOURS);

            assertThat(summary(f)).doesNotContain("recoverAll");
        }

        @Test
        @DisplayName("all optional attributes set → every clause appended, in declaration order")
        void allAttributesCombined() {
            Failover f = minimal(24, ChronoUnit.HOURS);
            given(f.domain()).willReturn("country");
            given(f.keyGenerator()).willReturn("myKeyGen");
            given(f.expiryPolicy()).willReturn("myExpiryPolicy");
            given(f.payloadSplitter()).willReturn("mySplitter");
            given(f.recoverAll()).willReturn(true);

            assertThat(summary(f)).isEqualTo("country-by-code : expiry=24 HOURS, domain='country', "
                    + "keyGenerator='myKeyGen', expiryPolicy='myExpiryPolicy', splitter='mySplitter', recoverAll=true");
        }
    }
}
