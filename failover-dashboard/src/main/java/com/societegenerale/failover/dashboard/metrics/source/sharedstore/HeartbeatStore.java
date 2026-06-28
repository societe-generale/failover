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

/**
 * Records the most recent heartbeat time per instance. Always present in shared-store mode —
 * no explicit opt-in required. Instances that never send a heartbeat remain at {@code UNKNOWN}
 * status; once a heartbeat arrives the instance is classified {@code LIVE} or {@code DOWN}
 * based on heartbeat age.
 *
 * @author Anand Manissery
 */
public interface HeartbeatStore {

    /** Records a heartbeat for the given instance, stamping the current receive time. */
    void record(String instanceId);

    /**
     * Returns the epoch-millis of the last recorded heartbeat for this instance,
     * or {@code null} if no heartbeat has ever been received.
     * <p>A {@code null} return means heartbeat tracking is not active for this instance
     * (the peer has not enabled it) — the instance keeps {@code LiveStatus.UNKNOWN}.
     */
    Long lastSeen(String instanceId);
}
