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

import com.societegenerale.failover.annotations.Failover;
import lombok.experimental.UtilityClass;

/**
 * Resolves the effective store namespace for a {@link Failover} annotation.
 *
 * <p>When {@link Failover#domain()} is non-blank it is used as the store namespace,
 * allowing multiple failovers covering the same business entity to share store entries.
 * When empty, {@link Failover#name()} is used — preserving existing behaviour.
 *
 * @author Anand Manissery
 */
@UtilityClass
public final class FailoverNameResolver {

    /**
     * Returns {@code failover.domain()} when non-blank, otherwise {@code failover.name()}.
     *
     * @param failover the annotation instance
     * @return effective name used as {@code FAILOVER_NAME} in the store and as UUID key prefix
     */
    public static String effectiveName(Failover failover) {
        return failover.domain().isBlank() ? failover.name() : failover.domain();
    }
}
