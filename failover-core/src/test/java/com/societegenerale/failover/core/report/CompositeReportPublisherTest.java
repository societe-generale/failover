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

package com.societegenerale.failover.core.report;

import com.societegenerale.failover.core.clock.FailoverClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.Arrays;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class CompositeReportPublisherTest {

    private static final Instant NOW = Instant.now();

    @Mock
    private ReportPublisher publisher1;

    @Mock
    private ReportPublisher publisher2;

    @Mock
    private FailoverClock failoverClock;

    private Metrics metrics;

    private CompositeReportPublisher compositeReportPublisher;

    @BeforeEach
    void setUp() {
        metrics = Metrics.of("failover");
        compositeReportPublisher = new CompositeReportPublisher(Arrays.asList(publisher1, publisher2), failoverClock);
    }

    @Test
    @DisplayName("should publish metrics with all publishers")
    void shouldPublishMetricsWithAllPublishers() {
        when(failoverClock.now()).thenReturn(NOW);
        compositeReportPublisher.publish(metrics);
        assertThat(metrics.getInfo()).containsEntry("failover-report-publish-on", NOW.toString());
        verify(publisher1).publish(metrics);
        verify(publisher2).publish(metrics);
    }
}