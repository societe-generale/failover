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

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.LongSupplier;

/**
 * In-memory {@link HeartbeatStore}: last-seen epoch per instance in a {@link ConcurrentHashMap}.
 * Zero infra; process-local and lost on restart — consistent with {@link SnapshotStoreInmemory}.
 *
 * @author Anand Manissery
 */
public class HeartbeatStoreInmemory implements HeartbeatStore {

    private final Map<String, Long> lastSeen = new ConcurrentHashMap<>();
    private final LongSupplier nowMillis;

    public HeartbeatStoreInmemory() {
        this(System::currentTimeMillis);
    }

    /** Test seam: inject a clock. */
    HeartbeatStoreInmemory(LongSupplier nowMillis) {
        this.nowMillis = nowMillis;
    }

    @Override
    public void record(String instanceId) {
        lastSeen.put(instanceId, nowMillis.getAsLong());
    }

    @Override
    public Long lastSeen(String instanceId) {
        return lastSeen.get(instanceId);
    }
}
