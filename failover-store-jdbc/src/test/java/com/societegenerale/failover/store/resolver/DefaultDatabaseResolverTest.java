/*
 * Copyright 2022-2023, Société Générale All rights reserved.
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

package com.societegenerale.failover.store.resolver;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.ConnectionCallback;
import org.springframework.jdbc.core.JdbcTemplate;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.SQLException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link DefaultDatabaseResolver} — no Spring context, no real JDBC.
 *
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultDatabaseResolver")
class DefaultDatabaseResolverTest {

    @Mock private JdbcTemplate jdbcTemplate;
    @Mock private Connection   connection;
    @Mock private DatabaseMetaData metaData;

    private DefaultDatabaseResolver resolver;

    @BeforeEach
    void setUp() {
        resolver = new DefaultDatabaseResolver(jdbcTemplate);
    }

    // =========================================================================
    // Helpers
    // =========================================================================

    @SuppressWarnings("unchecked")
    private void stubProductName(String productName) throws SQLException {
        when(metaData.getDatabaseProductName()).thenReturn(productName);
        when(connection.getMetaData()).thenReturn(metaData);
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(inv -> {
            ConnectionCallback<String> cb = inv.getArgument(0);
            return cb.doInConnection(connection);
        });
    }

    // =========================================================================
    // 1. Happy path — product name returned from metadata
    // =========================================================================

    @Nested
    @DisplayName("Happy path — database product name resolved from metadata")
    class HappyPathScenarios {

        @ParameterizedTest(name = "dbProduct=''{0}''")
        @ValueSource(strings = {"H2", "PostgreSQL", "MySQL", "MariaDB", "Oracle", "Microsoft SQL Server"})
        @DisplayName("returns exact product name string for known databases")
        void returnsExactProductNameForKnownDatabases(String productName) throws SQLException {
            stubProductName(productName);
            assertThat(resolver.resolve()).isEqualTo(productName);
        }

        @Test
        @DisplayName("returns empty string when database metadata reports empty product name")
        void returnsEmptyStringWhenMetadataReportsEmpty() throws SQLException {
            stubProductName("");
            assertThat(resolver.resolve()).isEqualTo("");
        }

        @Test
        @DisplayName("delegates to jdbcTemplate.execute(ConnectionCallback) exactly once per call")
        void delegatesToJdbcTemplateExecuteOnce() throws SQLException {
            stubProductName("H2");
            resolver.resolve();
            verify(jdbcTemplate).execute(any(ConnectionCallback.class));
        }

        @Test
        @DisplayName("calls getDatabaseProductName() on connection metadata")
        void callsGetDatabaseProductNameOnMetadata() throws SQLException {
            stubProductName("PostgreSQL");
            resolver.resolve();
            verify(metaData).getDatabaseProductName();
        }
    }

    // =========================================================================
    // 2. Fallback — exceptions produce null
    // =========================================================================

    @Nested
    @DisplayName("Fallback — any exception during metadata lookup returns null")
    class FallbackScenarios {

        @Test
        @DisplayName("returns null when JdbcTemplate throws DataAccessException")
        @SuppressWarnings("unchecked")
        void returnsNullOnDataAccessException() {
            when(jdbcTemplate.execute(any(ConnectionCallback.class)))
                    .thenThrow(new DataAccessResourceFailureException("connection refused"));
            assertThat(resolver.resolve()).isNull();
        }

        @Test
        @DisplayName("returns null when JdbcTemplate throws RuntimeException")
        @SuppressWarnings("unchecked")
        void returnsNullOnRuntimeException() {
            when(jdbcTemplate.execute(any(ConnectionCallback.class)))
                    .thenThrow(new RuntimeException("unexpected"));
            assertThat(resolver.resolve()).isNull();
        }

        @Test
        @DisplayName("returns null when getDatabaseProductName() throws SQLException")
        @SuppressWarnings("unchecked")
        void returnsNullWhenGetDatabaseProductNameThrowsSQLException() throws SQLException {
            when(connection.getMetaData()).thenReturn(metaData);
            when(metaData.getDatabaseProductName()).thenThrow(new SQLException("metadata unavailable"));
            when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenAnswer(inv -> {
                ConnectionCallback<String> cb = inv.getArgument(0);
                return cb.doInConnection(connection);
            });
            assertThat(resolver.resolve()).isNull();
        }

    }

    // =========================================================================
    // 3. Multiple calls — each call reads fresh metadata
    // =========================================================================

    @Nested
    @DisplayName("Multiple calls — each resolve() queries metadata independently")
    class MultipleCallScenarios {

        @Test
        @DisplayName("returns consistent result across multiple invocations")
        void returnsConsistentResultAcrossMultipleCalls() throws SQLException {
            stubProductName("Oracle");
            assertThat(resolver.resolve()).isEqualTo("Oracle");
        }

        @Test
        @DisplayName("returns null on first call (exception), product name on second (success)")
        @SuppressWarnings("unchecked")
        void returnsNullThenProductNameOnSubsequentCalls() throws SQLException {
            when(jdbcTemplate.execute(any(ConnectionCallback.class)))
                    .thenThrow(new RuntimeException("transient failure"))
                    .thenAnswer(inv -> {
                        ConnectionCallback<String> cb = inv.getArgument(0);
                        return cb.doInConnection(connection);
                    });
            when(connection.getMetaData()).thenReturn(metaData);
            when(metaData.getDatabaseProductName()).thenReturn("MySQL");

            assertThat(resolver.resolve()).isNull();
            assertThat(resolver.resolve()).isEqualTo("MySQL");
        }
    }
}
