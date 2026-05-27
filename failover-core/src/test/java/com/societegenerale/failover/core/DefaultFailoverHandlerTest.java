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
import com.societegenerale.failover.core.payload.DefaultPayloadEnricher;
import com.societegenerale.failover.core.payload.PayloadEnricher;
import com.societegenerale.failover.core.payload.ReferentialPayload;
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
import java.util.List;
import java.util.Optional;

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

    @Test
    @DisplayName("should store the referential payload")
    void shouldStoreTheReferential() {
        var thirdParty = new ThirdParty(1L, "Tata", 1);
        var referentialPayload = new ReferentialPayload<>(FAILOVER_NAME, "1", true, now, now, thirdParty);
        given(clock.now()).willReturn(now);
        given(expiryPolicy.computeExpiry(failover)).willReturn(now);

        ThirdParty result = defaultFailoverHandler.store(failover, List.of(1L), thirdParty);

        assertThat(result).isEqualTo(thirdParty);
        assertThat(result.getAsOf()).isEqualTo(now);
        assertThat(result.getUpToDate()).isTrue();
        verify(failoverStore).store(referentialPayload);
    }

    @Test
    @DisplayName("should recover the referential payload when not expired")
    void shouldRecoverTheReferentialWhenNotExpired() {
        var thirdParty = new ThirdParty(1L, "Tata", 1);
        var referentialPayload = new ReferentialPayload<>(FAILOVER_NAME, "1", false, now, now, thirdParty);
        given(failoverStore.find(FAILOVER_NAME, "1")).willReturn(of(referentialPayload));

        ThirdParty result = defaultFailoverHandler.recover(failover, List.of(1L), ThirdParty.class, cause);

        assertThat(result).isNotNull();
        assertThat(result).isEqualTo(thirdParty);
        assertThat(result.getAsOf()).isEqualTo(now);
        assertThat(result.getUpToDate()).isFalse();
        verify(failoverStore).find(FAILOVER_NAME, "1");
        verify(failoverStore, never()).delete(referentialPayload);
        verify(expiryPolicy).isExpired(failover, referentialPayload);
    }

    @Test
    @DisplayName("should return null when no referential payload found for the given name and key")
    void shouldReturnNullWhenReferentialIsNotFound() {
        given(failoverStore.find(FAILOVER_NAME, "1")).willReturn(Optional.empty());

        ThirdParty result = defaultFailoverHandler.recover(failover, List.of(1L), ThirdParty.class, cause);

        assertThat(result).isNull();
        verify(failoverStore).find(FAILOVER_NAME, "1");
        verify(failoverStore, never()).delete(any());
        verify(expiryPolicy, never()).isExpired(any(), any());
    }

    @Test
    @DisplayName("should return null when referential payload is expired")
    void shouldReturnNullWhenReferentialIsExpired() {
        var thirdParty = new ThirdParty(1L, "Tata", 1);
        var referentialPayload = new ReferentialPayload<>(FAILOVER_NAME, "1", false, now, now, thirdParty);
        given(failoverStore.find(FAILOVER_NAME, "1")).willReturn(of(referentialPayload));
        given(expiryPolicy.isExpired(failover, referentialPayload)).willReturn(true);

        ThirdParty result = defaultFailoverHandler.recover(failover, List.of(1L), ThirdParty.class, cause);

        assertThat(result).isNull();
        verify(failoverStore).find(FAILOVER_NAME, "1");
        verify(failoverStore).delete(referentialPayload);
        verify(expiryPolicy).isExpired(failover, referentialPayload);
    }

    @Test
    @DisplayName("should enrich metadata on Referential payload with exception info when recovering")
    void shouldEnrichMetadataOnReferentialPayloadWithExceptionInfoWhenRecovering() {
        var cause = new RuntimeException("upstream-failure");
        var thirdParty = new ThirdParty(1L, "Tata", 1);
        var referentialPayload = new ReferentialPayload<>(FAILOVER_NAME, "1", false, now, now, thirdParty);
        given(failoverStore.find(FAILOVER_NAME, "1")).willReturn(of(referentialPayload));

        ThirdParty result = defaultFailoverHandler.recover(failover, List.of(1L), ThirdParty.class, cause);

        assertThat(result).isNotNull();
        assertThat(result.getMetadata().getInfo())
                .containsEntry("exception-name", RuntimeException.class.getName())
                .containsEntry("cause", "upstream-failure");
    }

    @Test
    @DisplayName("should return null without NPE when payload is null")
    void shouldReturnNullWithoutNpeWhenPayloadIsNull() {
        // null guard must skip clock/expiryPolicy/store — no stubs needed
        ThirdParty result = defaultFailoverHandler.store(failover, List.of(1L), null);

        assertThat(result).isNull();
        verify(failoverStore, never()).store(any());
    }

    @Test
    @DisplayName("should execute clean")
    void shouldExecuteClean() {
        given(clock.now()).willReturn(now);
        defaultFailoverHandler.clean();
        verify(failoverStore).cleanByExpiry(now);
    }

    @Data
    @EqualsAndHashCode(callSuper = true)
    @AllArgsConstructor
    static class ThirdParty extends Referential {
        private Long id;
        private String name;
        private int score;
    }
}
