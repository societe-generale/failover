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

import com.societegenerale.failover.observable.metrics.ClusterSnapshot;
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import com.societegenerale.failover.observable.metrics.SnapshotBaseline;
import com.societegenerale.failover.observable.metrics.SnapshotTablePrefix;
import com.societegenerale.failover.observable.micrometer.SnapshotPushClient;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import tools.jackson.databind.ObjectMapper;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/**
 * {@link SnapshotPushClient} that writes a {@link ClusterSnapshot} straight into the dashboard's
 * {@code FAILOVER_DASHBOARD_SNAPSHOT} table instead of POSTing to the ingest endpoint — the
 * {@code failover.dashboard.cluster.snapshot.jdbc.enabled=true} transport. Registered by
 * {@link FailoverMicrometerAutoConfiguration} in place of {@link RestClientSnapshotPushClient} when active;
 * {@link com.societegenerale.failover.observable.micrometer.ClusterSnapshotPublisher} owns throttling and
 * backoff identically either way — only the transport differs.
 *
 * <p>Same table, same columns, same reset-aware {@link SnapshotBaseline} carry-forward as the dashboard's own
 * {@code SnapshotStoreJdbc} (upsert is a portable update-then-insert, no dialect-specific MERGE) — the two
 * writers are interchangeable from the table's point of view. This instance never reads the dashboard's
 * aggregated view, only its own row.
 *
 * @author Anand Manissery
 */
@Slf4j
public class JdbcSnapshotPushClient implements SnapshotPushClient {

    /** Base (unprefixed) table name; the configured {@code table-prefix} is prepended. */
    public static final String BASE_TABLE = "FAILOVER_DASHBOARD_SNAPSHOT";

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;
    private final String table;

    /**
     * Creates a new client.
     *
     * @param jdbc        the JDBC template to run against — a {@link javax.sql.DataSource} pointed at the
     *                    dashboard's database
     * @param mapper      serializes the summary/baseline/config JSON columns
     * @param tablePrefix prefix prepended to the base table name; must match the dashboard's configured prefix
     */
    public JdbcSnapshotPushClient(JdbcTemplate jdbc, ObjectMapper mapper, String tablePrefix) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.table = SnapshotTablePrefix.validate(tablePrefix) + BASE_TABLE;
        log.info("Failover shared-store snapshot publisher writing directly to JDBC table '{}' (no ingest endpoint).",
                this.table);
    }

    @Override
    public void send(ClusterSnapshot snapshot) {
        String id = snapshot.instanceId();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<MetricsSummary[]> existing = jdbc.query(
                "SELECT SUMMARY_JSON, BASELINE_JSON FROM " + table + " WHERE INSTANCE_ID = ?",
                (rs, rowNum) -> new MetricsSummary[]{fromJson(rs.getString("SUMMARY_JSON")),
                        fromJsonNullable(rs.getString("BASELINE_JSON"))},
                id);
        String configJson = mapper.writeValueAsString(snapshot.configEntries());
        if (existing.isEmpty()) {
            jdbc.update("INSERT INTO " + table + " (INSTANCE_ID, RECEIVED_AT, SUMMARY_JSON, BASELINE_JSON, CONFIG_JSON) VALUES (?, ?, ?, ?, ?)",
                    id, now, mapper.writeValueAsString(snapshot.summary()), null, configJson);
            return;
        }
        MetricsSummary baseline = SnapshotBaseline.next(existing.getFirst()[0], existing.getFirst()[1], snapshot.summary());
        jdbc.update("UPDATE " + table + " SET RECEIVED_AT = ?, SUMMARY_JSON = ?, BASELINE_JSON = ?, CONFIG_JSON = ? WHERE INSTANCE_ID = ?",
                now, mapper.writeValueAsString(snapshot.summary()), baseline == null ? null : mapper.writeValueAsString(baseline),
                configJson, id);
    }

    private MetricsSummary fromJson(String json) {
        return mapper.readValue(json, MetricsSummary.class);
    }

    private MetricsSummary fromJsonNullable(String json) {
        return json == null || json.isBlank() ? null : fromJson(json);
    }
}
