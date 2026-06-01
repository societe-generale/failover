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

package com.societegenerale.failover.core.key;

import com.societegenerale.failover.annotations.Failover;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Array;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

import static java.lang.Integer.toHexString;
import static java.lang.String.valueOf;
import static java.util.Objects.isNull;
import static java.util.stream.Collectors.joining;

/**
 * @author Anand Manissery
 */
@Slf4j
public class DefaultKeyGenerator implements KeyGenerator {

    private static final String NO_ARG_STRING = "NO-ARG";

    private static final String EMPTY_STRING = "";

    private static final List<Class<?>> NUMBER_TYPES = List.of(Number.class, String.class, Boolean.class);

    @Override
    public String key(Failover failover, List<Object> args) {
        if (isNull(args) || args.isEmpty()) {
            return NO_ARG_STRING;
        }
        return args.stream().map(e-> this.castToStringValue(e, failover)).collect(joining(":"));
    }

    private String castToStringValue(Object item, Failover failover) {
        if (isNull(item)) {
            return EMPTY_STRING;
        }
        if(item.getClass().isPrimitive() || isOfType(item)) {
            return valueOf(item);
        }
        if (Collection.class.isAssignableFrom(item.getClass())) {
            var collection = (Collection<?>) item;
            return collection.stream().map(e-> this.castToStringValue(e, failover)).collect(joining(","));
        }
        if (item.getClass().isArray()) {
            var len = Array.getLength(item);
            var list = new ArrayList<>();
            for (var i = 0; i < len; i++) {
                list.add(Array.get(item, i));
            }
            return list.stream().map(e-> this.castToStringValue(e, failover)).collect(joining(","));
        }
        log.warn("Failover '{}': key arg '{}' is a non-primitive/non-String type. Implement equals/hashCode or configure a custom KeyGenerator.",
                failover.name(), item.getClass().getName());
        return "%s@%s".formatted(item.getClass().getName(), toHexString(item.hashCode()));
    }

    private boolean isOfType(Object item) {
        return NUMBER_TYPES.stream().anyMatch(cls-> isOfType(item, cls));
    }

    private boolean isOfType(Object item, Class<?>type) {
        return type.isAssignableFrom(item.getClass());
    }
}
