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

import com.societegenerale.failover.dashboard.metrics.source.sharedstore.ClusterSnapshot;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.SnapshotStore;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

/**
 * Ingest endpoint for {@code cluster.mode=shared-store}: peers POST their {@link ClusterSnapshot} here and it is
 * recorded in the {@link SnapshotStore} for cluster-wide aggregation. Mapped under the same {@code base-path/api}
 * namespace as the read API, so it is covered by the dashboard's access-control gate. Present only in shared-store
 * mode (its bean is conditional).
 *
 * @author Anand Manissery
 */
@RestController
@RequestMapping("${failover.dashboard.base-path:/failover-dashboard}/api/cluster")
public class ClusterSnapshotController {

    private final SnapshotStore snapshotStore;

    public ClusterSnapshotController(SnapshotStore snapshotStore) {
        this.snapshotStore = snapshotStore;
    }

    /** Records a pushed snapshot. Returns {@code 202 Accepted}; aggregation happens lazily on read. */
    @PostMapping("/snapshot")
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void ingest(@RequestBody ClusterSnapshot snapshot) {
        snapshotStore.upsert(snapshot);
    }
}
