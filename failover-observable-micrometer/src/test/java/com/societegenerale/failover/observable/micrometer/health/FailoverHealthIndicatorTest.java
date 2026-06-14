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

package com.societegenerale.failover.observable.micrometer.health;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.scanner.FailoverScanner;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.health.contributor.Health;
import org.springframework.boot.health.contributor.Status;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Anand Manissery
 */
class FailoverHealthIndicatorTest {

    private FailoverScanner scanner;
    private FailoverHealthIndicator indicator;

    @BeforeEach
    void setUp() {
        scanner = mock(FailoverScanner.class);
        indicator = new FailoverHealthIndicator(scanner);
    }

    @Test
    @DisplayName("UP when at least one @Failover is registered")
    void shouldBeUpWhenFailoversRegistered() {
        Failover fo = mock(Failover.class);
        when(fo.name()).thenReturn("my-failover");
        when(scanner.findAllFailover()).thenReturn(List.of(fo));

        Health health = indicator.health();
        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("registered-failovers", 1);
    }

    @Test
    @DisplayName("DOWN when no @Failover found — likely misconfiguration")
    void shouldBeDownWhenNoFailoversRegistered() {
        when(scanner.findAllFailover()).thenReturn(List.of());

        Health health = indicator.health();

        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.DOWN);
        assertThat(health.getDetails()).containsEntry("registered-failovers", 0);
        assertThat(health.getDetails()).containsKey("reason");
    }

    @Test
    @DisplayName("UP with correct count when multiple failovers registered")
    void shouldReportCorrectCountForMultipleFailovers() {
        Failover fo1 = mock(Failover.class);
        Failover fo2 = mock(Failover.class);
        Failover fo3 = mock(Failover.class);
        when(scanner.findAllFailover()).thenReturn(List.of(fo1, fo2, fo3));

        Health health = indicator.health();

        assertThat(health).isNotNull();
        assertThat(health.getStatus()).isEqualTo(Status.UP);
        assertThat(health.getDetails()).containsEntry("registered-failovers", 3);
    }
}
