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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class HeartbeatStoreJdbcTest {

    private EmbeddedDatabase db;
    private JdbcTemplate jdbc;

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
                + " (INSTANCE_ID VARCHAR(255) PRIMARY KEY, LAST_SEEN TIMESTAMP(9) WITH TIME ZONE NOT NULL)");
    }

    private HeartbeatStoreJdbc store(String tablePrefix) {
        createSchema(tablePrefix + HeartbeatStoreJdbc.BASE_TABLE);
        return new HeartbeatStoreJdbc(jdbc, tablePrefix);
    }

    private HeartbeatStoreJdbc store() {
        return store("");
    }

    @Test
    void lastSeenIsNullWhenNoHeartbeatEverReceived() {
        assertThat(store().lastSeen("i1")).isNull();
    }

    @Test
    void recordThenLastSeenRoundTrips() {
        HeartbeatStoreJdbc store = store();
        store.record("i1");

        assertThat(store.lastSeen("i1")).isNotNull();
    }

    @Test
    void recordTwiceUpdatesTheSameRowRatherThanInserting() {
        HeartbeatStoreJdbc store = store();
        store.record("i1");
        Long first = store.lastSeen("i1");
        store.record("i1");
        Long second = store.lastSeen("i1");

        assertThat(second).isGreaterThanOrEqualTo(first);
        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM FAILOVER_DASHBOARD_HEARTBEAT", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void tracksMultipleInstancesIndependently() {
        HeartbeatStoreJdbc store = store();
        store.record("i1");
        store.record("i2");

        assertThat(store.lastSeen("i1")).isNotNull();
        assertThat(store.lastSeen("i2")).isNotNull();
        assertThat(store.lastSeen("i3")).isNull();
    }

    @Test
    void survivesANewStoreInstanceOverTheSameTable() {
        store().record("i1");
        // a "restart": a fresh store object over the same datasource/table still sees the row
        HeartbeatStoreJdbc reopened = store();

        assertThat(reopened.lastSeen("i1")).isNotNull();
    }

    @Test
    void tablePrefixNamespacesTheTable() {
        HeartbeatStoreJdbc store = store("DEMO_");
        store.record("i1");

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM DEMO_FAILOVER_DASHBOARD_HEARTBEAT", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void rejectsAnUnsafeTablePrefix() {
        assertThatThrownBy(() -> new HeartbeatStoreJdbc(jdbc, "x; DROP TABLE y;--"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("table-prefix");
    }

    @Test
    void doesNotCreateTheTableAutomatically() {
        // No createSchema() call — schema provisioning is the consuming service's responsibility, not the store's.
        HeartbeatStoreJdbc store = new HeartbeatStoreJdbc(jdbc, "");

        // Fails soft: a missing table degrades to "no heartbeat" rather than breaking the caller.
        assertThatCode(() -> store.record("i1")).doesNotThrowAnyException();
    }

    @Test
    void lastSeenFailsSoftWhenTableIsMissing() {
        // No createSchema() call.
        HeartbeatStoreJdbc store = new HeartbeatStoreJdbc(jdbc, "");

        assertThatCode(() -> assertThat(store.lastSeen("i1")).isNull()).doesNotThrowAnyException();
    }

    @Test
    void recoversOnceTheTableIsCreatedAfterTheFact() {
        // Table missing at construction time — first calls fail soft.
        HeartbeatStoreJdbc store = new HeartbeatStoreJdbc(jdbc, "");
        store.record("i1");
        assertThat(store.lastSeen("i1")).isNull();

        // Operator fixes it live, no restart needed — the very next call succeeds normally.
        createSchema(HeartbeatStoreJdbc.BASE_TABLE);
        store.record("i1");

        assertThat(store.lastSeen("i1")).isNotNull();
    }
}
