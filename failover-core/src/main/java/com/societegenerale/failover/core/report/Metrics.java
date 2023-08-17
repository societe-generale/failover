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

import lombok.Data;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

import static java.lang.String.format;

/**
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

    public static Metrics of(String name) {
        return new Metrics(name).collect("name", name);
    }

    public Metrics collect(String key, String value) {
        info.put( format("%s-%s",keyPrefix, key), value);
        return this;
    }

    public Map<String, String> getInfo() {
        return Collections.unmodifiableMap(info);
    }
}
