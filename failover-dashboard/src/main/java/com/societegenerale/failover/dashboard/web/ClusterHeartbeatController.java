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

package com.societegenerale.failover.dashboard.web;

import com.societegenerale.failover.dashboard.metrics.source.sharedstore.HeartbeatStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Lightweight heartbeat ingest endpoint for shared-store liveness tracking. Peers POST only their
 * instance id (no metrics payload) at a high frequency; the dashboard records the receive time and uses
 * it to classify instances as {@code LIVE} or {@code DOWN}.
 *
 * <p>Present only when {@code failover.dashboard.cluster.shared-store.liveness.enabled=true}. Mapped under
 * the same {@code base-path/api/cluster} namespace as the snapshot ingest, covered by the same auth gate.
 *
 * @author Anand Manissery
 */
@RestController
@RequestMapping("${failover.dashboard.base-path:/failover-dashboard}/api/cluster")
public class ClusterHeartbeatController {

    private final HeartbeatStore heartbeatStore;

    public ClusterHeartbeatController(HeartbeatStore heartbeatStore) {
        this.heartbeatStore = heartbeatStore;
    }

    /** Records a heartbeat. Returns {@code 202 Accepted}; liveness classification happens lazily on read. */
    @PostMapping("/heartbeat")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void heartbeat(@RequestBody HeartbeatPayload payload) {
        heartbeatStore.record(payload.instanceId());
    }
}
