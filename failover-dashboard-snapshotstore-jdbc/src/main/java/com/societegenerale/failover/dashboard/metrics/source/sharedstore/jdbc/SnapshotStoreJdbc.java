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

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.societegenerale.failover.observable.metrics.ClusterSnapshot;
import com.societegenerale.failover.observable.metrics.ConfigEntry;
import com.societegenerale.failover.observable.metrics.InstanceMetrics;
import com.societegenerale.failover.observable.metrics.LiveStatus;
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import com.societegenerale.failover.observable.metrics.SnapshotBaseline;
import com.societegenerale.failover.observable.metrics.SnapshotTablePrefix;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.SnapshotStore;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Durable {@link SnapshotStore} backed by a single JDBC table — the {@code store=jdbc} option for the shared-store
 * tier, so cluster aggregation survives a dashboard restart. All snapshots are retained regardless of age; the dashboard
 * always shows each instance's last-known values, with staleness visible through the per-instance {@code lastSeenEpochMs}
 * timestamp in the Instances tab. The {@link MetricsSummary} is stored as JSON. Applies the {@link SnapshotBaseline}
 * reset-aware carry-forward (persisted in the nullable {@code BASELINE_JSON} column), so a peer restart never
 * shrinks the cluster aggregate — and the carried baseline itself survives a dashboard restart.
 *
 * <p>Table {@code (INSTANCE_ID PK, RECEIVED_AT TIMESTAMP WITH TIME ZONE, SUMMARY_JSON CLOB, BASELINE_JSON CLOB NULL,
 * CONFIG_JSON CLOB NULL)}, named {@code <tablePrefix> +} {@link #BASE_TABLE} (the prefix is validated —
 * letters/digits/underscore only — since it is concatenated into SQL). {@code RECEIVED_AT} is read/written as
 * {@link OffsetDateTime} (always {@link ZoneOffset#UTC}) via {@code setObject}/{@code getObject}, the portable JDBC
 * 4.2 mapping for a time-zone-aware column, and converted to/from epoch-millis at this boundary — the rest of the
 * codebase (e.g. {@code InstanceMetrics.lastSeenEpochMs}) stays in epoch-millis. Upsert is a portable
 * update-then-insert (no dialect-specific MERGE). The table is never created or altered by this store — schema
 * management is the consuming service's responsibility; see the module docs for the DDL to run per dialect. Stores
 * only aggregate, non-sensitive failover metrics — never business data — so a single shared table is sufficient
 * (no multi-tenancy; use {@code tablePrefix} to namespace).
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

    /**
     * Creates a new store.
     *
     * @param jdbc         the JDBC template to run against
     * @param mapper       serializes/deserializes the summary/baseline/config JSON columns
     * @param maxInstances supported small-cluster ceiling; beyond it a warning is logged
     * @param tablePrefix  prefix prepended to the base table name; letters/digits/underscore only
     */
    public SnapshotStoreJdbc(JdbcTemplate jdbc, ObjectMapper mapper, int maxInstances, String tablePrefix) {
        this.jdbc = jdbc;
        this.mapper = mapper;
        this.maxInstances = maxInstances;
        this.table = SnapshotTablePrefix.validate(tablePrefix) + BASE_TABLE;
        log.info("Failover shared-store using durable JDBC snapshot store (table='{}').", this.table);
    }

    @Override
    public void upsert(ClusterSnapshot snapshot) {
        String id = snapshot.instanceId();
        OffsetDateTime now = OffsetDateTime.now(ZoneOffset.UTC);
        List<MetricsSummary[]> existing = jdbc.query(
                "SELECT SUMMARY_JSON, BASELINE_JSON FROM " + table + " WHERE INSTANCE_ID = ?",
                (rs, rowNum) -> new MetricsSummary[]{fromJson(rs.getString("SUMMARY_JSON")),
                        fromJsonNullable(rs.getString("BASELINE_JSON"))},
                id);
        String configJson = toJsonConfig(snapshot.configEntries());
        if (existing.isEmpty()) {
            warnIfOverCeiling();
            jdbc.update("INSERT INTO " + table + " (INSTANCE_ID, RECEIVED_AT, SUMMARY_JSON, BASELINE_JSON, CONFIG_JSON) VALUES (?, ?, ?, ?, ?)",
                    id, now, toJson(snapshot.summary()), null, configJson);
            return;
        }
        MetricsSummary baseline = SnapshotBaseline.next(existing.getFirst()[0], existing.getFirst()[1], snapshot.summary());
        jdbc.update("UPDATE " + table + " SET RECEIVED_AT = ?, SUMMARY_JSON = ?, BASELINE_JSON = ?, CONFIG_JSON = ? WHERE INSTANCE_ID = ?",
                now, toJson(snapshot.summary()), baseline == null ? null : toJson(baseline), configJson, id);
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
                    long receivedAt = rs.getObject("RECEIVED_AT", OffsetDateTime.class).toInstant().toEpochMilli();
                    return new InstanceMetrics(rs.getString("INSTANCE_ID"), receivedAt, combined, LiveStatus.UNKNOWN);
                }).stream().filter(Objects::nonNull).toList();
    }

    /**
     * The {@code @Failover} configuration, merged across every row (config is expected identical
     * cluster-wide; when instances disagree the most recently received row per name wins).
     */
    @Override
    public List<ConfigEntry> configEntries() {
        Map<String, ConfigEntry> byName = new LinkedHashMap<>();
        List<List<ConfigEntry>> rows = jdbc.query(
                "SELECT CONFIG_JSON FROM " + table + " ORDER BY RECEIVED_AT ASC",
                (rs, rowNum) -> fromJsonConfig(rs.getString("CONFIG_JSON")));
        for (List<ConfigEntry> row : rows) {
            for (ConfigEntry entry : row) {
                byName.put(entry.name(), entry);   // last-seen instance wins per name
            }
        }
        return byName.values().stream().sorted(Comparator.comparing(ConfigEntry::name)).toList();
    }

    private void warnIfOverCeiling() {
        Integer total = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class);
        if (total != null && total >= maxInstances) {
            log.warn("Failover shared-store (JDBC) has {} instances (max-instances={}); consider cluster.mode=prometheus "
                    + "for clusters this large.", total, maxInstances);
        }
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

    private String toJsonConfig(List<ConfigEntry> configEntries) {
        try {
            return mapper.writeValueAsString(configEntries);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize snapshot config entries", e);
        }
    }

    /** {@code null}/blank (older row, or a peer that pushed no config) reads back as an empty list. */
    private List<ConfigEntry> fromJsonConfig(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            return mapper.readValue(json, new TypeReference<>() {
            });
        } catch (Exception e) {
            log.warn("Skipping unreadable snapshot config row: {}", e.toString());
            return List.of();
        }
    }
}
