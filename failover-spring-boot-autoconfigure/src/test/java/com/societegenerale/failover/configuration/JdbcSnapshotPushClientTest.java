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

import com.societegenerale.failover.observable.metrics.ApiKpis;
import com.societegenerale.failover.observable.metrics.ClusterSnapshot;
import com.societegenerale.failover.observable.metrics.Latency;
import com.societegenerale.failover.observable.metrics.MetricsKpis;
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;
import tools.jackson.databind.ObjectMapper;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies {@link JdbcSnapshotPushClient} writes rows the dashboard's {@code SnapshotStoreJdbc} can read back:
 * same table/columns, same reset-aware baseline carry-forward. No dependency on the dashboard module — this
 * asserts against the raw JDBC rows directly.
 */
class JdbcSnapshotPushClientTest {

    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();

    @BeforeEach
    void setUp() {
        db = new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2)
                .generateUniqueName(true).build();
        jdbc = new JdbcTemplate(db);
    }

    @AfterEach
    void tearDown() {
        db.shutdown();
    }

    /** Simulates schema provisioned upfront by the consuming service — the client itself never creates it. */
    private void createSchema(String tableName) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + tableName
                + " (INSTANCE_ID VARCHAR(255) PRIMARY KEY, RECEIVED_AT TIMESTAMP(9) WITH TIME ZONE NOT NULL, "
                + "SUMMARY_JSON CLOB NOT NULL, BASELINE_JSON CLOB, CONFIG_JSON CLOB)");
    }

    private JdbcSnapshotPushClient client() {
        return client("");
    }

    private JdbcSnapshotPushClient client(String tablePrefix) {
        createSchema(tablePrefix + JdbcSnapshotPushClient.BASE_TABLE);
        return new JdbcSnapshotPushClient(jdbc, mapper, tablePrefix);
    }

    private static MetricsSummary summaryFor(String name, long success, long recovered) {
        ApiKpis k = MetricsKpis.build(name, name, success, recovered, 0, 0, 0, 0, new Latency(1, 2, 3, 4));
        return new MetricsSummary(k, List.of(k), List.of(), 0L);
    }

    private MetricsSummary readSummary(String table, String instanceId) {
        String json = jdbc.queryForObject("SELECT SUMMARY_JSON FROM " + table + " WHERE INSTANCE_ID = ?",
                String.class, instanceId);
        return mapper.readValue(json, MetricsSummary.class);
    }

    @Test
    void doesNotCreateTheTableAutomatically() {
        JdbcSnapshotPushClient client = new JdbcSnapshotPushClient(jdbc, mapper, "");

        assertThatThrownBy(() -> client.send(new ClusterSnapshot("i1", summaryFor("country", 1, 0))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsAnUnsafeTablePrefix() {
        assertThatThrownBy(() -> new JdbcSnapshotPushClient(jdbc, mapper, "x; DROP TABLE y;--"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("table-prefix");
    }

    @Test
    void tablePrefixNamespacesTheTable() throws Exception {
        JdbcSnapshotPushClient client = client("DEMO_");
        client.send(new ClusterSnapshot("i1", summaryFor("country", 1, 0)));

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM DEMO_FAILOVER_DASHBOARD_SNAPSHOT", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void sendInsertsThenUpdatesTheSameInstanceRow() throws Exception {
        JdbcSnapshotPushClient client = client();
        client.send(new ClusterSnapshot("i1", summaryFor("country", 1, 0)));
        client.send(new ClusterSnapshot("i1", summaryFor("country", 9, 4)));   // replaces

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM FAILOVER_DASHBOARD_SNAPSHOT", Integer.class);
        assertThat(count).isEqualTo(1);

        MetricsSummary summary = readSummary("FAILOVER_DASHBOARD_SNAPSHOT", "i1");
        assertThat(summary.perApi().getFirst().upstreamSuccess()).isEqualTo(9);
        assertThat(summary.perApi().getFirst().recovered()).isEqualTo(4);
    }

    @Test
    void counterResetFoldsPreviousTotalsIntoBaseline() throws Exception {
        // A peer restart resets its counters; BASELINE_JSON must carry the pre-restart totals forward
        // so the dashboard's combined view (baseline + raw) never drops.
        JdbcSnapshotPushClient client = client();
        client.send(new ClusterSnapshot("i1", summaryFor("country", 100, 0)));
        client.send(new ClusterSnapshot("i1", summaryFor("country", 3, 0)));   // 3 < 100 → reset detected

        String baselineJson = jdbc.queryForObject(
                "SELECT BASELINE_JSON FROM FAILOVER_DASHBOARD_SNAPSHOT WHERE INSTANCE_ID = ?", String.class, "i1");
        assertThat(baselineJson).isNotBlank();
        MetricsSummary baseline = mapper.readValue(baselineJson, MetricsSummary.class);
        assertThat(baseline.perApi().getFirst().upstreamSuccess()).isEqualTo(100);

        MetricsSummary raw = readSummary("FAILOVER_DASHBOARD_SNAPSHOT", "i1");
        assertThat(raw.perApi().getFirst().upstreamSuccess()).isEqualTo(3);
    }

    @Test
    void configEntriesRoundTripAsJson() throws Exception {
        JdbcSnapshotPushClient client = client();
        var entry = new com.societegenerale.failover.observable.metrics.ConfigEntry(
                "country-by-code", "country-by-code", 24L, "HOURS", false,
                "default", "default", "default", "inmemory", "basic", "rethrow", true);
        client.send(new ClusterSnapshot("i1", summaryFor("country", 1, 0), List.of(entry)));

        String configJson = jdbc.queryForObject(
                "SELECT CONFIG_JSON FROM FAILOVER_DASHBOARD_SNAPSHOT WHERE INSTANCE_ID = ?", String.class, "i1");
        assertThat(configJson).contains("country-by-code");
    }
}
