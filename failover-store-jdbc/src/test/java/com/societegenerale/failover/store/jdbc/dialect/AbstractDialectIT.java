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

package com.societegenerale.failover.store.jdbc.dialect;

import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.store.jdbc.FailoverStoreJdbc;
import com.societegenerale.failover.store.jdbc.mapper.ReferentialPayloadRowMapper;
import com.societegenerale.failover.store.jdbc.resolver.DefaultDatabaseResolver;
import com.societegenerale.failover.store.jdbc.resolver.DefaultFailoverStoreQueryResolver;
import com.societegenerale.failover.store.jdbc.resolver.VarcharPayloadColumnResolver;
import com.societegenerale.failover.store.jdbc.serializer.JsonSerializer;
import com.societegenerale.failover.store.jdbc.serializer.Serializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.containers.JdbcDatabaseContainer;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

import java.time.Instant;
import java.time.temporal.ChronoUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;

/**
 * Shared scenario for the dialect integration tests. Each concrete subclass spins up a real
 * database via Testcontainers and runs the store/merge/find/clean round-trip against it, proving
 * that the native merge/upsert SQL emitted by {@link DefaultFailoverStoreQueryResolver} is valid
 * for that database — coverage the H2-only default build cannot provide (audit T-1).
 *
 * <p>These tests are named {@code *DialectIT} so the default build excludes them; they run only
 * under the {@code dialect-its} Maven profile and require Docker.
 *
 * @author Anand Manissery
 */
@Testcontainers
abstract class AbstractDialectIT {

    private static final String NAME = "product-service";
    private static final Instant NOW = Instant.now().truncatedTo(ChronoUnit.MILLIS);

    /** The running database container. */
    protected abstract JdbcDatabaseContainer<?> container();

    /** Dialect-specific DDL creating {@code FAILOVER_STORE} with a PK on (FAILOVER_NAME, FAILOVER_KEY). */
    protected abstract String ddl();

    /** A fragment that must appear in the resolved merge query, proving the right dialect was picked. */
    protected abstract String expectedMergeFragment();

    private JdbcTemplate jdbcTemplate;
    private Serializer serializer;
    private RowMapper<ReferentialPayload<String>> rowMapper;

    @BeforeEach
    void setUp() {
        var dataSource = new DriverManagerDataSource(
                container().getJdbcUrl(), container().getUsername(), container().getPassword());
        jdbcTemplate = new JdbcTemplate(dataSource);
        jdbcTemplate.execute("DROP TABLE IF EXISTS FAILOVER_STORE");
        jdbcTemplate.execute(ddl());
        serializer = new JsonSerializer(new JsonMapper());
        rowMapper = new ReferentialPayloadRowMapper<>(new VarcharPayloadColumnResolver(), serializer);
    }

    private FailoverStoreJdbc<String> store() {
        var databaseResolver = new DefaultDatabaseResolver(jdbcTemplate);
        var queryResolver = new DefaultFailoverStoreQueryResolver(
                "", serializer, databaseResolver, new VarcharPayloadColumnResolver());
        assertThat(queryResolver.getMergeQuery())
                .as("native merge dialect must be selected for %s, not the INSERT/UPDATE fallback",
                        container().getDockerImageName())
                .isNotNull()
                .contains(expectedMergeFragment());
        return new FailoverStoreJdbc<>(jdbcTemplate, queryResolver, rowMapper);
    }

    private ReferentialPayload<String> payload(String key, String value, Instant expireOn) {
        return new ReferentialPayload<>(NAME, key, true, NOW, expireOn, value);
    }

    @Test
    void storeThenMergeOverwritesUnderTheSameKey() {
        var store = store();
        store.store(payload("p-1", "first", NOW.plus(1, ChronoUnit.HOURS)));
        // Second store under the same key must upsert via the native merge dialect, not duplicate-fail.
        store.store(payload("p-1", "second", NOW.plus(1, ChronoUnit.HOURS)));

        assertThat(store.find(NAME, "p-1"))
                .isPresent()
                .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("second"));
        assertThat(jdbcTemplate.queryForObject("SELECT COUNT(*) FROM FAILOVER_STORE", Integer.class))
                .isEqualTo(1);
    }

    @Test
    void findReturnsEmptyForUnknownKey() {
        assertThat(store().find(NAME, "missing")).isEmpty();
    }

    @Test
    void cleanByExpiryEvictsExpiredKeepsLive() {
        var store = store();
        store.store(payload("expired", "old", NOW.plus(1, ChronoUnit.MINUTES)));
        store.store(payload("live", "fresh", NOW.plus(2, ChronoUnit.HOURS)));

        store.cleanByExpiry(NOW.plus(30, ChronoUnit.MINUTES));

        assertThat(store.find(NAME, "expired")).isEmpty();
        assertThat(store.find(NAME, "live"))
                .isPresent()
                .hasValueSatisfying(p -> assertThat(p.getPayload()).isEqualTo("fresh"));
    }

    @Test
    void deleteRemovesTheEntry() {
        var store = store();
        store.store(payload("p-1", "value", NOW.plus(1, ChronoUnit.HOURS)));

        assertThatNoException().isThrownBy(() -> store.delete(payload("p-1", "value", NOW.plus(1, ChronoUnit.HOURS))));
        assertThat(store.find(NAME, "p-1")).isEmpty();
    }
}
