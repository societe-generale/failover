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

package com.societegenerale.failover.core.util;

import lombok.experimental.UtilityClass;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;

/**
 * Small null-and-emptiness helpers shared across the failover core.
 *
 * <p>"Empty" is defined per type, which matters for scatter/gather where a recovered slice may be a
 * collection, a map, or an array:
 *
 * <ul>
 *   <li>{@code null} → empty.</li>
 *   <li>{@link Collection} → empty when it has no elements <em>or</em> every element is {@code null}
 *       (an all-{@code null} collection carries no usable data, so it is treated as empty).</li>
 *   <li>{@link Map} → empty when it has no entries.</li>
 *   <li>{@code Object[]} → empty only when its length is {@code 0}. Unlike collections, an array is
 *       <em>not</em> inspected element-by-element, so an array of all {@code null}s is <b>not</b>
 *       considered empty.</li>
 *   <li>Any other object (e.g. a {@link String}, a number, a POJO) → never empty, even the empty
 *       string {@code ""}. Only {@code null} makes a non-container value "empty".</li>
 * </ul>
 *
 * @author Anand Manissery
 */
@UtilityClass
public class CommonsUtil {

    /**
     * Inverse of {@link #isNullOrEmpty(Object)} — {@code true} when the value carries usable data.
     *
     * @param object the value to test (any type, may be {@code null})
     * @return {@code true} if {@code object} is non-null and not empty by the rules of
     *         {@link #isNullOrEmpty(Object)}
     */
    public static boolean isNotNullOrEmpty(Object object) {
        return !isNullOrEmpty(object);
    }

    /**
     * Returns whether the given value is {@code null} or "empty", where emptiness is type-specific
     * (see the {@link CommonsUtil class javadoc} for the exact rules).
     *
     * @param obj the value to test (any type, may be {@code null})
     * @return {@code true} if {@code obj} is {@code null}; an empty or all-{@code null} {@link Collection};
     *         an empty {@link Map}; or a zero-length {@code Object[]}. {@code false} otherwise.
     */
    public static boolean isNullOrEmpty(Object obj) {
        return switch (obj) {
            case null -> true;
            case Collection<?> c -> c.isEmpty() || c.stream().filter(Objects::nonNull).toList().isEmpty();
            case Map<?, ?> m -> m.isEmpty();
            case Object[] a -> a.length == 0;
            default -> false;
        };
    }
}
