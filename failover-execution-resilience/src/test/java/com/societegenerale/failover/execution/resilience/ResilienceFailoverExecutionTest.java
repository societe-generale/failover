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

package com.societegenerale.failover.execution.resilience;

import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.execution.resilience.demo.Client;
import com.societegenerale.failover.execution.resilience.demo.ClientReferentialExecutor;
import com.societegenerale.failover.execution.resilience.demo.ClientService;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/// @author Anand Manissery
@SpringBootTest
class ResilienceFailoverExecutionTest {

    private static final Instant NOW = Instant.now();

    private final Client client = new Client(1L, "TATA");

    @MockitoBean
    private FailoverClock clock;

    @MockitoBean
    private ClientReferentialExecutor clientReferentialExecutor;

    @Autowired
    private FailoverStore<Object> failoverStore;

    @Autowired
    private ClientService clientService;

    @Autowired
    private CircuitBreakerRegistry circuitBreakerRegistry;

    @BeforeEach
    void setUp() {
        given(clock.now()).willReturn(NOW);
    }

    @AfterEach
    void tearDown() {
        // The failoverStore is a shared singleton across the test class — clear the entry so tests
        // are isolated regardless of execution order.
        failoverStore.delete(new ReferentialPayload<>("client-by-id", "1", false, NOW, NOW, null));
        circuitBreakerRegistry.circuitBreaker("client-by-id").reset();
    }

    @Test
    @DisplayName("should store the client info after successful call")
    void shouldStoreTheClientInfoAfterSuccessfulCall() {
        //Given
        given(clientReferentialExecutor.findClientById(1L)).willReturn(client);

        //When
        Client result = clientService.findClientById(1L);

        //Then
        assertThat(result).isEqualTo(client);
        assertThat(result.getUpToDate()).isTrue();
        assertThat(result.getAsOf()).isEqualTo(NOW);

        var optionalClientReferentialPayload = failoverStore.find("client-by-id", "1");
        assertThat(optionalClientReferentialPayload).isPresent().contains(new ReferentialPayload<>("client-by-id", "1", false, NOW, NOW.plusSeconds(3600), client));
    }

    @Test
    @DisplayName("should recover the client info on failure")
    void shouldRecoverTheClientInfoOnFailure() {
        //Given
        given(clientReferentialExecutor.findClientById(1L)).willReturn(client);

        Client result = clientService.findClientById(1L);
        assertThat(result).isEqualTo(client);
        assertThat(result.getUpToDate()).isTrue();
        assertThat(result.getAsOf()).isEqualTo(NOW);

        given(clientReferentialExecutor.findClientById(1L)).willThrow(new RuntimeException("Some Failure"));

        //When
        Client recovered = clientService.findClientById(1L);

        //then
        assertThat(recovered).isEqualTo(client);
        assertThat(recovered.getUpToDate()).isFalse();
        assertThat(recovered.getAsOf()).isEqualTo(NOW);

        var optionalClientReferentialPayload = failoverStore.find("client-by-id", "1");
        assertThat(optionalClientReferentialPayload).isPresent().contains(new ReferentialPayload<>("client-by-id", "1", false, NOW, NOW.plusSeconds(3600), client));
    }

    @Test
    @DisplayName("should not recover the client info is expired")
    void shouldNotRecoverTheClientInfoIsExpired() {
        //Given
        given(clock.now()).willReturn(NOW, NOW, NOW.plusSeconds(7200));
        given(clientReferentialExecutor.findClientById(1L)).willReturn(client);
        Client result = clientService.findClientById(1L);
        assertThat(result).isEqualTo(client);
        assertThat(result.getUpToDate()).isTrue();
        assertThat(result.getAsOf()).isEqualTo(NOW);

        given(clientReferentialExecutor.findClientById(1L)).willThrow(new RuntimeException("Some Failure"));

        //When
        Client recovered = clientService.findClientById(1L);

        //then
        assertThat(recovered).isNull();

        var optionalClientReferentialPayload = failoverStore.find("client-by-id", "1");
        assertThat(optionalClientReferentialPayload).isNotPresent();
    }

    @Test
    @DisplayName("should return the actual client info when actual call is success and failover has internal error")
    void shouldReturnTheActualClientInfoWhenActualCallIsSuccessAndFailoverHasInternalError() {
        //Given
        given(clientReferentialExecutor.findClientById(1L)).willReturn(client);
        given(clock.now()).willThrow(new RuntimeException("Internal Error"));

        //When
        Client result = clientService.findClientById(1L);

        //Then
        assertThat(result).isEqualTo(client);
        assertThat(result.getUpToDate()).isNull();
        assertThat(result.getAsOf()).isNull();

        var optionalClientReferentialPayload = failoverStore.find("client-by-id", "1");
        assertThat(optionalClientReferentialPayload).isNotPresent();
    }

    @Test
    @DisplayName("should return null when failover recovery has internal error")
    void shouldReturnNullWhenFailoverRecoveryHasInternalError() {
        //Given
        given(clientReferentialExecutor.findClientById(1L)).willReturn(client);
        Client result = clientService.findClientById(1L);
        assertThat(result).isEqualTo(client);
        assertThat(result.getUpToDate()).isTrue();
        assertThat(result.getAsOf()).isEqualTo(NOW);

        given(clientReferentialExecutor.findClientById(1L)).willThrow(new RuntimeException("Some Failure"));
        given(clock.now()).willThrow(new RuntimeException("Internal Error"));

        //When
        Client recovered = clientService.findClientById(1L);

        //Then
        assertThat(recovered).isNull();
        var optionalClientReferentialPayload = failoverStore.find("client-by-id", "1");
        assertThat(optionalClientReferentialPayload).isPresent().contains(new ReferentialPayload<>("client-by-id", "1", false, NOW, NOW.plusSeconds(3600), client));
    }

    @Test
    @DisplayName("failover recovery works across circuit-breaker states: CLOSED → OPEN → HALF_OPEN (audit I-18)")
    void recoveryWorksAcrossCircuitBreakerStateTransitions() {
        // The circuit breaker is named after the failover point.
        CircuitBreaker breaker = circuitBreakerRegistry.circuitBreaker("client-by-id");

        // ── CLOSED — successful call stores the entry, returns the live value ──
        given(clientReferentialExecutor.findClientById(1L)).willReturn(client);
        Client stored = clientService.findClientById(1L);
        assertThat(stored.getUpToDate()).isTrue();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.CLOSED);

        // ── OPEN — calls are short-circuited (CallNotPermittedException); failover still serves from the store ──
        breaker.transitionToOpenState();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.OPEN);
        Client openRecovered = clientService.findClientById(1L);
        assertThat(openRecovered).isEqualTo(client);
        assertThat(openRecovered.getUpToDate()).isFalse();   // served by failover, not the blocked upstream

        // ── HALF_OPEN — a permitted trial call succeeds → live value returned, no failover needed ──
        breaker.transitionToHalfOpenState();
        assertThat(breaker.getState()).isEqualTo(CircuitBreaker.State.HALF_OPEN);
        Client trial = clientService.findClientById(1L);
        assertThat(trial).isEqualTo(client);
        assertThat(trial.getUpToDate()).isTrue();            // upstream healthy on the trial
    }
}