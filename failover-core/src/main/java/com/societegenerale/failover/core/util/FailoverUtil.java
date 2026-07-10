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
public final class FailoverUtil {

    /**
     * Returns {@code failover.domain()} when non-blank, otherwise {@code failover.name()}.
     * {@code domain()} is an annotation attribute defaulting to {@code ""}, so it is never {@code null}.
     *
     * @param failover the annotation instance
     * @return effective name used as {@code FAILOVER_NAME} in the store and as UUID key prefix
     */
    public static String effectiveName(Failover failover) {
        return failover.domain().isBlank() ? failover.name() : failover.domain();
    }

    /**
     * Returns a single-line summary of the failover configuration, suitable for logging.
     *
     * @param f the annotation instance
     * @return a string summarizing the failover configuration
     */
    public static String summary(Failover f) {
        var sb = new StringBuilder(f.name()).append(" : ");
        if (!f.expiryDurationExpression().isBlank()) {
            sb.append("expiry=").append(f.expiryDurationExpression()).append(" ")
                    .append(f.expiryUnitExpression().isBlank() ? f.expiryUnit().name() : f.expiryUnitExpression());
        } else {
            sb.append("expiry=").append(f.expiryDuration()).append(" ")
                    .append(f.expiryUnitExpression().isBlank() ? f.expiryUnit().name() : f.expiryUnitExpression());
        }
        if (!f.domain().isBlank())          sb.append(", domain='").append(f.domain()).append("'");
        if (!f.keyGenerator().isBlank())    sb.append(", keyGenerator='").append(f.keyGenerator()).append("'");
        if (!f.expiryPolicy().isBlank())    sb.append(", expiryPolicy='").append(f.expiryPolicy()).append("'");
        if (!f.payloadSplitter().isBlank()) sb.append(", splitter='").append(f.payloadSplitter()).append("'");
        if (f.recoverAll())                 sb.append(", recoverAll=true");
        return sb.toString();
    }
}
