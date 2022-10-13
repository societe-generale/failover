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

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.societegenerale.failover.core.clock.FailoverClock;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.BDDMockito;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class MetricsReportPublisherTest {

    private LocalDateTime now = LocalDateTime.now();

    private Metrics metrics = Metrics.of("failover");

    @Mock
    private Appender<ILoggingEvent> appender;

    @Mock
    private FailoverClock clock;

    @Captor
    private ArgumentCaptor<ILoggingEvent> captor;

    private MetricsReportPublisher metricsReportPublisher;

    @BeforeEach
    void setUp() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logger.addAppender(appender);
        metricsReportPublisher = new MetricsReportPublisher(clock);
        BDDMockito.given(clock.now()).willReturn(now);
    }

    @Test
    void shouldPublishMetrics() {
        metricsReportPublisher.publish(metrics);
        verify(appender).doAppend(captor.capture());
        assertThat(captor.getValue().getMDCPropertyMap()).containsAllEntriesOf(metrics.getInfo());
    }
}