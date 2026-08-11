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

import com.societegenerale.failover.observable.metrics.ClusterSnapshot;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.SnapshotStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Ingest endpoint for {@code cluster.mode=shared-store}: peers POST their {@link ClusterSnapshot} here and it is
 * recorded in the {@link SnapshotStore} for cluster-wide aggregation. Mapped under the same {@code base-path/api}
 * namespace as the read API, so it is covered by the dashboard's access-control gate. Present only in shared-store
 * mode (its bean is conditional).
 *
 * @author Anand Manissery
 */
@Slf4j
@RestController
@RequestMapping("${failover.dashboard.base-path:/failover-dashboard}/api/cluster")
public class ClusterSnapshotController implements PeerIngestEndpoint {

    private final SnapshotStore snapshotStore;

    /**
     * Creates a new controller.
     *
     * @param snapshotStore the store to record incoming peer snapshots into
     */
    public ClusterSnapshotController(SnapshotStore snapshotStore) {
        this.snapshotStore = snapshotStore;
    }

    /**
     * Records a pushed snapshot. Returns {@code 202 Accepted}; aggregation happens lazily on read.
     *
     * <p>The instance id is validated before the snapshot reaches the store — it becomes the store key
     * and is rendered in the Instances table, so an absent, oversized or markup-bearing id is rejected
     * rather than recorded (see {@link InstanceId}). A missing {@code summary} is rejected for the same
     * reason: it is dereferenced on every aggregation, so accepting it would trade a {@code 400} on the
     * pushing peer for a {@code 500} on every subsequent dashboard read. {@code configEntries} needs no
     * check — {@link ClusterSnapshot}'s compact constructor already normalises {@code null} to empty.
     *
     * @param snapshot the pushed peer snapshot
     * @throws org.springframework.web.server.ResponseStatusException {@code 400} if the body carries an
     *         unusable instance id or no summary
     */
    @PostMapping("/snapshot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void ingest(@RequestBody ClusterSnapshot snapshot) {
        String instanceId = InstanceId.validated(snapshot.instanceId());
        if (snapshot.summary() == null) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "summary is required.");
        }
        log.debug("Received cluster snapshot from instance '{}' ({} config entries).",
                instanceId, snapshot.configEntries().size());
        snapshotStore.upsert(snapshot);
        log.debug("Cluster snapshot from instance '{}' recorded.", instanceId);
    }
}
