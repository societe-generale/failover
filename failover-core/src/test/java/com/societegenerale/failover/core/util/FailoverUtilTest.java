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

import java.lang.reflect.Method;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;

import static com.societegenerale.failover.core.util.FailoverUtil.effectiveName;
import static com.societegenerale.failover.core.util.FailoverUtil.summary;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

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

    /** Real {@code @Failover} fixtures — read off methods rather than mocking the annotation. */
    @SuppressWarnings("unused")
    static class Fixtures {
        @Failover(name = "country-by-code")
        void minimal() { /* fixture: only the @Failover annotation is read reflectively */ }

        @Failover(name = "country-by-code", expiryDuration = 30, expiryUnit = ChronoUnit.MINUTES)
        void literalMinutes() { /* fixture: only the @Failover annotation is read reflectively */ }

        @Failover(name = "country-by-code", expiryDurationExpression = "${my.ttl.hours}")
        void exprDurationOnly() { /* fixture: only the @Failover annotation is read reflectively */ }

        @Failover(name = "country-by-code", expiryDurationExpression = "${my.ttl.duration}",
                expiryUnitExpression = "${my.ttl.unit}")
        void exprBoth() { /* fixture: only the @Failover annotation is read reflectively */ }

        @Failover(name = "country-by-code", expiryUnitExpression = "${my.ttl.unit}")
        void exprUnitOnly() { /* fixture: only the @Failover annotation is read reflectively */ }

        @Failover(name = "country-by-code", domain = "country")
        void withDomain() { /* fixture: only the @Failover annotation is read reflectively */ }

        @Failover(name = "country-by-code", keyGenerator = "myKeyGen")
        void withKeyGenerator() { /* fixture: only the @Failover annotation is read reflectively */ }

        @Failover(name = "country-by-code", expiryPolicy = "myExpiryPolicy")
        void withExpiryPolicy() { /* fixture: only the @Failover annotation is read reflectively */ }

        @Failover(name = "country-by-code", payloadSplitter = "mySplitter")
        void withPayloadSplitter() { /* fixture: only the @Failover annotation is read reflectively */ }

        @Failover(name = "country-by-code", recoverAll = true)
        void withRecoverAllTrue() { /* fixture: only the @Failover annotation is read reflectively */ }

        @Failover(name = "country-by-code", domain = "country", keyGenerator = "myKeyGen",
                expiryPolicy = "myExpiryPolicy", payloadSplitter = "mySplitter", recoverAll = true)
        void allCombined() { /* fixture: only the @Failover annotation is read reflectively */ }
    }

    private static Failover annotation(String methodName) {
        Method m = Arrays.stream(Fixtures.class.getDeclaredMethods())
                .filter(it -> it.getName().equals(methodName)).findFirst().orElseThrow();
        return m.getAnnotation(Failover.class);
    }

    @Nested
    @DisplayName("summary")
    class Summary {

        @Test
        @DisplayName("minimal config → name and literal expiry only")
        void minimalConfig() {
            assertThat(summary(annotation("minimal"))).isEqualTo("country-by-code : expiry=1 HOURS");
        }

        @Test
        @DisplayName("literal expiry uses expiryDuration()/expiryUnit() when no expression is set")
        void literalExpiryUsedWhenNoExpression() {
            assertThat(summary(annotation("literalMinutes"))).isEqualTo("country-by-code : expiry=30 MINUTES");
        }

        @Test
        @DisplayName("expiryDurationExpression set, expiryUnitExpression blank → expression duration + literal unit name")
        void expiryDurationExpressionWithLiteralUnit() {
            assertThat(summary(annotation("exprDurationOnly")))
                    .isEqualTo("country-by-code : expiry=${my.ttl.hours} HOURS");
        }

        @Test
        @DisplayName("both expiryDurationExpression and expiryUnitExpression set → both expressions used verbatim")
        void bothExpiryExpressionsUsed() {
            assertThat(summary(annotation("exprBoth")))
                    .isEqualTo("country-by-code : expiry=${my.ttl.duration} ${my.ttl.unit}");
        }

        @Test
        @DisplayName("expiryUnitExpression set but expiryDurationExpression blank → literal duration still used")
        void expiryUnitExpressionAloneDoesNotSwitchToExpressionDuration() {
            assertThat(summary(annotation("exprUnitOnly")))
                    .isEqualTo("country-by-code : expiry=1 ${my.ttl.unit}");
        }

        @Test
        @DisplayName("domain set → appends domain")
        void domainAppended() {
            assertThat(summary(annotation("withDomain")))
                    .isEqualTo("country-by-code : expiry=1 HOURS, domain='country'");
        }

        @Test
        @DisplayName("keyGenerator set → appends keyGenerator")
        void keyGeneratorAppended() {
            assertThat(summary(annotation("withKeyGenerator")))
                    .isEqualTo("country-by-code : expiry=1 HOURS, keyGenerator='myKeyGen'");
        }

        @Test
        @DisplayName("expiryPolicy set → appends expiryPolicy")
        void expiryPolicyAppended() {
            assertThat(summary(annotation("withExpiryPolicy")))
                    .isEqualTo("country-by-code : expiry=1 HOURS, expiryPolicy='myExpiryPolicy'");
        }

        @Test
        @DisplayName("payloadSplitter set → appends splitter")
        void payloadSplitterAppended() {
            assertThat(summary(annotation("withPayloadSplitter")))
                    .isEqualTo("country-by-code : expiry=1 HOURS, splitter='mySplitter'");
        }

        @Test
        @DisplayName("recoverAll true → appends recoverAll=true")
        void recoverAllAppendedWhenTrue() {
            assertThat(summary(annotation("withRecoverAllTrue")))
                    .isEqualTo("country-by-code : expiry=1 HOURS, recoverAll=true");
        }

        @Test
        @DisplayName("recoverAll false → omitted entirely (not 'recoverAll=false')")
        void recoverAllOmittedWhenFalse() {
            assertThat(summary(annotation("minimal"))).doesNotContain("recoverAll");
        }

        @Test
        @DisplayName("all optional attributes set → every clause appended, in declaration order")
        void allAttributesCombined() {
            assertThat(summary(annotation("allCombined"))).isEqualTo("country-by-code : expiry=1 HOURS, "
                    + "domain='country', keyGenerator='myKeyGen', expiryPolicy='myExpiryPolicy', "
                    + "splitter='mySplitter', recoverAll=true");
        }
    }
}
