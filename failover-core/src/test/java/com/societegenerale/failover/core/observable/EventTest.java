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

package com.societegenerale.failover.core.observable;

import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.Appender;
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

import java.util.AbstractMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class EventTest {

    @Mock
    private Appender<ILoggingEvent> appender;

    @Captor
    private ArgumentCaptor<ILoggingEvent> captor;

    @BeforeEach
    void setUp() {
        var logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logger.addAppender(appender);
        MDC.clear();
    }

    @Test
    @DisplayName("should create a new technical event")
    void shouldCreateANewTechnicalEvent() {
        Event event = Event.technical("An event");
        assertThat(event.getAttributes())
                .containsEntry("type", "TECHNICAL")
                .containsEntry("metricName", "An event");
    }

    @Test
    @DisplayName("should add an attribute")
    void shouldAddAnAttribute() {
        Event event = Event.technical("An event").addAttribute("new", "attribute");
        assertThat(event.getAttributes())
                .containsEntry("type", "TECHNICAL")
                .containsEntry("metricName", "An event")
                .containsEntry("new", "attribute");
    }

    @Test
    @DisplayName("should publish event through logging")
    void shouldPublishEventThroughLogging() {
        Event event = Event.technical("An event").addAttribute("new", "attribute");
        event.publish();
        verify(appender).doAppend(captor.capture());
        assertThat(captor.getValue().getLoggerName()).isEqualTo("TECHNICAL");
        assertThat(captor.getValue().getMDCPropertyMap())
                .containsEntry("new", "attribute")
                .containsEntry("type", "TECHNICAL");
    }

    @Test
    @DisplayName("should restore mdc after publish")
    void shouldRestoreMdcAfterPublish() {
        MDC.put("existingKey", "existingValue");
        MDC.put("existingKey2", "existingValue2");
        Event event = Event.technical("An event").addAttribute("existingKey", "newValue");
        event.publish();
        assertThat(MDC.getCopyOfContextMap()).containsAllEntriesOf(Map.of(
                "existingKey", "existingValue",
                "existingKey2", "existingValue2"));
    }

    @Test
    @DisplayName("should throw exception when modifying from outside")
    void shouldThrowExceptionWhenModifyingFromOutside() {
        Event event = Event.technical("An event").addAttribute("existingKey", "newValue");
        assertThat(event.getAttributes()).contains(new AbstractMap.SimpleEntry<>("existingKey", "newValue"));
        Map<String, String> result = event.getAttributes();
        UnsupportedOperationException exception = assertThrows(UnsupportedOperationException.class, () -> result.put("someKey", "someValue"));
        assertThat(exception).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("should clear mdc after publish if no mdc present before publish")
    void shouldClearMdcAfterPublishIfNoMdcPresentBeforePublish() {
        Event event = Event.technical("An event").addAttribute("existingKey", "newValue");
        event.publish();
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    @DisplayName("should check equality")
    void shouldCheckEquality() {
        Event event1 = Event.technical("An event").addAttribute("key-1", "value-1");
        Event event2 = Event.technical("An event").addAttribute("key-1", "value-1");
        assertThat(event1).isEqualTo(event2);
    }

    @Test
    @DisplayName("should check non equality")
    void shouldCheckNonEquality() {
        Event event1 = Event.technical("An event").addAttribute("key-1", "value-1");
        Event event2 = Event.technical("An event").addAttribute("key-2", "value-2");
        assertThat(event1).isNotEqualTo(event2);
    }

    @Test
    @DisplayName("should check to string")
    void shouldCheckToString() {
        Event event = Event.technical("An event").addAttribute("existingKey", "newValue");
        assertThat(event.toString()).hasToString("Event(attributes={metricName=An event, existingKey=newValue, type=TECHNICAL})");
    }
}
