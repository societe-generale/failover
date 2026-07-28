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

import com.societegenerale.failover.dashboard.metrics.source.sharedstore.HeartbeatStore;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.SnapshotStore;
import com.societegenerale.failover.observable.metrics.ApiKpis;
import com.societegenerale.failover.observable.metrics.ClusterSnapshot;
import com.societegenerale.failover.observable.metrics.Latency;
import com.societegenerale.failover.observable.metrics.MetricsKpis;
import com.societegenerale.failover.observable.metrics.MetricsSummary;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotStoreJdbcAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnapshotStoreJdbcAutoConfiguration.class));

    private static DataSource h2() {
        return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
    }

    @Test
    void wiresJdbcSnapshotStoreWhenSharedStoreJdbcAndDatasourcePresent() {
        runner.withBean(DataSource.class, SnapshotStoreJdbcAutoConfigurationTest::h2)
                .withPropertyValues(
                        "failover.dashboard.cluster.mode=shared-store",
                        "failover.dashboard.cluster.shared-store.store=jdbc")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(SnapshotStore.class);
                    assertThat(ctx.getBean(SnapshotStore.class)).isInstanceOf(SnapshotStoreJdbc.class);
                });
    }

    @Test
    void inactiveWhenStoreIsNotJdbc() {
        runner.withBean(DataSource.class, SnapshotStoreJdbcAutoConfigurationTest::h2)
                .withPropertyValues("failover.dashboard.cluster.mode=shared-store")   // store defaults to inmemory
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SnapshotStore.class));
    }

    @Test
    void inactiveWithoutADatasource() {
        runner.withPropertyValues(
                        "failover.dashboard.cluster.mode=shared-store",
                        "failover.dashboard.cluster.shared-store.store=jdbc")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SnapshotStore.class));
    }

    @Test
    void wiresJdbcHeartbeatStoreWhenSharedStoreJdbcAndDatasourcePresentAndLivenessEnabled() {
        runner.withBean(DataSource.class, SnapshotStoreJdbcAutoConfigurationTest::h2)
                .withPropertyValues(
                        "failover.dashboard.cluster.mode=shared-store",
                        "failover.dashboard.cluster.shared-store.store=jdbc",
                        "failover.dashboard.cluster.shared-store.liveness.enabled=true")
                .run(ctx -> {
                    assertThat(ctx).hasSingleBean(HeartbeatStore.class);
                    assertThat(ctx.getBean(HeartbeatStore.class)).isInstanceOf(HeartbeatStoreJdbc.class);
                });
    }

    /** ADR 66: dashboard-side liveness tracking is off by default, even with store=jdbc and a DataSource present. */
    @Test
    void heartbeatStoreAbsentByDefaultWhenLivenessNotEnabled() {
        runner.withBean(DataSource.class, SnapshotStoreJdbcAutoConfigurationTest::h2)
                .withPropertyValues(
                        "failover.dashboard.cluster.mode=shared-store",
                        "failover.dashboard.cluster.shared-store.store=jdbc")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(HeartbeatStore.class));
    }

    @Test
    void heartbeatStoreInactiveWhenStoreIsNotJdbc() {
        runner.withBean(DataSource.class, SnapshotStoreJdbcAutoConfigurationTest::h2)
                .withPropertyValues(
                        "failover.dashboard.cluster.mode=shared-store",   // store defaults to inmemory
                        "failover.dashboard.cluster.shared-store.liveness.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(HeartbeatStore.class));
    }

    @Test
    void heartbeatStoreInactiveWithoutADatasource() {
        runner.withPropertyValues(
                        "failover.dashboard.cluster.mode=shared-store",
                        "failover.dashboard.cluster.shared-store.store=jdbc",
                        "failover.dashboard.cluster.shared-store.liveness.enabled=true")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(HeartbeatStore.class));
    }

    /**
     * Regression test for a relaxed-binding gap: {@code SharedStore}/{@code Jdbc} carry a convenience no-arg
     * constructor alongside the canonical one, which Spring Boot's binder treats as ambiguous unless the
     * canonical constructor is marked {@code @ConstructorBinding} — without it, nested properties like
     * {@code jdbc.table-prefix} silently fall back to their hardcoded default instead of the configured value.
     */
    @Test
    void honorsConfiguredTablePrefix() {
        DataSource dataSource = h2();
        new JdbcTemplate(dataSource).execute("CREATE TABLE DEMO_FAILOVER_DASHBOARD_SNAPSHOT "
                + "(INSTANCE_ID VARCHAR(255) PRIMARY KEY, RECEIVED_AT TIMESTAMP(9) WITH TIME ZONE NOT NULL, "
                + "SUMMARY_JSON CLOB NOT NULL, BASELINE_JSON CLOB, CONFIG_JSON CLOB)");

        runner.withBean(DataSource.class, () -> dataSource)
                .withPropertyValues(
                        "failover.dashboard.cluster.mode=shared-store",
                        "failover.dashboard.cluster.shared-store.store=jdbc",
                        "failover.dashboard.cluster.shared-store.jdbc.table-prefix=DEMO_")
                .run(ctx -> {
                    SnapshotStore store = ctx.getBean(SnapshotStore.class);
                    ApiKpis k = MetricsKpis.build("country", "country", 1, 0, 0, 0, 0, 0, new Latency(1, 2, 3, 4));
                    store.upsert(new ClusterSnapshot("i1", new MetricsSummary(k, List.of(k), List.of(), 0L)));

                    Integer count = new JdbcTemplate(dataSource)
                            .queryForObject("SELECT COUNT(*) FROM DEMO_FAILOVER_DASHBOARD_SNAPSHOT", Integer.class);
                    assertThat(count).isEqualTo(1);
                });
    }

    /** Same regression coverage as {@link #honorsConfiguredTablePrefix()}, for the heartbeat store. */
    @Test
    void heartbeatStoreHonorsConfiguredTablePrefix() {
        DataSource dataSource = h2();
        new JdbcTemplate(dataSource).execute(
                "CREATE TABLE DEMO_FAILOVER_DASHBOARD_HEARTBEAT (INSTANCE_ID VARCHAR(255) PRIMARY KEY, "
                        + "LAST_SEEN TIMESTAMP(9) WITH TIME ZONE NOT NULL)");

        runner.withBean(DataSource.class, () -> dataSource)
                .withPropertyValues(
                        "failover.dashboard.cluster.mode=shared-store",
                        "failover.dashboard.cluster.shared-store.store=jdbc",
                        "failover.dashboard.cluster.shared-store.jdbc.table-prefix=DEMO_",
                        "failover.dashboard.cluster.shared-store.liveness.enabled=true")
                .run(ctx -> {
                    ctx.getBean(HeartbeatStore.class).record("i1");

                    Integer count = new JdbcTemplate(dataSource)
                            .queryForObject("SELECT COUNT(*) FROM DEMO_FAILOVER_DASHBOARD_HEARTBEAT", Integer.class);
                    assertThat(count).isEqualTo(1);
                });
    }
}
