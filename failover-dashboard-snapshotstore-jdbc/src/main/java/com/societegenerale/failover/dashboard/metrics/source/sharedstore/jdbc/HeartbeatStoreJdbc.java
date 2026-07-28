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

package com.societegenerale.failover.dashboard.metrics.source.sharedstore.jdbc;

import com.societegenerale.failover.dashboard.metrics.source.sharedstore.HeartbeatStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Durable {@link HeartbeatStore} backed by a single JDBC table — the {@code store=jdbc} counterpart to
 * {@link SnapshotStoreJdbc}, so per-instance liveness survives a dashboard restart and, more importantly,
 * stays consistent when the dashboard is embedded in <em>multiple</em> {@code @Failover} instances pointed at
 * the same database: {@link com.societegenerale.failover.dashboard.metrics.source.sharedstore.HeartbeatStoreInmemory}
 * is process-local, so each embedded dashboard would only ever see its own loopback heartbeat and show every
 * peer as {@code UNKNOWN}. A shared table fixes that.
 *
 * <p>Table {@code (INSTANCE_ID PK, LAST_SEEN TIMESTAMP WITH TIME ZONE)}, named {@code <tablePrefix> + }
 * {@link #BASE_TABLE} (the prefix is validated — letters/digits/underscore only — since it is concatenated into
 * SQL; use the same prefix as {@link SnapshotStoreJdbc} to namespace both tables together). {@code LAST_SEEN} is
 * read/written as {@link OffsetDateTime} (always {@link ZoneOffset#UTC}) via {@code setObject}/{@code getObject},
 * converted to/from epoch-millis at this boundary so {@link HeartbeatStore#lastSeen} stays epoch-millis. The
 * table is never created or altered by this store — schema management is the consuming service's
 * responsibility; see the module docs for the DDL to run per dialect.
 *
 * <p><strong>Fails soft.</strong> Unlike {@link SnapshotStoreJdbc} (the metrics path, which must fail loud —
 * a broken store there is a broken dashboard), heartbeat is a supplementary liveness signal: a
 * {@link DataAccessException} (e.g. the table doesn't exist because {@code liveness.enabled} was turned on
 * without provisioning it first) is caught, logged once at {@code WARN} until the next successful call, and
 * degrades to the same behaviour as no heartbeat ever received ({@link #lastSeen} returns {@code null} →
 * caller keeps {@code LiveStatus.UNKNOWN}; {@link #record} silently drops the ping) — a misconfigured
 * heartbeat table never breaks the dashboard or the ingest endpoint.
 *
 * @author Anand Manissery
 */
@Slf4j
public class HeartbeatStoreJdbc implements HeartbeatStore {

    /** Base (unprefixed) table name; the configured {@code table-prefix} is prepended. */
    public static final String BASE_TABLE = "FAILOVER_DASHBOARD_HEARTBEAT";

    private final JdbcTemplate jdbc;
    private final String table;
    private final AtomicBoolean failing = new AtomicBoolean(false);

    /**
     * Creates a new store.
     *
     * @param jdbc        the JDBC template to run against
     * @param tablePrefix prefix prepended to the base table name; letters/digits/underscore only
     */
    public HeartbeatStoreJdbc(JdbcTemplate jdbc, String tablePrefix) {
        this.jdbc = jdbc;
        this.table = TablePrefix.validate(tablePrefix) + BASE_TABLE;
        log.info("Failover shared-store using durable JDBC heartbeat store (table='{}').", this.table);
    }

    @Override
    public void record(String instanceId) {
        try {
            OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
            int updated = jdbc.update("UPDATE " + table + " SET LAST_SEEN = ? WHERE INSTANCE_ID = ?", now, instanceId);
            if (updated == 0) {
                try {
                    jdbc.update("INSERT INTO " + table + " (INSTANCE_ID, LAST_SEEN) VALUES (?, ?)", instanceId, now);
                } catch (DuplicateKeyException e) {
                    // Lost the race to a concurrent first heartbeat for the same instance — that insert already
                    // recorded a timestamp; bring it up to this one.
                    jdbc.update("UPDATE " + table + " SET LAST_SEEN = ? WHERE INSTANCE_ID = ?", now, instanceId);
                }
            }
            onSuccess();
        } catch (DataAccessException e) {
            onFailure(e);
        }
    }

    @Override
    public Long lastSeen(String instanceId) {
        try {
            List<Long> rows = jdbc.query("SELECT LAST_SEEN FROM " + table + " WHERE INSTANCE_ID = ?",
                    (rs, rowNum) -> rs.getObject("LAST_SEEN", OffsetDateTime.class).toInstant().toEpochMilli(), instanceId);
            onSuccess();
            return rows.isEmpty() ? null : rows.getFirst();
        } catch (DataAccessException e) {
            onFailure(e);
            return null;   // same as "no heartbeat ever received" — caller keeps LiveStatus.UNKNOWN
        }
    }

    private void onSuccess() {
        if (failing.getAndSet(false)) {
            log.info("Failover shared-store heartbeat table '{}' reachable again.", table);
        }
    }

    private void onFailure(DataAccessException e) {
        if (!failing.getAndSet(true)) {
            log.warn("Failover shared-store heartbeat table '{}' is not reachable ({}) — instance liveness will "
                    + "stay UNKNOWN until this is fixed. If you don't need heartbeat tracking, set "
                    + "failover.dashboard.cluster.shared-store.liveness.enabled=false to disable it; otherwise "
                    + "create the table (see the Dashboard module docs, Scenario D, for the DDL).",
                    table, e.getMostSpecificCause().toString());
            log.debug("Heartbeat store failure detail:", e);
        }
    }
}
