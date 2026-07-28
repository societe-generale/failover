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

package com.societegenerale.failover.configuration;

import com.societegenerale.failover.observable.metrics.SnapshotTablePrefix;
import com.societegenerale.failover.observable.micrometer.HeartbeatPushClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;

/**
 * {@link HeartbeatPushClient} that writes straight into the dashboard's {@code FAILOVER_DASHBOARD_HEARTBEAT}
 * table instead of POSTing to the ingest endpoint — the JDBC-direct counterpart to
 * {@link JdbcSnapshotPushClient}, active alongside it when both
 * {@code failover.dashboard.cluster.snapshot.jdbc.enabled=true} and
 * {@code failover.dashboard.cluster.snapshot.heartbeat.enabled=true} are set. Same table, same upsert
 * logic as the dashboard's own {@code HeartbeatStoreJdbc} — the two writers are interchangeable from the
 * table's point of view.
 *
 * <p><strong>Fails soft</strong>, matching {@code HeartbeatStoreJdbc}: heartbeat is a supplementary
 * liveness signal, not the metrics path, so a {@link DataAccessException} (e.g. the table doesn't exist
 * yet) is caught by the caller ({@code HeartbeatPublisher}) and logged once rather than propagated.
 *
 * @author Anand Manissery
 */
@Slf4j
public class JdbcHeartbeatPushClient implements HeartbeatPushClient {

    /** Base (unprefixed) table name; the configured {@code table-prefix} is prepended. */
    public static final String BASE_TABLE = "FAILOVER_DASHBOARD_HEARTBEAT";

    private final JdbcTemplate jdbc;
    private final String table;

    /**
     * Creates a new client.
     *
     * @param jdbc        the JDBC template to run against — a {@link javax.sql.DataSource} pointed at the
     *                    dashboard's database
     * @param tablePrefix prefix prepended to the base table name; must match the dashboard's configured prefix
     */
    public JdbcHeartbeatPushClient(JdbcTemplate jdbc, String tablePrefix) {
        this.jdbc = jdbc;
        this.table = SnapshotTablePrefix.validate(tablePrefix) + BASE_TABLE;
        log.info("Failover heartbeat publisher writing directly to JDBC table '{}' (no ingest endpoint).", this.table);
    }

    @Override
    public void send(String instanceId) {
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        int updated = jdbc.update("UPDATE " + table + " SET LAST_SEEN = ? WHERE INSTANCE_ID = ?", now, instanceId);
        if (updated == 0) {
            try {
                jdbc.update("INSERT INTO " + table + " (INSTANCE_ID, LAST_SEEN) VALUES (?, ?)", instanceId, now);
            } catch (DuplicateKeyException e) {
                // Lost the race to a concurrent first heartbeat for the same instance — bring it up to this one.
                jdbc.update("UPDATE " + table + " SET LAST_SEEN = ? WHERE INSTANCE_ID = ?", now, instanceId);
            }
        }
    }
}
