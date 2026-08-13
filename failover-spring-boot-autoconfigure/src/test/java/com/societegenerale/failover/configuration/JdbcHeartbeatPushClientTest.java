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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabase;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Verifies {@link JdbcHeartbeatPushClient} writes rows the dashboard's {@code HeartbeatStoreJdbc} can read back:
 * same table/columns. No dependency on the dashboard module — asserts against the raw JDBC rows directly.
 */
class JdbcHeartbeatPushClientTest {

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

    /** Simulates schema provisioned upfront by the consuming service — the client itself never creates it. */
    private void createSchema(String tableName) {
        jdbc.execute("CREATE TABLE IF NOT EXISTS " + tableName
                + " (INSTANCE_ID VARCHAR(255) PRIMARY KEY, LAST_SEEN TIMESTAMP(9) WITH TIME ZONE NOT NULL)");
    }

    private JdbcHeartbeatPushClient client(String tablePrefix) {
        createSchema(tablePrefix + JdbcHeartbeatPushClient.BASE_TABLE);
        return new JdbcHeartbeatPushClient(jdbc, tablePrefix);
    }

    @Test
    void doesNotCreateTheTableAutomatically() {
        JdbcHeartbeatPushClient client = new JdbcHeartbeatPushClient(jdbc, "");

        assertThatThrownBy(() -> client.send("i1")).isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsAnUnsafeTablePrefix() {
        assertThatThrownBy(() -> new JdbcHeartbeatPushClient(jdbc, "x; DROP TABLE y;--"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("table-prefix");
    }

    @Test
    void tablePrefixNamespacesTheTable() {
        JdbcHeartbeatPushClient client = client("DEMO_");
        client.send("i1");

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM DEMO_FAILOVER_DASHBOARD_HEARTBEAT", Integer.class);
        assertThat(count).isEqualTo(1);
    }

    @Test
    void sendInsertsThenUpdatesTheSameInstanceRow() {
        JdbcHeartbeatPushClient client = client("");
        client.send("i1");
        OffsetDateTime first = jdbc.queryForObject(
                "SELECT LAST_SEEN FROM FAILOVER_DASHBOARD_HEARTBEAT WHERE INSTANCE_ID = ?", OffsetDateTime.class, "i1");

        client.send("i1");
        OffsetDateTime second = jdbc.queryForObject(
                "SELECT LAST_SEEN FROM FAILOVER_DASHBOARD_HEARTBEAT WHERE INSTANCE_ID = ?", OffsetDateTime.class, "i1");

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM FAILOVER_DASHBOARD_HEARTBEAT", Integer.class);
        assertThat(count).isEqualTo(1);
        assertThat(second).isAfterOrEqualTo(first);
    }

    @Test
    void sendRecordsMultipleInstancesIndependently() {
        JdbcHeartbeatPushClient client = client("");
        client.send("i1");
        client.send("i2");

        Integer count = jdbc.queryForObject("SELECT COUNT(*) FROM FAILOVER_DASHBOARD_HEARTBEAT", Integer.class);
        assertThat(count).isEqualTo(2);
    }
}
