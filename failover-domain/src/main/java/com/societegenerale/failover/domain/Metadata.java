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

package com.societegenerale.failover.domain;

import lombok.Data;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Key-value bag for additional metadata attached to recovered failover payloads.
 * Entries are added via {@link #withInfo(String, String)} and preserved in insertion order.
 *
 * @author Anand Manissery
 */
@Data
public class Metadata {

    private final Map<String,String> info = new LinkedHashMap<>();

    /**
     * Adds a metadata entry and returns {@code this} for chaining.
     *
     * @param key   the metadata key
     * @param value the metadata value
     * @return this instance
     */
    public Metadata withInfo(String key, String value) {
        info.put(key,value);
        return this;
    }
}
