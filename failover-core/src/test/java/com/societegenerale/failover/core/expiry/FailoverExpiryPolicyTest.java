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
import com.societegenerale.failover.core.payload.ReferentialPayload;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.time.LocalDateTime.now;
import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class FailoverExpiryPolicyTest {

    private static final String EXPIRY_POLICY_NAME = "failover-expiry-policy";

    private static final ReferentialPayload<String> REFERENTIAL_PAYLOAD = new ReferentialPayload<>("name", "key", true, now(),  now(), "PAYLOAD");

    @Mock
    private Failover failover;

    @Mock
    private ExpiryPolicy<String> defaultExpiryPolicy;

    @Mock
    private ExpiryPolicy<String> customExpiryPolicy;

    @Mock
    private ExpiryPolicyLookup<String> expiryPolicyLookup;

    private FailoverExpiryPolicy<String> failoverExpiryPolicy;

    @BeforeEach
    void setUp() {
        failoverExpiryPolicy = new FailoverExpiryPolicy<>(defaultExpiryPolicy, expiryPolicyLookup);
    }

    @Test
    void computeExpiryWithDefaultExpiryPolicyWhenNoExpiryPolicySpecified() {
        given(failover.expiryPolicy()).willReturn("");

        failoverExpiryPolicy.computeExpiry(failover);

        verify(defaultExpiryPolicy).computeExpiry(failover);
        verify(customExpiryPolicy, never()).computeExpiry(failover);
    }

    @Test
    void computeExpiryWithCustomExpiryPolicyWhenExpiryPolicySpecified() {
        given(failover.expiryPolicy()).willReturn(EXPIRY_POLICY_NAME);
        given(expiryPolicyLookup.lookup(EXPIRY_POLICY_NAME)).willReturn(customExpiryPolicy);

        failoverExpiryPolicy.computeExpiry(failover);

        verify(customExpiryPolicy).computeExpiry(failover);
        verify(defaultExpiryPolicy, never()).computeExpiry(failover);
    }

    @Test
    void shouldThrowExceptionWhenNoCustomKeyGeneratorFoundForAGivenKeyGeneratorName() {
        when(failover.name()).thenReturn("failover-xyz");
        given(failover.expiryPolicy()).willReturn(EXPIRY_POLICY_NAME);
        given(expiryPolicyLookup.lookup(EXPIRY_POLICY_NAME)).willReturn(null);

        ExpiryPolicyNotFoundException exception = assertThrows(ExpiryPolicyNotFoundException.class, () -> failoverExpiryPolicy.computeExpiry(failover));

        assertThat(exception).isInstanceOf(ExpiryPolicyNotFoundException.class);
        assertThat(exception.getMessage()).isEqualTo("No matching ExpiryPolicy bean found for failover 'failover-xyz' with expiry policy qualifier 'failover-expiry-policy'. Neither qualifier match nor bean name match!");
    }

    @Test
    void checkExpiryWithDefaultExpiryPolicyWhenNoExpiryPolicySpecified() {
        given(failover.expiryPolicy()).willReturn("");

        failoverExpiryPolicy.isExpired(failover, REFERENTIAL_PAYLOAD);

        verify(defaultExpiryPolicy).isExpired(failover, REFERENTIAL_PAYLOAD);
        verify(customExpiryPolicy, never()).isExpired(failover, REFERENTIAL_PAYLOAD);
    }

    @Test
    void checkExpiryWithCustomExpiryPolicyWhenExpiryPolicySpecified() {
        given(failover.expiryPolicy()).willReturn(EXPIRY_POLICY_NAME);
        given(expiryPolicyLookup.lookup(EXPIRY_POLICY_NAME)).willReturn(customExpiryPolicy);

        failoverExpiryPolicy.isExpired(failover, REFERENTIAL_PAYLOAD);

        verify(customExpiryPolicy).isExpired(failover, REFERENTIAL_PAYLOAD);
        verify(defaultExpiryPolicy, never()).isExpired(failover, REFERENTIAL_PAYLOAD);
    }

    @Test
    void checkThrowExceptionWhenNoCustomKeyGeneratorFoundForAGivenKeyGeneratorName() {
        when(failover.name()).thenReturn("failover-xyz");
        given(failover.expiryPolicy()).willReturn(EXPIRY_POLICY_NAME);
        given(expiryPolicyLookup.lookup(EXPIRY_POLICY_NAME)).willReturn(null);

        ExpiryPolicyNotFoundException exception = assertThrows(ExpiryPolicyNotFoundException.class, () -> failoverExpiryPolicy.isExpired(failover, REFERENTIAL_PAYLOAD));

        assertThat(exception).isInstanceOf(ExpiryPolicyNotFoundException.class);
        assertThat(exception.getMessage()).isEqualTo("No matching ExpiryPolicy bean found for failover 'failover-xyz' with expiry policy qualifier 'failover-expiry-policy'. Neither qualifier match nor bean name match!");
    }
}