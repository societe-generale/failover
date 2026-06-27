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

import com.fasterxml.jackson.databind.ObjectMapper;
import com.societegenerale.failover.observable.metrics.ClusterSnapshot;
import com.societegenerale.failover.observable.metrics.InstanceMetrics;
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.SnapshotStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.ArrayList;
import java.util.List;
import java.util.function.LongSupplier;

/**
 * Durable {@link SnapshotStore} backed by a single JDBC table — the {@code store=jdbc} option for the shared-store
 * tier, so cluster aggregation survives a dashboard restart. Same semantics as the in-memory store (latest snapshot
 * per instance, liveness window, max-instances warning) but persisted; the {@link MetricsSummary} is stored as JSON.
 *
 * <p>Table {@code (INSTANCE_ID PK, RECEIVED_AT BIGINT, SUMMARY_JSON CLOB)}, named {@code <tablePrefix> +}
 * {@link #BASE_TABLE} (the prefix is validated — letters/digits/underscore only — since it is concatenated into
 * SQL). Upsert is a portable update-then-insert (no dialect-specific MERGE); {@code autoDdl} creates the table on
 * startup if missing. Stores only aggregate, non-sensitive failover metrics — never business data — so a single
 * shared table is sufficient (no multi-tenancy; use {@code tablePrefix} to namespace).
 *
 * @author Anand Manissery
 */
@Slf4j
public class SnapshotStoreJdbc implements SnapshotStore {

    /** Base (unprefixed) table name; the configured {@code table-prefix} is prepended. */
    public static final String BASE_TABLE = "FAILOVER_DASHBOARD_SNAPSHOT";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final long livenessMillis;
    private final int maxInstances;
    private final String table;
    private final LongSupplier nowMillis;

    public SnapshotStoreJdbc(JdbcTemplate jdbc, ObjectMapper mapper, long livenessMillis, int maxInstances,
                             String tablePrefix, boolean autoDdl) {
        this(jdbc, mapper, livenessMillis, maxInstances, tablePrefix, autoDdl, System::currentTimeMillis);
    }

    /** Test seam: inject a clock. */
    SnapshotStoreJdbc(JdbcTemplate jdbc, ObjectMapper mapper, long livenessMillis, int maxInstances,
                      String tablePrefix, boolean autoDdl, LongSupplier nowMillis) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.livenessMillis = livenessMillis;
        this.maxInstances = maxInstances;
        this.table = validatePrefix(tablePrefix) + BASE_TABLE;
        this.nowMillis = nowMillis;
        if (autoDdl) {
            createTableIfMissing();
        }
        log.info("Failover shared-store using durable JDBC snapshot store (table='{}').", this.table);
    }

    @Override
    public void upsert(ClusterSnapshot snapshot) {
        String id = snapshot.instanceId();
        String json = toJson(snapshot.summary());
        long now = nowMillis.getAsLong();
        int updated = jdbc.update("UPDATE " + table + " SET RECEIVED_AT = ?, SUMMARY_JSON = ? WHERE INSTANCE_ID = ?",
                now, json, id);
        if (updated == 0) {
            warnIfOverCeiling();
            jdbc.update("INSERT INTO " + table + " (INSTANCE_ID, RECEIVED_AT, SUMMARY_JSON) VALUES (?, ?, ?)",
                    id, now, json);
        }
    }

    @Override
    public List<MetricsSummary> live() {
        long cutoff = nowMillis.getAsLong() - livenessMillis;
        List<String> rows = jdbc.queryForList(
                "SELECT SUMMARY_JSON FROM " + table + " WHERE RECEIVED_AT >= ?", String.class, cutoff);
        List<MetricsSummary> out = new ArrayList<>(rows.size());
        for (String json : rows) {
            MetricsSummary summary = fromJson(json);
            if (summary != null) {
                out.add(summary);
            }
        }
        return out;
    }

    @Override
    public List<InstanceMetrics> liveInstances() {
        long cutoff = nowMillis.getAsLong() - livenessMillis;
        return jdbc.query(
                "SELECT INSTANCE_ID, RECEIVED_AT, SUMMARY_JSON FROM " + table + " WHERE RECEIVED_AT >= ?",
                (rs, rowNum) -> {
                    MetricsSummary summary = fromJson(rs.getString("SUMMARY_JSON"));
                    return summary == null ? null
                            : new InstanceMetrics(rs.getString("INSTANCE_ID"), rs.getLong("RECEIVED_AT"), summary);
                },
                cutoff).stream().filter(java.util.Objects::nonNull).toList();
    }

    @Override
    public int liveCount() {
        long cutoff = nowMillis.getAsLong() - livenessMillis;
        Integer count = jdbc.queryForObject(
                "SELECT COUNT(*) FROM " + table + " WHERE RECEIVED_AT >= ?", Integer.class, cutoff);
        return count == null ? 0 : count;
    }

    @Override
    public long newestEpochMs() {
        long cutoff = nowMillis.getAsLong() - livenessMillis;
        Long newest = jdbc.queryForObject(
                "SELECT MAX(RECEIVED_AT) FROM " + table + " WHERE RECEIVED_AT >= ?", Long.class, cutoff);
        return newest == null ? 0L : newest;
    }

    private void warnIfOverCeiling() {
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        if (total != null && total >= maxInstances) {
            log.warn("Failover shared-store (JDBC) has {} instances (max-instances={}); consider cluster.mode=prometheus "
                    + "for clusters this large.", total, maxInstances);
        }
    }

    private void createTableIfMissing() {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + table
                + " (INSTANCE_ID VARCHAR(255) PRIMARY KEY, RECEIVED_AT BIGINT NOT NULL, SUMMARY_JSON CLOB NOT NULL)");
    }

    private String toJson(MetricsSummary summary) {
        try {
            return mapper.writeValueAsString(summary);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize snapshot summary", e);
        }
    }

    private MetricsSummary fromJson(String json) {
        try {
            return mapper.readValue(json, MetricsSummary.class);
        } catch (Exception e) {
            log.warn("Skipping unreadable snapshot row: {}", e.toString());
            return null;
        }
    }

    /**
     * Validates the table prefix — it is concatenated into SQL, so it must be a safe SQL identifier fragment:
     * empty, or letters/digits/underscore only (no whitespace, quotes, or punctuation). Prevents SQL injection
     * via the prefix. A {@code null} prefix is treated as empty.
     *
     * @param prefix the configured {@code table-prefix} ({@code ""} ⇒ the base table name is used as-is)
     * @return the validated prefix
     */
    private static String validatePrefix(String prefix) {
        String p = prefix == null ? "" : prefix;
        if (!p.matches("[A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "Illegal snapshot table-prefix '" + prefix + "' — only letters, digits and underscore are allowed.");
        }
        return p;
    }
}
