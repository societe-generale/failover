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

package com.societegenerale.failover.dashboard.metrics.source.sharedstore;

import java.time.Duration;

/**
 * Two independent bounds applied to retained trend history (design §5.4): an age bound ({@link #maxAge}) and a
 * size bound ({@link #maxEntries}, oldest truncated first). Shared by the in-memory series ring and, later, the
 * JDBC snapshot store so both evict with identical semantics.
 *
 * @param maxAge     points older than this are evicted
 * @param maxEntries maximum retained points; the oldest are truncated beyond this
 * @author Anand Manissery
 */
public record RetentionPolicy(Duration maxAge, int maxEntries) {

    public RetentionPolicy {
        if (maxAge == null || maxAge.isNegative() || maxAge.isZero()) {
            throw new IllegalArgumentException("maxAge must be a positive duration, but was " + maxAge);
        }
        if (maxEntries <= 0) {
            throw new IllegalArgumentException("maxEntries must be > 0, but was " + maxEntries);
        }
    }

    /** @return {@code true} if a point captured at {@code timestampMs} is older than {@link #maxAge} relative to {@code nowMs}. */
    public boolean isExpired(long timestampMs, long nowMs) {
        return nowMs - timestampMs > maxAge.toMillis();
    }
}
