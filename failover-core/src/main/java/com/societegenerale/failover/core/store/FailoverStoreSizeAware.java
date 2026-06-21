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

package com.societegenerale.failover.core.store;

/**
 * Optional capability a {@link FailoverStore} may expose to report its current live entry count per
 * referential name, backing the {@code failover.live.entries} gauge (cache footprint).
 *
 * <p>It is deliberately <strong>not</strong> part of {@link FailoverStore}: a cheap live count is only
 * meaningful for in-process stores (in-memory, Caffeine). Stores where counting is expensive or
 * ambiguous (a JDBC {@code COUNT(*)} per scrape, or per-tenant routing) simply do not implement it, and
 * the gauge is then not registered. Decorators ({@code DefaultFailoverStore}, {@code FailoverStoreAsync})
 * forward to their delegate, so the capability survives the store-assembly chain.
 *
 * @author Anand Manissery
 */
public interface FailoverStoreSizeAware {

    /**
     * Number of entries currently held for the given referential name.
     *
     * @param name the referential (store) name — the effective name used as the store key prefix
     * @return the live entry count for that name (0 when none)
     */
    long liveEntryCount(String name);

    /**
     * Whether {@link #liveEntryCount} returns a meaningful value. Base stores return {@code true};
     * decorators return {@code true} only when their delegate is size-aware and supported, so the gauge
     * is registered only when the assembled chain can actually answer.
     *
     * @return {@code true} when live counting is supported end-to-end
     */
    default boolean liveEntryCountSupported() {
        return true;
    }
}
