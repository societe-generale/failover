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

package com.societegenerale.failover.core.scanner;

import com.societegenerale.failover.annotations.Failover;
import lombok.AllArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anand Manissery
 */
class DefaultFailoverScannerTest {

    private FailoverScanner failoverScanner;

    @BeforeEach
    public void setUp() {
        failoverScanner = new DefaultFailoverScanner("com.societegenerale.failover.core.scanner");
    }

    @Test
    void shouldReturnAllFailoverAnnotations() {
        List<Failover> result = failoverScanner.findAllFailover();
        assertThat(result).hasSize(2);
    }

    @Test
    void shouldReturnFailoverAnnotationByGivenName() {
        Failover result = failoverScanner.findFailoverByName("client-by-id");
        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("client-by-id");
        assertThat(result.expiryDuration()).isEqualTo(1);
        assertThat(result.expiryUnit()).isEqualTo(ChronoUnit.HOURS);
    }

    interface ClientReferential {
        @Failover(name = "client-by-id")
        Client findClientById(Long id);

        @Failover(name = "client-all")
        List<Client> findAllClients();
    }

    @AllArgsConstructor
    static class Client {
        private Long id;
        private String name;
    }
}