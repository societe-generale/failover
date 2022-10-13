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
import com.google.common.collect.ImmutableMap;
import org.junit.jupiter.api.BeforeEach;
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

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class EventTest {

    @Mock
    private Appender<ILoggingEvent> appender;

    @Captor
    private ArgumentCaptor<ILoggingEvent> captor;

    @BeforeEach
    void setUp() {
        ch.qos.logback.classic.Logger logger = (ch.qos.logback.classic.Logger) LoggerFactory.getLogger(Logger.ROOT_LOGGER_NAME);
        logger.addAppender(appender);
        MDC.clear();
    }

    @Test
    void shouldCreateANewTechnicalEvent() {
        //when
        Event event = Event.technical("An event");

        //then
        assertThat(event.getAttributes())
                .containsEntry("type", "TECHNICAL")
                .containsEntry("metricName", "An event");
    }

    @Test
    void shouldAddAnAttribute() {
        //when
        Event event = Event.technical("An event")
                .addAttribute("new", "attribute");

        //then
        assertThat(event.getAttributes())
                .containsEntry("type", "TECHNICAL")
                .containsEntry("metricName", "An event")
                .containsEntry("new", "attribute");
    }

    @Test
    void shouldPublishEventThroughLogging() {
        //given
        Event event = Event.technical("An event")
                .addAttribute("new", "attribute");

        //when
        event.publish();

        //then
        verify(appender).doAppend(captor.capture());
        assertThat(captor.getValue().getLoggerName()).isEqualTo("TECHNICAL");
        assertThat(captor.getValue().getMDCPropertyMap())
                .containsEntry("new", "attribute")
                .containsEntry("type", "TECHNICAL");
    }

    @Test
    void shouldRestoreMdcAfterPublish() {
        //given
        MDC.put("existingKey", "existingValue");
        MDC.put("existingKey2", "existingValue2");

        Event event = Event.technical("An event")
                .addAttribute("existingKey", "newValue");

        //when
        event.publish();

        //then
        assertThat(MDC.getCopyOfContextMap()).containsAllEntriesOf(ImmutableMap.of(
                "existingKey", "existingValue",
                "existingKey2", "existingValue2"));
    }

    @Test
    void shouldThrowExceptionWhenModifyingFromOutside() {

        Event event = Event.technical("An event")
                .addAttribute("existingKey", "newValue");

        assertThat(event.getAttributes()).contains(new AbstractMap.SimpleEntry<>("existingKey", "newValue"));

        Map<String, String> result = event.getAttributes();

        UnsupportedOperationException exception = assertThrows(UnsupportedOperationException.class, () -> result.put("someKey", "someValue"));
        assertThat(exception).isInstanceOf(UnsupportedOperationException.class);
    }


    @Test
    void shouldClearMdcAfterPublishIfNoMdcPresentBeforePublish() {
        //given

        Event event = Event.technical("An event")
                .addAttribute("existingKey", "newValue");

        //when
        event.publish();

        //then
        assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
    }

    @Test
    void shouldCheckEquality() {
        Event event1 = Event.technical("An event").addAttribute("key-1", "value-1");
        Event event2 = Event.technical("An event").addAttribute("key-1", "value-1");
        assertThat(event1).isEqualTo(event2);
    }

    @Test
    void shouldCheckNonEquality() {
        Event event1 = Event.technical("An event").addAttribute("key-1", "value-1");
        Event event2 = Event.technical("An event").addAttribute("key-2", "value-2");
        assertThat(event1).isNotEqualTo(event2);
    }

    @Test
    void shouldCheckToString() {
        Event event = Event.technical("An event").addAttribute("existingKey", "newValue");
        assertThat(event.toString()).hasToString("Event(attributes={metricName=An event, existingKey=newValue, type=TECHNICAL})");
    }
}