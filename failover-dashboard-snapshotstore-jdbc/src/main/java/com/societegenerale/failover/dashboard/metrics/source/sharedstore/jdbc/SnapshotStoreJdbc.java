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
import com.societegenerale.failover.observable.metrics.LiveStatus;
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.SnapshotBaseline;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.SnapshotStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.util.List;
import java.util.Objects;

/**
 * Durable {@link SnapshotStore} backed by a single JDBC table — the {@code store=jdbc} option for the shared-store
 * tier, so cluster aggregation survives a dashboard restart. All snapshots are retained regardless of age; the dashboard
 * always shows each instance's last-known values, with staleness visible through the per-instance {@code lastSeenEpochMs}
 * timestamp in the Instances tab. The {@link MetricsSummary} is stored as JSON. Applies the {@link SnapshotBaseline}
 * reset-aware carry-forward (persisted in the nullable {@code BASELINE_JSON} column), so a peer restart never
 * shrinks the cluster aggregate — and the carried baseline itself survives a dashboard restart.
 *
 * <p>Table {@code (INSTANCE_ID PK, RECEIVED_AT BIGINT, SUMMARY_JSON CLOB, BASELINE_JSON CLOB NULL)}, named {@code <tablePrefix> +}
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
    private final int maxInstances;
    private final String table;

    public SnapshotStoreJdbc(JdbcTemplate jdbc, ObjectMapper mapper, int maxInstances,
                             String tablePrefix, boolean autoDdl) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.maxInstances = maxInstances;
        this.table = validatePrefix(tablePrefix) + BASE_TABLE;
        if (autoDdl) {
            createTableIfMissing();
        }
        log.info("Failover shared-store using durable JDBC snapshot store (table='{}').", this.table);
    }

    @Override
    public void upsert(ClusterSnapshot snapshot) {
        String id = snapshot.instanceId();
        long now = System.currentTimeMillis();
        List<MetricsSummary[]> existing = jdbc.query(
                "SELECT SUMMARY_JSON, BASELINE_JSON FROM " + table + " WHERE INSTANCE_ID = ?",
                (rs, rowNum) -> new MetricsSummary[]{fromJson(rs.getString("SUMMARY_JSON")),
                        fromJsonNullable(rs.getString("BASELINE_JSON"))},
                id);
        if (existing.isEmpty()) {
            warnIfOverCeiling();
            jdbc.update("INSERT INTO " + table + " (INSTANCE_ID, RECEIVED_AT, SUMMARY_JSON, BASELINE_JSON) VALUES (?, ?, ?, ?)",
                    id, now, toJson(snapshot.summary()), null);
            return;
        }
        MetricsSummary baseline = SnapshotBaseline.next(existing.getFirst()[0], existing.getFirst()[1], snapshot.summary());
        jdbc.update("UPDATE " + table + " SET RECEIVED_AT = ?, SUMMARY_JSON = ?, BASELINE_JSON = ? WHERE INSTANCE_ID = ?",
                now, toJson(snapshot.summary()), baseline == null ? null : toJson(baseline), id);
    }

    @Override
    public List<InstanceMetrics> allInstances() {
        return jdbc.query(
                "SELECT INSTANCE_ID, RECEIVED_AT, SUMMARY_JSON, BASELINE_JSON FROM " + table,
                (rs, rowNum) -> {
                    MetricsSummary summary = fromJson(rs.getString("SUMMARY_JSON"));
                    if (summary == null) {
                        return null;
                    }
                    MetricsSummary combined = SnapshotBaseline.combined(fromJsonNullable(rs.getString("BASELINE_JSON")), summary);
                    return new InstanceMetrics(rs.getString("INSTANCE_ID"), rs.getLong("RECEIVED_AT"), combined, LiveStatus.UNKNOWN);
                }).stream().filter(Objects::nonNull).toList();
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
                + " (INSTANCE_ID VARCHAR(255) PRIMARY KEY, RECEIVED_AT BIGINT NOT NULL, SUMMARY_JSON CLOB NOT NULL, "
                + "BASELINE_JSON CLOB)");
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

    private MetricsSummary fromJsonNullable(String json) {
        return json == null || json.isBlank() ? null : fromJson(json);
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
