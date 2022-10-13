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

package com.societegenerale.failover.core;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.expiry.ExpiryPolicy;
import com.societegenerale.failover.core.key.DefaultKeyGenerator;
import com.societegenerale.failover.core.key.KeyGenerator;
import com.societegenerale.failover.core.payload.*;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.domain.Referential;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.EqualsAndHashCode;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;
import java.util.Optional;

import static java.util.Collections.singletonList;
import static java.util.Optional.of;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class DefaultFailoverHandlerTest {

    private static final String FAILOVER_NAME = "failover-name";

    private final KeyGenerator keyGenerator = new DefaultKeyGenerator();

    private final LocalDateTime now = LocalDateTime.now();

    @Mock
    private Failover failover;

    @Mock
    private Throwable cause;

    @Mock
    private FailoverClock clock;

    @Mock
    private FailoverStore<ThirdParty> failoverStore;

    @Mock
    private ExpiryPolicy<ThirdParty> expiryPolicy;

    private final PayloadEnricher<ThirdParty> payloadEnricher = new DefaultPayloadEnricher<>();

    private DefaultFailoverHandler<ThirdParty> defaultFailoverHandler;

    @BeforeEach
    void setUp() {
        lenient().when(failover.name()).thenReturn(FAILOVER_NAME);
        defaultFailoverHandler = new DefaultFailoverHandler<>(keyGenerator, clock, failoverStore, expiryPolicy, payloadEnricher);
    }

    @DisplayName("should store the referential payload")
    @Test
    void shouldStoreTheReferential() {
        ThirdParty thirdParty = new ThirdParty(1L, "Tata", 1);
        ReferentialPayload<ThirdParty> referentialPayload = new ReferentialPayload<>(FAILOVER_NAME, "1", true, now, now, thirdParty);
        given(clock.now()).willReturn(now);
        given(expiryPolicy.computeExpiry(failover)).willReturn(now);

        ThirdParty result = defaultFailoverHandler.store(failover, singletonList(1L), thirdParty);

        assertThat(result).isEqualTo(thirdParty);
        assertThat(result.getAsOf()).isEqualTo(now);
        assertThat(result.getUpToDate()).isTrue();
        verify(failoverStore).store(referentialPayload);
    }

    @DisplayName("should recover the referential payload when not expired")
    @Test
    void shouldRecoverTheReferentialWhenNotExpired() {
        ThirdParty thirdParty = new ThirdParty(1L, "Tata", 1);
        ReferentialPayload<ThirdParty> referentialPayload = new ReferentialPayload<>(FAILOVER_NAME, "1", false, now, now, thirdParty);
        given(failoverStore.find(FAILOVER_NAME, "1")).willReturn(of(referentialPayload));

        ThirdParty result = defaultFailoverHandler.recover(failover, singletonList(1L), ThirdParty.class, cause);

        assertThat(result).isEqualTo(thirdParty);
        assertThat(result.getAsOf()).isEqualTo(now);
        assertThat(result.getUpToDate()).isFalse();
        verify(failoverStore).find(FAILOVER_NAME, "1");
        verify(failoverStore, never()).delete(referentialPayload);
        verify(expiryPolicy).isExpired(failover, referentialPayload);
    }

    @DisplayName("should return null when no referential payload found for the given name and key")
    @Test
    void shouldReturnNullWhenReferentialIsNotFound() {
        given(failoverStore.find(FAILOVER_NAME, "1")).willReturn(Optional.empty());

        ThirdParty result = defaultFailoverHandler.recover(failover, singletonList(1L), ThirdParty.class, cause);

        assertThat(result).isNull();
        verify(failoverStore).find(FAILOVER_NAME, "1");
        verify(failoverStore, never()).delete(any());
        verify(expiryPolicy, never()).isExpired(any(), any());
    }

    @DisplayName("should return null when referential payload is expired")
    @Test
    void shouldReturnNullWhenReferentialIsExpired() {
        ThirdParty thirdParty = new ThirdParty(1L, "Tata", 1);
        ReferentialPayload<ThirdParty> referentialPayload = new ReferentialPayload<>(FAILOVER_NAME, "1", false, now, now, thirdParty);
        given(failoverStore.find(FAILOVER_NAME, "1")).willReturn(of(referentialPayload));
        given(expiryPolicy.isExpired(failover, referentialPayload)).willReturn(true);

        ThirdParty result = defaultFailoverHandler.recover(failover, singletonList(1L), ThirdParty.class, cause);

        assertThat(result).isNull();
        verify(failoverStore).find(FAILOVER_NAME, "1");
        verify(failoverStore).delete(referentialPayload);
        verify(expiryPolicy).isExpired(failover, referentialPayload);
    }

    @Test
    void shouldExecuteClean() {
        given(clock.now()).willReturn(now);
        defaultFailoverHandler.clean();
        verify(failoverStore).cleanByExpiry(now);
    }
}

@Data
@EqualsAndHashCode(callSuper = true)
@AllArgsConstructor
class ThirdParty extends Referential {
    private Long id;
    private String name;
    private int score;
}
