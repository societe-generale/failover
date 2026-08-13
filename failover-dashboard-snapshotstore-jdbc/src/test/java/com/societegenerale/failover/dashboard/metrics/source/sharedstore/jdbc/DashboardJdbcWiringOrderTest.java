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

import com.societegenerale.failover.dashboard.config.DashboardAutoConfiguration;
import com.societegenerale.failover.dashboard.web.ClusterHeartbeatController;
import com.societegenerale.failover.dashboard.web.ClusterSnapshotController;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Regression coverage for a cross-module auto-configuration ordering bug: {@code clusterSnapshotController} /
 * {@code clusterHeartbeatController} in {@code DashboardAutoConfiguration} are {@code @ConditionalOnBean(SnapshotStore /
 * HeartbeatStore.class)}, but those beans are supplied by the separately-conditioned
 * {@link SnapshotStoreJdbcAutoConfiguration}. {@code @ConditionalOnBean} across two independently-conditioned
 * {@code @AutoConfiguration} classes is unreliable without an explicit ordering edge — it depends on which
 * class's {@code @Bean} methods get their conditions evaluated first, which is <em>not</em> determined by the
 * order passed to {@code AutoConfigurations.of(...)} (confirmed empirically: without the ordering edge, both
 * declaration orders left the ingest controllers unregistered, a silent 404 on {@code /api/cluster/snapshot} and
 * {@code /api/cluster/heartbeat}). {@code DashboardAutoConfiguration}'s {@code @AutoConfiguration(afterName = ...)}
 * now includes {@code SnapshotStoreJdbcAutoConfiguration} by name (no compile dependency — same pattern already
 * used for {@code FailoverAutoConfiguration} in the same list) to fix the ordering regardless of classpath order.
 *
 * @author Anand Manissery
 */
class DashboardJdbcWiringOrderTest {

    private static DataSource h2() {
        return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
    }

    private WebApplicationContextRunner runner(Class<?>... autoConfigurations) {
        return new WebApplicationContextRunner()
                .withConfiguration(AutoConfigurations.of(autoConfigurations))
                .withBean(MeterRegistry.class, SimpleMeterRegistry::new)
                .withBean(DataSource.class, DashboardJdbcWiringOrderTest::h2)
                .withPropertyValues(
                        "failover.dashboard.enabled=true",
                        "failover.dashboard.security.allow-insecure=true",
                        "failover.dashboard.cluster.mode=shared-store",
                        "failover.dashboard.cluster.shared-store.store=jdbc",
                        "failover.dashboard.cluster.shared-store.liveness.enabled=true");
    }

    @Test
    void wiresIngestControllersWhenDashboardIsDeclaredBeforeTheJdbcModule() {
        runner(DashboardAutoConfiguration.class, SnapshotStoreJdbcAutoConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(ClusterSnapshotController.class);
                    assertThat(ctx).hasSingleBean(ClusterHeartbeatController.class);
                });
    }

    @Test
    void wiresIngestControllersWhenTheJdbcModuleIsDeclaredBeforeDashboard() {
        runner(SnapshotStoreJdbcAutoConfiguration.class, DashboardAutoConfiguration.class)
                .run(ctx -> {
                    assertThat(ctx).hasNotFailed();
                    assertThat(ctx).hasSingleBean(ClusterSnapshotController.class);
                    assertThat(ctx).hasSingleBean(ClusterHeartbeatController.class);
                });
    }
}
