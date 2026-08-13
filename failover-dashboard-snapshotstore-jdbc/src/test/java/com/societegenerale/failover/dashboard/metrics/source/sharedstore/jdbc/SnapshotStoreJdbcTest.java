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
import com.societegenerale.failover.observable.metrics.ApiKpis;
import com.societegenerale.failover.observable.metrics.ConfigEntry;
import com.societegenerale.failover.observable.metrics.Latency;
import com.societegenerale.failover.observable.metrics.LiveStatus;
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import com.societegenerale.failover.observable.metrics.MetricsKpis;
import com.societegenerale.failover.observable.metrics.ClusterSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotStoreJdbcTest {

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

    /** Simulates schema provisioned upfront by the consuming service — the store itself never creates it. */
    private void createSchema(String tableName) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + tableName
                + " (INSTANCE_ID VARCHAR(255) PRIMARY KEY, RECEIVED_AT TIMESTAMP(9) WITH TIME ZONE NOT NULL, "
                + "SUMMARY_JSON CLOB NOT NULL, BASELINE_JSON CLOB, CONFIG_JSON CLOB)");
    }

    private SnapshotStoreJdbc store(int maxInstances) {
        return store(maxInstances, "");
    }

    private SnapshotStoreJdbc store(int maxInstances, String tablePrefix) {
        createSchema(tablePrefix + SnapshotStoreJdbc.BASE_TABLE);
        return new SnapshotStoreJdbc(jdbc, mapper, maxInstances, tablePrefix);
    }

    private static MetricsSummary summaryFor(String name, long success, long recovered) {
        ApiKpis k = MetricsKpis.build(name, name, success, recovered, 0, 0, 0, 0, new Latency(1, 2, 3, 4));
        return new MetricsSummary(k, List.of(k), List.of(), 0L);
    }

    @Test
    void doesNotCreateTheTableAutomatically() {
        // No createSchema() call — schema provisioning is the consuming service's responsibility, not the store's.
        SnapshotStoreJdbc store = new SnapshotStoreJdbc(jdbc, mapper, 10, "");

        assertThatThrownBy(() -> store.upsert(new ClusterSnapshot("i1", summaryFor("country", 1, 0))))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void tablePrefixNamespacesTheTable() {
        SnapshotStoreJdbc store = store(10, "DEMO_");
        store.upsert(new ClusterSnapshot("i1", summaryFor("country", 1, 0)));

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM DEMO_FAILOVER_DASHBOARD_SNAPSHOT", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void rejectsAnUnsafeTablePrefix() {
        assertThatThrownBy(() -> new SnapshotStoreJdbc(jdbc, mapper, 10, "x; DROP TABLE y;--"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("table-prefix");
    }

    @Test
    void upsertKeepsLatestPerInstanceAndRoundTripsValues() {
        SnapshotStoreJdbc store = store(10);
        store.upsert(new ClusterSnapshot("i1", summaryFor("country", 1, 0)));
        store.upsert(new ClusterSnapshot("i1", summaryFor("country", 9, 4)));   // replaces

        assertThat(store.allInstances()).singleElement().satisfies(im -> {
            assertThat(im.instanceId()).isEqualTo("i1");
            assertThat(im.summary().perApi().getFirst().upstreamSuccess()).isEqualTo(9);
            assertThat(im.summary().perApi().getFirst().recovered()).isEqualTo(4);
            assertThat(im.liveStatus()).isEqualTo(LiveStatus.UNKNOWN);
        });
    }

    @Test
    void allInstancesReturnsAllWithUnknownStatus() {
        SnapshotStoreJdbc store = store(10);
        store.upsert(new ClusterSnapshot("i1", summaryFor("country", 1, 0)));
        store.upsert(new ClusterSnapshot("i2", summaryFor("country", 2, 0)));

        assertThat(store.allInstances()).hasSize(2)
                .allMatch(im -> im.liveStatus() == LiveStatus.UNKNOWN);
    }

    @Test
    void allInstancesAlwaysIncludesOldSnapshots() {
        // Old data is retained — stale peer still contributes last-known values.
        SnapshotStoreJdbc store = store(10);
        store.upsert(new ClusterSnapshot("i1", summaryFor("country", 42, 0)));

        assertThat(store.allInstances()).singleElement()
                .satisfies(im -> assertThat(im.summary().perApi().getFirst().upstreamSuccess()).isEqualTo(42));
    }

    @Test
    void survivesANewStoreInstanceOverTheSameTable() {
        store(10).upsert(new ClusterSnapshot("i1", summaryFor("country", 7, 0)));
        // a "restart": a fresh store object over the same datasource/table still sees the row
        SnapshotStoreJdbc reopened = store(10);

        assertThat(reopened.allInstances()).singleElement()
                .satisfies(im -> assertThat(im.summary().perApi().getFirst().upstreamSuccess()).isEqualTo(7));
    }

    @Test
    void recordsBeyondMaxInstancesStillStoredAsWarnOnlyGuard() {
        SnapshotStoreJdbc store = store(2);
        store.upsert(new ClusterSnapshot("i1", summaryFor("country", 1, 0)));
        store.upsert(new ClusterSnapshot("i2", summaryFor("country", 1, 0)));
        store.upsert(new ClusterSnapshot("i3", summaryFor("country", 1, 0)));   // beyond ceiling — warn only

        assertThat(store.allInstances()).hasSize(3);
    }

    @Test
    void counterResetFoldsPreviousTotalsIntoBaseline() {
        // A peer restart resets its counters; the served summary must be baseline + raw, never smaller.
        SnapshotStoreJdbc store = store(10);
        store.upsert(new ClusterSnapshot("i1", summaryFor("country", 100, 0)));
        store.upsert(new ClusterSnapshot("i1", summaryFor("country", 3, 0)));   // 3 < 100 → reset detected

        assertThat(store.allInstances()).singleElement()
                .satisfies(im -> assertThat(im.summary().perApi().getFirst().upstreamSuccess()).isEqualTo(103));

        store.upsert(new ClusterSnapshot("i1", summaryFor("country", 7, 0)));   // same process grows — no re-fold

        assertThat(store.allInstances()).singleElement()
                .satisfies(im -> assertThat(im.summary().perApi().getFirst().upstreamSuccess()).isEqualTo(107));
    }

    @Test
    void carriedBaselineSurvivesADashboardRestart() {
        SnapshotStoreJdbc store = store(10);
        store.upsert(new ClusterSnapshot("i1", summaryFor("country", 100, 0)));
        store.upsert(new ClusterSnapshot("i1", summaryFor("country", 3, 0)));   // baseline 100 persisted

        SnapshotStoreJdbc reopened = store(10);                                 // fresh store, same table

        assertThat(reopened.allInstances()).singleElement()
                .satisfies(im -> assertThat(im.summary().perApi().getFirst().upstreamSuccess()).isEqualTo(103));
    }

    private static ConfigEntry entry(String name) {
        return new ConfigEntry(name, name, 24L, "HOURS", false,
                "default", "default", "default", "inmemory", "basic", "rethrow", true);
    }

    @Test
    void configEntriesRoundTripAndSurviveAStoreRestart() {
        SnapshotStoreJdbc store = store(10);
        store.upsert(new ClusterSnapshot("i1", summaryFor("country", 1, 0), List.of(entry("country-by-code"))));

        SnapshotStoreJdbc reopened = store(10);   // fresh store, same table

        assertThat(reopened.configEntries()).singleElement()
                .satisfies(e -> {
                    assertThat(e.name()).isEqualTo("country-by-code");
                    assertThat(e.expiryDuration()).isEqualTo(24L);
                });
    }

    @Test
    void configEntriesMergedAcrossInstancesUnionedByName() {
        SnapshotStoreJdbc store = store(10);
        store.upsert(new ClusterSnapshot("i1", summaryFor("a", 1, 0), List.of(entry("alpha"))));
        store.upsert(new ClusterSnapshot("i2", summaryFor("a", 1, 0), List.of(entry("zebra"))));

        assertThat(store.configEntries()).extracting(ConfigEntry::name).containsExactly("alpha", "zebra");
    }

    @Test
    void configEntriesReflectsTheLatestPushForAReUpsertedInstance() {
        SnapshotStoreJdbc store = store(10);
        ConfigEntry stale = new ConfigEntry("alpha", "alpha", 1L, "HOURS", false,
                "default", "default", "default", "inmemory", "basic", "rethrow", true);
        ConfigEntry fresh = new ConfigEntry("alpha", "alpha", 2L, "HOURS", false,
                "default", "default", "default", "inmemory", "basic", "rethrow", true);
        store.upsert(new ClusterSnapshot("i1", summaryFor("a", 1, 0), List.of(stale)));
        store.upsert(new ClusterSnapshot("i1", summaryFor("a", 1, 0), List.of(fresh)));   // same instance, re-pushed

        assertThat(store.configEntries()).singleElement()
                .satisfies(e -> assertThat(e.expiryDuration()).isEqualTo(2L));
    }

    @Test
    void configEntriesEmptyWhenNothingPushedYet() {
        assertThat(store(10).configEntries()).isEmpty();
    }

    @Test
    void configEntriesTreatsANullConfigJsonRowAsEmpty() {
        // Simulates a row written before CONFIG_JSON existed (or by an older peer): NULL in that column.
        SnapshotStoreJdbc store = store(10);
        store.upsert(new ClusterSnapshot("i1", summaryFor("country", 1, 0), List.of(entry("alpha"))));
        jdbc.update("UPDATE FAILOVER_DASHBOARD_SNAPSHOT SET CONFIG_JSON = NULL WHERE INSTANCE_ID = 'i1'");

        assertThat(store.configEntries()).isEmpty();
        assertThat(store.allInstances()).hasSize(1);   // the row itself (metrics) is unaffected
    }
}
