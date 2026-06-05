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

import lombok.EqualsAndHashCode;
import lombok.ToString;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;


/**
 * Structured technical event that writes its attributes to MDC before emitting a single INFO log line.
 * Used by {@link MetricsReportPublisher} to emit failover metrics as structured log events.
 *
 * @author Anand Manissery
 */
@EqualsAndHashCode(onlyExplicitlyIncluded = true)
@ToString(onlyExplicitlyIncluded = true)
public final class Event {

    private static final String TECHNICAL_TYPE = "TECHNICAL";

    private static final String NAME_ATTRIBUTE = "metricName";

    private static final String TYPE_ATTRIBUTE = "type";

    @EqualsAndHashCode.Include
    @ToString.Include
    private final Map<String, String> attributes = new HashMap<>();

    private final Logger logger;

    private Event(String name) {
        attributes.put(NAME_ATTRIBUTE, name);
        attributes.put(TYPE_ATTRIBUTE, TECHNICAL_TYPE);
        this.logger = LoggerFactory.getLogger(TECHNICAL_TYPE);
    }

    /**
     * Creates a technical event with the given metric name.
     *
     * @param name the metric name (stored as the {@code metricName} attribute)
     * @return new event instance
     */
    public static Event technical(String name) {
        return new Event(name);
    }

    /**
     * Adds an attribute to this event if the key is not already present.
     *
     * @param name  the attribute key
     * @param value the attribute value
     * @return this event for chaining
     */
    public synchronized Event addAttribute(String name, String value) {
        attributes.putIfAbsent(name, value);
        return this;
    }

    /**
     * Returns an unmodifiable view of all attributes collected on this event.
     *
     * @return unmodifiable map of attribute key-value pairs
     */
    public Map<String, String> getAttributes(){
        return Collections.unmodifiableMap(attributes);
    }

    /**
     * Publishes this event by setting all attributes into the MDC and emitting a single INFO log line,
     * then restoring the previous MDC state.
     */
    public synchronized void publish() {
        final Map<String, String> copyOfMDC = MDC.getCopyOfContextMap();
        attributes.forEach(MDC::put);
        try {
            logger.info("");
        } finally {
            if (copyOfMDC != null) {
                MDC.setContextMap(copyOfMDC);
            } else {
                MDC.clear();
            }
        }
    }
}