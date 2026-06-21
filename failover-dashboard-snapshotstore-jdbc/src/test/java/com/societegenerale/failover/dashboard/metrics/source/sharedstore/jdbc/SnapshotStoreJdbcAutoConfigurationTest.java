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

import com.societegenerale.failover.dashboard.metrics.source.sharedstore.SnapshotStore;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseBuilder;
import org.springframework.jdbc.datasource.embedded.EmbeddedDatabaseType;

import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;

class SnapshotStoreJdbcAutoConfigurationTest {

    private final ApplicationContextRunner runner = new ApplicationContextRunner()
            .withConfiguration(AutoConfigurations.of(SnapshotStoreJdbcAutoConfiguration.class));

    private static DataSource h2() {
        return new EmbeddedDatabaseBuilder().setType(EmbeddedDatabaseType.H2).generateUniqueName(true).build();
    }

    @Test
    void wires_jdbc_snapshot_store_when_shared_store_jdbc_and_datasource_present() {
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
    void inactive_when_store_is_not_jdbc() {
        runner.withBean(DataSource.class, SnapshotStoreJdbcAutoConfigurationTest::h2)
                .withPropertyValues("failover.dashboard.cluster.mode=shared-store")   // store defaults to inmemory
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SnapshotStore.class));
    }

    @Test
    void inactive_without_a_datasource() {
        runner.withPropertyValues(
                        "failover.dashboard.cluster.mode=shared-store",
                        "failover.dashboard.cluster.shared-store.store=jdbc")
                .run(ctx -> assertThat(ctx).doesNotHaveBean(SnapshotStore.class));
    }
}
