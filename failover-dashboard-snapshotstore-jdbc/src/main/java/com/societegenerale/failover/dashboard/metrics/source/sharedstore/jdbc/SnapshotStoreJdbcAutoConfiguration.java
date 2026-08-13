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
import com.societegenerale.failover.dashboard.config.DashboardProperties;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.HeartbeatStore;
import com.societegenerale.failover.dashboard.metrics.source.sharedstore.SnapshotStore;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.JdbcTemplate;

import javax.sql.DataSource;

/**
 * Auto-configuration for the durable JDBC shared-store tier ({@code cluster.shared-store.store=jdbc}). Provides a
 * {@link SnapshotStoreJdbc} and a {@link HeartbeatStoreJdbc} that the dashboard's {@code SharedStoreMetricsSource}
 * picks up in place of the default in-memory stores (whose beans are disabled when {@code store != inmemory}).
 * Active only when JDBC is on the classpath and a {@link DataSource} is present; ordered after Spring Boot's
 * {@code DataSourceAutoConfiguration}.
 *
 * @author Anand Manissery
 */
@AutoConfiguration(afterName = "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration")
@ConditionalOnClass(JdbcTemplate.class)
@ConditionalOnExpression("'${failover.dashboard.cluster.mode:local}' == 'shared-store' "
        + "and '${failover.dashboard.cluster.shared-store.store:inmemory}' == 'jdbc'")
@EnableConfigurationProperties(DashboardProperties.class)
public class SnapshotStoreJdbcAutoConfiguration {

    /**
     * The durable snapshot store. {@code @ConditionalOnMissingBean} so a consumer can still override it; requires a
     * {@link DataSource} in the context.
     *
     * @param dataSource the application datasource
     * @param properties dashboard properties (shared-store liveness / max-instances / jdbc settings)
     * @param mapper     JSON mapper for serializing snapshots (a context {@link ObjectMapper} if present)
     * @return a {@link SnapshotStoreJdbc}
     */
    @Bean
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(SnapshotStore.class)
    public SnapshotStore snapshotStore(DataSource dataSource, DashboardProperties properties,
                                       ObjectProvider<ObjectMapper> mapper) {
        DashboardProperties.SharedStore sharedStore = properties.cluster().sharedStore();
        return new SnapshotStoreJdbc(
                new JdbcTemplate(dataSource),
                mapper.getIfAvailable(ObjectMapper::new),
                sharedStore.maxInstances(),
                sharedStore.jdbc().tablePrefix());
    }

    /**
     * The durable heartbeat store — keeps peer liveness consistent when the dashboard is embedded in multiple
     * {@code @Failover} instances sharing this database (each instance's own {@code HeartbeatStoreInmemory}
     * would otherwise only ever see the heartbeat it received itself). {@code @ConditionalOnMissingBean} so a
     * consumer can still override it; requires a {@link DataSource} in the context.
     *
     * <p>Gated on {@code shared-store.liveness.enabled} (default {@code false}) — off by default, so
     * {@code store=jdbc} alone never requires provisioning {@code FAILOVER_DASHBOARD_HEARTBEAT}; only
     * enabling liveness tracking does.
     *
     * @param dataSource the application datasource
     * @param properties dashboard properties (shared-store jdbc settings)
     * @return a {@link HeartbeatStoreJdbc}
     */
    @Bean
    @ConditionalOnProperty(prefix = "failover.dashboard.cluster.shared-store.liveness", name = "enabled", havingValue = "true")
    @ConditionalOnBean(DataSource.class)
    @ConditionalOnMissingBean(HeartbeatStore.class)
    public HeartbeatStore heartbeatStore(DataSource dataSource, DashboardProperties properties) {
        DashboardProperties.SharedStore sharedStore = properties.cluster().sharedStore();
        return new HeartbeatStoreJdbc(new JdbcTemplate(dataSource), sharedStore.jdbc().tablePrefix());
    }
}
