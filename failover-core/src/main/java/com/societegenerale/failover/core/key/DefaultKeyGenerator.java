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
 * Default {@link KeyGenerator} that derives a cache key from method arguments.
 *
 * <p>Key construction rules per argument:
 * <ul>
 *   <li>No args (null or empty list) → {@code "NO-ARG"}</li>
 *   <li>Primitive, {@link Number}, {@link String}, or {@link Boolean} → {@link String#valueOf(Object)}</li>
 *   <li>{@link java.util.Collection} → elements converted recursively, joined by {@code ","}</li>
 *   <li>Array → elements converted recursively, joined by {@code ","}</li>
 *   <li>Any type that overrides {@link Object#toString()} (records, enums, value objects) →
 *       its {@code toString()} value, which is deterministic and stable across JVM restarts</li>
 *   <li>Any other type (identity {@code toString()}) → {@code "ClassName@hashCode"} (hex); a
 *       warning is logged because the identity hash is unstable across JVM restarts and will cause
 *       failover lookups to miss with a persistent store. Override {@code toString}/{@code equals}/
 *       {@code hashCode} or configure a custom {@link KeyGenerator}</li>
 * </ul>
 *
 * <p>Multiple arguments are joined with {@code ":"}.
 *
 * @author Anand Manissery
 * @see FailoverKeyGenerator
 */
@Slf4j
public class DefaultKeyGenerator implements KeyGenerator {

    private static final String NO_ARG_STRING = "NO-ARG";

    private static final String EMPTY_STRING = "";

    private static final String COLLECTIONS_DELIMITER = ",";

    private static final String KEY_DELIMITER = ":";

    private static final List<Class<?>> NUMBER_TYPES = List.of(Number.class, String.class, Boolean.class);

    /**
     * Generates a cache key by converting each argument to its string representation
     * and joining all arguments with {@code ":"}.
     *
     * <p>Returns {@code "NO-ARG"} when {@code args} is {@code null} or empty.
     * See the class-level documentation for per-argument type rules.
     *
     * @param failover annotation metadata; used in warning logs for unrecognised argument types
     * @param args     resolved method arguments; may be {@code null} or empty
     * @return non-null key string
     */
    @Override
    public String key(Failover failover, List<Object> args) {
        if (isNull(args) || args.isEmpty()) {
            return NO_ARG_STRING;
        }
        return args.stream().map(e-> this.castToStringValue(e, failover)).collect(joining(KEY_DELIMITER));
    }

    private String castToStringValue(Object item, Failover failover) {
        if (isNull(item)) {
            return EMPTY_STRING;
        }
        if(isOfType(item)) {
            return valueOf(item);
        }
        if (Collection.class.isAssignableFrom(item.getClass())) {
            var collection = (Collection<?>) item;
            return collection.stream().map(e-> this.castToStringValue(e, failover)).collect(joining(COLLECTIONS_DELIMITER));
        }
        if (item.getClass().isArray()) {
            var len = Array.getLength(item);
            var list = new ArrayList<>();
            for (var i = 0; i < len; i++) {
                list.add(Array.get(item, i));
            }
            return list.stream().map(e-> this.castToStringValue(e, failover)).collect(joining(COLLECTIONS_DELIMITER));
        }
        if (overridesToString(item.getClass())) {
            // Records, enums and value types with a real toString() yield a deterministic, readable
            // key that is stable across JVM restarts — safe for a persistent store.
            return valueOf(item);
        }
        log.warn("Failover '{}': key arg of type '{}' overrides neither toString() nor a stable hashCode/equals; "
                        + "its identity hash is unstable across JVM restarts and will cause failover lookups to miss with a persistent store. "
                        + "Override toString()/equals()/hashCode() or configure a custom KeyGenerator.",
                failover.name(), item.getClass().getName());
        return "%s@%s".formatted(item.getClass().getName(), toHexString(item.hashCode()));
    }

    /**
     * Returns {@code true} when {@code clazz} (or a supertype other than {@link Object}) overrides
     * {@link Object#toString()}. Such a {@code toString()} is treated as a deterministic key source;
     * the bare {@link Object#toString()} (identity hash) is not.
     */
    private boolean overridesToString(Class<?> clazz) {
        try {
            return clazz.getMethod("toString").getDeclaringClass() != Object.class;
        } catch (NoSuchMethodException e) {
            return false; // unreachable: toString() is always present
        }
    }

    private boolean isOfType(Object item) {
        return NUMBER_TYPES.stream().anyMatch(cls-> isOfType(item, cls));
    }

    private boolean isOfType(Object item, Class<?>type) {
        return type.isAssignableFrom(item.getClass());
    }
}
