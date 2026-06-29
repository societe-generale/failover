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
import com.societegenerale.failover.observable.metrics.Latency;
import com.societegenerale.failover.observable.metrics.LiveStatus;
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import com.societegenerale.failover.observable.metrics.MetricsKpis;
import com.societegenerale.failover.observable.metrics.ClusterSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
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

    private SnapshotStoreJdbc store(int maxInstances) {
        return new SnapshotStoreJdbc(jdbc, mapper, maxInstances, "", true);
    }

    private static MetricsSummary summaryFor(String name, long success, long recovered) {
        ApiKpis k = MetricsKpis.build(name, name, success, recovered, 0, 0, 0, 0, new Latency(1, 2, 3, 4));
        return new MetricsSummary(k, List.of(k), List.of(), 0L);
    }

    @Test
    void autoDdlCreatesTheTable() {
        store(10);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM FAILOVER_DASHBOARD_SNAPSHOT", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void tablePrefixNamespacesTheTable() {
        SnapshotStoreJdbc store = new SnapshotStoreJdbc(jdbc, mapper, 10, "DEMO_", true);
        store.upsert(new ClusterSnapshot("i1", summaryFor("country", 1, 0)));

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM DEMO_FAILOVER_DASHBOARD_SNAPSHOT", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void rejectsAnUnsafeTablePrefix() {
        assertThatThrownBy(() -> new SnapshotStoreJdbc(jdbc, mapper, 10, "x; DROP TABLE y;--", true))
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
}
