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

package com.societegenerale.failover.core.observable;

import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Mutable key-value bag for failover metrics, keyed with a {@code "failover-"} prefix.
 * Use {@link #of(String)} to create an instance and {@link #collect(String, String)} to add entries.
 *
 * @author Anand Manissery
 */
@Data
public class Metrics {

    private static final String KEY_PREFIX = "failover";

    private String name;

    private Map<String, String> info;

    private String keyPrefix;

    private Metrics(String name) {
        this.name = name;
        this.keyPrefix = KEY_PREFIX;
        this.info = new LinkedHashMap<>();
    }

    /**
     * Creates a new {@link Metrics} instance pre-populated with a {@code name} entry.
     *
     * @param name the metric name
     * @return new metrics instance
     */
    public static Metrics of(String name) {
        return new Metrics(name).collect("name", name);
    }

    /**
     * Adds a key-value pair to this metrics bag, prefixing the key with {@code "failover-"}.
     *
     * @param key   the metric key (will be stored as {@code "failover-" + key})
     * @param value the metric value
     * @return this instance for chaining
     */
    public Metrics collect(String key, String value) {
        info.put( "%s-%s".formatted(keyPrefix, key), value);
        return this;
    }

    /**
     * Returns an unmodifiable view of all collected metric entries.
     *
     * @return unmodifiable map of prefixed key-value pairs
     */
    public Map<String, String> getInfo() {
        return Collections.unmodifiableMap(info);
    }
}
