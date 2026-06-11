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

/**
 * Utility for unchecked generic casts needed to bridge raw types at framework boundaries.
 *
 * @author Anand Manissery
 */
@UtilityClass
public class CastingUtils {

    /**
     * Casts {@code payload} to {@code T} without a checked cast warning.
     * Returns {@code null} if {@code payload} is {@code null}.
     *
     * @param <T>     the target type
     * @param payload the object to cast
     * @return the cast object, or {@code null}
     */
    @SuppressWarnings("unchecked")
    public static <T> T cast(Object payload) {
        return  payload == null ? null : (T) payload;
    }
}
