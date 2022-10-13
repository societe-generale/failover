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

package com.societegenerale.failover.execution.resilience;

import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.execution.resilience.demo.Client;
import com.societegenerale.failover.execution.resilience.demo.ClientReferentialExecutor;
import com.societegenerale.failover.execution.resilience.demo.ClientService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

/**
 * @author Anand Manissery
 */
@SpringBootTest
class ResilienceFailoverExecutionTest {

    private static final LocalDateTime NOW = LocalDateTime.now();

    private final Client client = new Client(1L, "TATA");

    @MockBean
    private FailoverClock clock;

    @MockBean
    private ClientReferentialExecutor clientReferentialExecutor;

    @Autowired
    private FailoverStore<Object> failoverStore;

    @Autowired
    private ClientService clientService;

    @BeforeEach
    void setUp() {
        given(clock.now()).willReturn(NOW);
    }

    @Test
    void shouldStoreTheClientInfoAfterSuccessfulCall() {
        //Given
        given(clientReferentialExecutor.findClientById(1L)).willReturn(client);

        //When
        Client result = clientService.findClientById(1L);

        //Then
        assertThat(result).isEqualTo(client);
        assertThat(result.getUpToDate()).isTrue();
        assertThat(result.getAsOf()).isEqualTo(NOW);

        Optional<ReferentialPayload<Object>> optionalClientReferentialPayload = failoverStore.find("client-by-id", "1");
        assertThat(optionalClientReferentialPayload).isPresent().contains(new ReferentialPayload<>("client-by-id", "1", true, NOW, NOW.plusHours(1), client));
    }

    @Test
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

        Optional<ReferentialPayload<Object>> optionalClientReferentialPayload = failoverStore.find("client-by-id", "1");
        assertThat(optionalClientReferentialPayload).isPresent().contains(new ReferentialPayload<>("client-by-id", "1", false, NOW, NOW.plusHours(1), client));
    }

    @Test
    void shouldNotRecoverTheClientInfoIsExpired() {
        //Given
        given(clock.now()).willReturn(NOW, NOW, NOW, NOW.plusHours(2));
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

        Optional<ReferentialPayload<Object>> optionalClientReferentialPayload = failoverStore.find("client-by-id", "1");
        assertThat(optionalClientReferentialPayload).isNotPresent();
    }

    @Test
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

        Optional<ReferentialPayload<Object>> optionalClientReferentialPayload = failoverStore.find("client-by-id", "1");
        assertThat(optionalClientReferentialPayload).isNotPresent();
    }

    @Test
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
        Optional<ReferentialPayload<Object>> optionalClientReferentialPayload = failoverStore.find("client-by-id", "1");
        assertThat(optionalClientReferentialPayload).isPresent().contains(new ReferentialPayload<>("client-by-id", "1", false, NOW, NOW.plusHours(1), client));
    }
}