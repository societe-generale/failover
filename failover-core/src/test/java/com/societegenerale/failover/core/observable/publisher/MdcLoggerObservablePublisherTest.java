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

package com.societegenerale.failover.core.observable.publisher;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
import com.societegenerale.failover.core.observable.Metrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class MdcLoggerObservablePublisherTest {

    private final Metrics metrics = Metrics.of("failover");

    @Mock
    private Appender<ILoggingEvent> appender;

    @Captor
    private ArgumentCaptor<ILoggingEvent> captor;

    private MdcLoggerObservablePublisher publisher;

    @BeforeEach
    void setUp() {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logger.addAppender(appender);
        MDC.clear();
        publisher = new MdcLoggerObservablePublisher();
    }

    @Test
    @DisplayName("should publish metrics to MDC and log")
    void shouldPublishMetricsToMdcAndLog() {
        publisher.publish(metrics);
        verify(appender).doAppend(captor.capture());
        assertThat(captor.getValue().getMDCPropertyMap()).containsAllEntriesOf(metrics.getInfo());
        assertThat(captor.getValue().getFormattedMessage()).contains("failover");
    }

    @Test
    @DisplayName("should restore MDC after publish")
    void shouldRestoreMdcAfterPublish() {
        MDC.put("existing-key", "existing-value");
        publisher.publish(metrics);
        assertThat(MDC.getCopyOfContextMap()).containsEntry("existing-key", "existing-value");
        assertThat(MDC.getCopyOfContextMap()).doesNotContainKey("failover-action");
    }

    @Test
    @DisplayName("should clear MDC after publish when MDC was empty before")
    void shouldClearMdcAfterPublishWhenMdcEmpty() {
        publisher.publish(metrics);
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }
}
