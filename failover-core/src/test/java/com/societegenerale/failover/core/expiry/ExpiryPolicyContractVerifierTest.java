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

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

/**
 * Verifies the {@link ExpiryPolicyContractVerifier} harness itself: it must pass a correct policy
 * (the {@link DefaultExpiryPolicy}) and flag each common contract violation.
 *
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class ExpiryPolicyContractVerifierTest {

    @Mock
    private Failover failover;

    @Mock
    private FailoverClock clock;

    @BeforeEach
    void setUp() {
        lenient().when(clock.now()).thenReturn(Instant.now());
        lenient().when(failover.expiryDuration()).thenReturn(1L);
        lenient().when(failover.expiryUnit()).thenReturn(ChronoUnit.HOURS);
    }

    @Test
    @DisplayName("passes for a correct policy (DefaultExpiryPolicy)")
    void passesForCorrectPolicy() {
        var policy = new DefaultExpiryPolicy<String>(clock, new BasicFailoverExpiryExtractor());

        assertThatCode(() -> ExpiryPolicyContractVerifier.forPolicy(policy)
                .withFailover(failover)
                .withSamplePayload("payload")
                .verify())
                .doesNotThrowAnyException();
    }

    @Test
    @DisplayName("flags a policy whose computeExpiry returns null")
    void flagsNullComputeExpiry() {
        ExpiryPolicy<String> broken = new ExpiryPolicy<>() {
            @Override public Instant computeExpiry(Failover f) { return null; }
            @Override public boolean isExpired(Failover f, ReferentialPayload<String> p) { return false; }
        };

        assertThatThrownBy(() -> ExpiryPolicyContractVerifier.forPolicy(broken)
                .withFailover(failover).withSamplePayload("p").verify())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("returned null");
    }

    @Test
    @DisplayName("flags a policy whose computeExpiry returns a past instant")
    void flagsPastComputeExpiry() {
        ExpiryPolicy<String> broken = new ExpiryPolicy<>() {
            @Override public Instant computeExpiry(Failover f) { return Instant.now().minusSeconds(60); }
            @Override public boolean isExpired(Failover f, ReferentialPayload<String> p) { return false; }
        };

        assertThatThrownBy(() -> ExpiryPolicyContractVerifier.forPolicy(broken)
                .withFailover(failover).withSamplePayload("p").verify())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("past instant");
    }

    @Test
    @DisplayName("flags a policy whose isExpired ignores the payload expireOn")
    void flagsIsExpiredIgnoringExpireOn() {
        ExpiryPolicy<String> broken = new ExpiryPolicy<>() {
            @Override public Instant computeExpiry(Failover f) { return Instant.now().plusSeconds(3600); }
            @Override public boolean isExpired(Failover f, ReferentialPayload<String> p) { return false; } // never expires
        };

        assertThatThrownBy(() -> ExpiryPolicyContractVerifier.forPolicy(broken)
                .withFailover(failover).withSamplePayload("p").verify())
                .isInstanceOf(AssertionError.class)
                .hasMessageContaining("10 years ago");
    }

    @Test
    @DisplayName("verifyComputeExpiry alone returns the computed instant for payload-driven policies")
    void verifyComputeExpiryOnly() {
        var policy = new DefaultExpiryPolicy<String>(clock, new BasicFailoverExpiryExtractor());
        Instant expiry = ExpiryPolicyContractVerifier.forPolicy(policy)
                .withFailover(failover).verifyComputeExpiry();
        assertThatCode(() -> { Instant ignored = expiry; }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("rejects verify() before a Failover is supplied")
    void rejectsMissingFailover() {
        var policy = new DefaultExpiryPolicy<String>(clock, new BasicFailoverExpiryExtractor());
        assertThatThrownBy(() -> ExpiryPolicyContractVerifier.forPolicy(policy).verify())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("withFailover");
    }

    @Test
    @DisplayName("rejects a null policy")
    void rejectsNullPolicy() {
        assertThatThrownBy(() -> ExpiryPolicyContractVerifier.forPolicy(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
