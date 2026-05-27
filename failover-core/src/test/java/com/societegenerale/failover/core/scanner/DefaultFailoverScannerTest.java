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
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
    @DisplayName("should return all failover annotations")
    void shouldReturnAllFailoverAnnotations() {
        List<Failover> result = failoverScanner.findAllFailover();
        assertThat(result).hasSize(2);
    }

    @Test
    @DisplayName("should return failover annotation by given name")
    void shouldReturnFailoverAnnotationByGivenName() {
        Failover result = failoverScanner.findFailoverByName("client-by-id");
        assertThat(result).isNotNull();
        assertThat(result.name()).isEqualTo("client-by-id");
        assertThat(result.expiryDuration()).isEqualTo(1);
        assertThat(result.expiryUnit()).isEqualTo(ChronoUnit.HOURS);
    }

    @Test
    @DisplayName("should throw IllegalStateException when packageToScan is null")
    void constructorThrowsWhenPackageToScanIsNull() {
        assertThatThrownBy(() -> new DefaultFailoverScanner(null))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failover.package-to-scan must not be blank");
    }

    @Test
    @DisplayName("should throw IllegalStateException when packageToScan is blank")
    void constructorThrowsWhenPackageToScanIsBlank() {
        assertThatThrownBy(() -> new DefaultFailoverScanner("   "))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failover.package-to-scan must not be blank");
    }

    @Test
    @DisplayName("should throw IllegalStateException when packageToScan is empty string")
    void constructorThrowsWhenPackageToScanIsEmpty() {
        assertThatThrownBy(() -> new DefaultFailoverScanner(""))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("failover.package-to-scan must not be blank");
    }

    /**
     * Regression: duplicate @Failover names cause toMap() to throw
     * IllegalStateException("Duplicate key ...") which is then wrapped in a
     * FailoverScannerException with a misleading "reflections library" message.
     * The scanner should throw FailoverScannerException with a message that
     * clearly names the duplicate failover name so the developer can fix it.
     */
    @Test
    @DisplayName("should throw FailoverScannerException with clear duplicate-name message")
    void constructorThrowsFailoverScannerExceptionWithClearMessageOnDuplicateNames() {
        assertThatThrownBy(() ->
                new DefaultFailoverScanner("com.societegenerale.failover.core.fixtures.duplicate"))
                .isInstanceOf(FailoverScannerException.class)
                .hasMessageContaining("Duplicate @Failover name")
                .hasMessageContaining("duplicate-name");
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