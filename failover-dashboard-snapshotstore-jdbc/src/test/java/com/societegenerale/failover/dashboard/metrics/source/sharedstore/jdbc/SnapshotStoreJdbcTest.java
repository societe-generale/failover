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
import com.societegenerale.failover.dashboard.metrics.ApiKpis;
import com.societegenerale.failover.dashboard.metrics.Latency;
import com.societegenerale.failover.dashboard.metrics.MetricsSummary;
import com.societegenerale.failover.dashboard.metrics.source.DashboardKpis;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.ClusterSnapshot;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SnapshotStoreJdbcTest {

    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;
    private final ObjectMapper mapper = new ObjectMapper();
    private final AtomicLong now = new AtomicLong(100_000);

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

    private SnapshotStoreJdbc store(long livenessMs, int maxInstances) {
        return new SnapshotStoreJdbc(jdbc, mapper, livenessMs, maxInstances,
                "", true, now::get);   // empty table-prefix ⇒ base table FAILOVER_DASHBOARD_SNAPSHOT
    }

    private static MetricsSummary summaryFor(String name, long success, long recovered) {
        ApiKpis k = DashboardKpis.build(name, name, success, recovered, 0, 0, 0, 0, new Latency(1, 2, 3, 4));
        return new MetricsSummary(k, List.of(k), List.of(), 0L);
    }

    @Test
    void autoDdlCreatesTheTable() {
        store(60_000, 10);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM FAILOVER_DASHBOARD_SNAPSHOT", Integer.class);
        assertThat(count).isZero();
    }

    @Test
    void tablePrefixNamespacesTheTable() {
        SnapshotStoreJdbc store = new SnapshotStoreJdbc(jdbc, mapper, 60_000, 10, "DEMO_", true, now::get);
        store.upsert(new ClusterSnapshot("i1", summaryFor("country", 1, 0)));

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM DEMO_FAILOVER_DASHBOARD_SNAPSHOT", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void rejectsAnUnsafeTablePrefix() {
        assertThatThrownBy(() -> new SnapshotStoreJdbc(jdbc, mapper, 60_000, 10, "x; DROP TABLE y;--", true, now::get))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("table-prefix");
    }

    @Test
    void upsertKeepsLatestPerInstanceAndRoundTripsValues() {
        SnapshotStoreJdbc store = store(60_000, 10);
        store.upsert(new ClusterSnapshot("i1", summaryFor("country", 1, 0)));
        store.upsert(new ClusterSnapshot("i1", summaryFor("country", 9, 4)));   // replaces

        assertThat(store.liveCount()).isEqualTo(1);
        assertThat(store.live()).singleElement().satisfies(s -> {
            assertThat(s.perApi().getFirst().upstreamSuccess()).isEqualTo(9);
            assertThat(s.perApi().getFirst().recovered()).isEqualTo(4);
        });
    }

    @Test
    void liveExcludesSnapshotsOlderThanLivenessAndReportsNewest() {
        SnapshotStoreJdbc store = store(1_000, 10);
        store.upsert(new ClusterSnapshot("i1", summaryFor("country", 1, 0)));   // t=100000
        now.set(100_500);
        store.upsert(new ClusterSnapshot("i2", summaryFor("country", 2, 0)));   // t=100500
        now.set(101_200);                                                       // i1 1200ms old (>1000)

        assertThat(store.liveCount()).isEqualTo(1);
        assertThat(store.newestEpochMs()).isEqualTo(100_500);
        assertThat(store.live()).singleElement()
                .satisfies(s -> assertThat(s.perApi().getFirst().upstreamSuccess()).isEqualTo(2));
    }

    @Test
    void newestIsZeroWhenNothingLive() {
        SnapshotStoreJdbc store = store(1_000, 10);
        store.upsert(new ClusterSnapshot("i1", summaryFor("country", 1, 0)));
        now.addAndGet(5_000);

        assertThat(store.liveCount()).isZero();
        assertThat(store.newestEpochMs()).isZero();
        assertThat(store.live()).isEmpty();
    }

    @Test
    void survivesANewStoreInstanceOverTheSameTable() {
        store(60_000, 10).upsert(new ClusterSnapshot("i1", summaryFor("country", 7, 0)));
        // a "restart": a fresh store object over the same datasource/table still sees the row
        SnapshotStoreJdbc reopened = store(60_000, 10);

        assertThat(reopened.liveCount()).isEqualTo(1);
        assertThat(reopened.live()).singleElement()
                .satisfies(s -> assertThat(s.perApi().getFirst().upstreamSuccess()).isEqualTo(7));
    }
}
