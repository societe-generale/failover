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

import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class DefaultExpiryPolicyTest {

    private static final String FAILOVER_NAME = "failover-name";

    private static final String FAILOVER_KEY = "failover-key";

    private static final java.lang.String PAYLOAD = "Payload";

    private static final LocalDateTime now = LocalDateTime.now();

    @Mock
    private Failover failover;

    @Mock
    private FailoverClock clock;

    private DefaultExpiryPolicy<String> defaultExpiryPolicy;

    @BeforeEach
    void setUp() {
        defaultExpiryPolicy = new DefaultExpiryPolicy<>(clock);
    }

    @DisplayName("should return expiry based on the given duration and unit")
    @Test
    void shouldReturnExpiry() {
        given(failover.expiryDuration()).willReturn(10L);
        given(failover.expiryUnit()).willReturn(ChronoUnit.SECONDS);
        given(clock.now()).willReturn(now);
        LocalDateTime result = defaultExpiryPolicy.computeExpiry(failover);
        assertThat(result).isEqualTo(now.plusSeconds(10L));
    }

    @DisplayName("should return true when referential payload is expired")
    @Test
    void shouldReturnTrueWhenExpired() {
        given(clock.now()).willReturn(now);
        ReferentialPayload<String> referentialPayload = new ReferentialPayload<>(FAILOVER_NAME, FAILOVER_KEY, false, now.minusSeconds(11), now.minusSeconds(1), PAYLOAD);
        boolean result = defaultExpiryPolicy.isExpired(failover, referentialPayload);
        assertThat(result).isTrue();
    }

    @DisplayName("should return false when referential payload is not expired")
    @Test
    void shouldReturnFalseWhenNotExpired() {
        given(clock.now()).willReturn(now);
        ReferentialPayload<String> referentialPayload = new ReferentialPayload<>(FAILOVER_NAME, FAILOVER_KEY, false, now, now.plusSeconds(1), "Payload");
        boolean result = defaultExpiryPolicy.isExpired(failover, referentialPayload);
        assertThat(result).isFalse();
    }

    @DisplayName("should return false when referential payload is not expired and 'as of' has same time as 'expire on'")
    @Test
    void shouldReturnFalseWhenNotExpiredAndHasEqualTime() {
        given(clock.now()).willReturn(now);
        ReferentialPayload<String> referentialPayload = new ReferentialPayload<>(FAILOVER_NAME, FAILOVER_KEY, false, now, now, "Payload");
        boolean result = defaultExpiryPolicy.isExpired(failover, referentialPayload);
        assertThat(result).isFalse();
    }
}