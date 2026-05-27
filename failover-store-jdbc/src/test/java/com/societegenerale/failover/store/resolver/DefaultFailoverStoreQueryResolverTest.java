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

import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.FailoverStoreException;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.when;

/**
 * Pure unit tests for {@link FailoverStoreQueryResolver} — no Spring context, no JDBC.
 *
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
@DisplayName("DefaultFailoverStoreQueryResolver")
class DefaultFailoverStoreQueryResolverTest {

    private static final String TABLE_PREFIX = "TEST_";
    private static final String NAME         = "my-referential";
    private static final String KEY          = "my-key";
    private static final LocalDateTime NOW    = LocalDateTime.of(2024, 1, 15, 10, 30, 0);
    private static final LocalDateTime EXPIRE = NOW.plusHours(24);

    private static final ObjectMapper            OBJECT_MAPPER  = new JsonMapper();
    private static final VarcharPayloadColumnResolver VARCHAR_HANDLER = new VarcharPayloadColumnResolver();

    /** Only needed by MapRowScenarios; declared here so Mockito initialises it. */
    @Mock
    private ResultSet resultSet;

    @Mock
    private DatabaseResolver databaseResolver;

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private FailoverStoreQueryResolver resolver(String tablePrefix) {
        return new DefaultFailoverStoreQueryResolver(tablePrefix, OBJECT_MAPPER, databaseResolver, VARCHAR_HANDLER);
    }

    private FailoverStoreQueryResolver resolver(String tablePrefix, String dbProduct) {
        when(databaseResolver.resolve()).thenReturn(dbProduct);
        return resolver(tablePrefix);
    }

    private FailoverStoreQueryResolver defaultResolver() {
        return resolver(TABLE_PREFIX, "H2");
    }

    private ReferentialPayload<TestPayload> payload(TestPayload p) {
        return new ReferentialPayload<>(NAME, KEY, false, NOW, EXPIRE, p);
    }

    // =========================================================================
    // 1. Query resolution — prefix substitution
    // =========================================================================

    @Nested
    @DisplayName("Query resolution — table prefix substitution")
    class QueryResolutionScenarios {

        @Test
        @DisplayName("all queries substitute the prefix and leave no %PREFIX% placeholder")
        void allQueriesSubstitutePrefixAndHaveNoPlaceholder() {
            var r = defaultResolver();
            Stream.of(r.getInsertQuery(), r.getUpdateQuery(), r.getSelectQuery(),
                            r.getDeleteQuery(), r.getCleanUpQuery(), r.getMergeQuery())
                    .forEach(q -> assertThat(q)
                            .doesNotContain("%PREFIX%")
                            .contains("TEST_FAILOVER_STORE"));
        }

        @Test
        @DisplayName("empty prefix produces bare FAILOVER_STORE table name")
        void emptyPrefixProducesBareTableName() {
            var r = resolver("", "H2");
            assertThat(r.getInsertQuery())
                    .contains("FAILOVER_STORE")
                    .doesNotContain("TEST_FAILOVER_STORE");
        }

        @Test
        @DisplayName("schema-qualified prefix (e.g. SCHEMA.) is substituted correctly")
        void schemaQualifiedPrefixIsSubstituted() {
            var r = resolver("SCHEMA.", "H2");
            assertThat(r.getInsertQuery()).contains("SCHEMA.FAILOVER_STORE");
            assertThat(r.getMergeQuery()).contains("SCHEMA.FAILOVER_STORE");
        }
    }

    // =========================================================================
    // 2. SQL content correctness
    // =========================================================================

    @Nested
    @DisplayName("SQL content correctness")
    class SqlContentScenarios {

        @Test
        @DisplayName("insertQuery column order: FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS")
        void insertQueryColumnOrder() {
            assertThat(defaultResolver().getInsertQuery())
                    .contains("FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS");
        }

        @Test
        @DisplayName("updateQuery has SET clause followed by WHERE on name and key")
        void updateQuerySetBeforeWhere() {
            String q = defaultResolver().getUpdateQuery();
            int setIdx   = q.indexOf("SET AS_OF");
            int whereIdx = q.indexOf("WHERE FAILOVER_NAME");
            assertThat(setIdx).isGreaterThanOrEqualTo(0);
            assertThat(whereIdx).isGreaterThan(setIdx);
        }

        @Test
        @DisplayName("selectQuery selects all six columns")
        void selectQuerySelectsAllColumns() {
            assertThat(defaultResolver().getSelectQuery())
                    .contains("FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS");
        }

        @Test
        @DisplayName("deleteQuery filters by FAILOVER_NAME and FAILOVER_KEY")
        void deleteQueryPredicateOnNameAndKey() {
            assertThat(defaultResolver().getDeleteQuery())
                    .contains("WHERE FAILOVER_NAME = ? AND FAILOVER_KEY = ?");
        }

        @Test
        @DisplayName("cleanUpQuery deletes by EXPIRE_ON threshold")
        void cleanUpQueryPredicateOnExpireOn() {
            assertThat(defaultResolver().getCleanUpQuery()).contains("EXPIRE_ON < ?");
        }

        @Test
        @DisplayName("H2 mergeQuery uses MERGE INTO ... KEY(...) syntax")
        void h2MergeQueryKeyClause() {
            assertThat(resolver(TABLE_PREFIX, "H2").getMergeQuery())
                    .startsWith("MERGE INTO")
                    .contains("KEY (FAILOVER_NAME, FAILOVER_KEY)");
        }

        @Test
        @DisplayName("PostgreSQL mergeQuery uses INSERT ... ON CONFLICT DO UPDATE")
        void postgresqlMergeQueryOnConflict() {
            assertThat(resolver(TABLE_PREFIX, "PostgreSQL").getMergeQuery())
                    .contains("ON CONFLICT (FAILOVER_NAME, FAILOVER_KEY)")
                    .contains("DO UPDATE SET");
        }

        @Test
        @DisplayName("MySQL mergeQuery uses INSERT ... ON DUPLICATE KEY UPDATE")
        void mysqlMergeQueryOnDuplicateKey() {
            assertThat(resolver(TABLE_PREFIX, "MySQL").getMergeQuery())
                    .contains("ON DUPLICATE KEY UPDATE");
        }

        @Test
        @DisplayName("Oracle mergeQuery uses MERGE INTO ... USING ... ON DUAL with MATCHED branches")
        void oracleMergeQueryUsingDual() {
            assertThat(resolver(TABLE_PREFIX, "Oracle").getMergeQuery())
                    .contains("USING")
                    .contains("DUAL")
                    .contains("WHEN MATCHED THEN UPDATE")
                    .contains("WHEN NOT MATCHED THEN INSERT");
        }
    }

    // =========================================================================
    // 3. Placeholder count consistency — SQL ↔ param array ↔ type array
    // =========================================================================

    @Nested
    @DisplayName("Placeholder count consistency — SQL, param array, and type array must agree")
    class PlaceholderCountConsistencyScenarios {

        private int countPlaceholders(String sql) {
            return (int) sql.chars().filter(c -> c == '?').count();
        }

        @Test
        @DisplayName("insertQuery placeholder count matches buildInsertMergeParams array length and buildInsertMergeTypes length")
        void insertQueryPlaceholderCountMatchesStoreBuilders() {
            var r       = defaultResolver();
            var objects = r.buildInsertMergeParams(payload(new TestPayload("v")));
            assertThat(countPlaceholders(r.getInsertQuery()))
                    .isEqualTo(objects.length)
                    .isEqualTo(r.buildInsertMergeTypes().length);
        }

        @Test
        @DisplayName("updateQuery placeholder count matches buildUpdateParams array length and buildUpdateTypes length")
        void updateQueryPlaceholderCountMatchesUpdateBuilders() {
            var r       = defaultResolver();
            var objects = r.buildUpdateParams(payload(new TestPayload("v")));
            assertThat(countPlaceholders(r.getUpdateQuery()))
                    .isEqualTo(objects.length)
                    .isEqualTo(r.buildUpdateTypes().length);
        }

        @Test
        @DisplayName("H2 mergeQuery placeholder count matches buildInsertMergeParams and buildInsertMergeTypes")
        void h2MergeQueryPlaceholderCountMatchesStoreBuilders() {
            var r       = resolver(TABLE_PREFIX, "H2");
            assertThat(r).isNotNull();
            var objects = r.buildInsertMergeParams(payload(new TestPayload("v")));
            assertThat(countPlaceholders(r.getMergeQuery()))
                    .isEqualTo(objects.length)
                    .isEqualTo(r.buildInsertMergeTypes().length);
        }

        @ParameterizedTest(name = "dbProduct=''{0}'' mergeQuery placeholder count matches buildInsertMergeParams")
        @ValueSource(strings = {"PostgreSQL", "MySQL", "MariaDB", "Oracle"})
        @DisplayName("all known merge dialects placeholder count matches buildInsertMergeParams and buildInsertMergeTypes")
        void allMergeDialectsPlaceholderCountMatchesStoreBuilders(String dbProduct) {
            var r       = resolver(TABLE_PREFIX, dbProduct);
            var objects = r.buildInsertMergeParams(payload(new TestPayload("v")));
            assertThat(countPlaceholders(r.getMergeQuery()))
                    .isEqualTo(objects.length)
                    .isEqualTo(r.buildInsertMergeTypes().length);
        }
    }

    // =========================================================================
    // 4. Merge dialect detection
    // =========================================================================

    @Nested
    @DisplayName("Merge dialect detection")
    class MergeDialectDetectionScenarios {

        @Test
        @DisplayName("null dbProduct → mergeQuery is null (INSERT/UPDATE fallback)")
        void nullDbProductProducesNullMergeQuery() {
            assertThat(resolver(TABLE_PREFIX, null).getMergeQuery()).isNull();
        }

        @ParameterizedTest(name = "dbProduct=''{0}''")
        @ValueSource(strings = {"H2", "h2", "H2 2.2.220", "H2 Version 2"})
        @DisplayName("H2 variants select H2 native MERGE dialect")
        void h2VariantsSelectH2Dialect(String dbProduct) {
            assertThat(resolver(TABLE_PREFIX, dbProduct).getMergeQuery())
                    .isNotNull()
                    .contains("MERGE INTO")
                    .contains("KEY (FAILOVER_NAME, FAILOVER_KEY)");
        }

        @ParameterizedTest(name = "dbProduct=''{0}''")
        @ValueSource(strings = {"PostgreSQL", "postgresql", "PostgreSQL 14.5", "postgres", "POSTGRES"})
        @DisplayName("PostgreSQL variants select ON CONFLICT dialect")
        void postgresqlVariantsSelectOnConflictDialect(String dbProduct) {
            assertThat(resolver(TABLE_PREFIX, dbProduct).getMergeQuery())
                    .isNotNull()
                    .contains("ON CONFLICT");
        }

        @ParameterizedTest(name = "dbProduct=''{0}''")
        @ValueSource(strings = {"MySQL", "mysql", "MySQL 8.0.33", "MariaDB", "mariadb", "MariaDB 10.11"})
        @DisplayName("MySQL and MariaDB variants select ON DUPLICATE KEY dialect")
        void mysqlAndMariaDbVariantsSelectOnDuplicateKeyDialect(String dbProduct) {
            assertThat(resolver(TABLE_PREFIX, dbProduct).getMergeQuery())
                    .isNotNull()
                    .contains("ON DUPLICATE KEY UPDATE");
        }

        @ParameterizedTest(name = "dbProduct=''{0}''")
        @ValueSource(strings = {"Oracle", "oracle", "Oracle Database 19c", "ORACLE DATABASE 21C"})
        @DisplayName("Oracle variants select MERGE USING DUAL dialect")
        void oracleVariantsSelectMergeUsingDualDialect(String dbProduct) {
            assertThat(resolver(TABLE_PREFIX, dbProduct).getMergeQuery())
                    .isNotNull()
                    .contains("DUAL");
        }

        @ParameterizedTest(name = "dbProduct=''{0}''")
        @ValueSource(strings = {"Microsoft SQL Server", "DB2", "SQLite", "HSQLDB", "SAP HANA", ""})
        @DisplayName("unknown database products return null mergeQuery (INSERT/UPDATE fallback)")
        void unknownDbProductReturnsNullMergeQuery(String dbProduct) {
            assertThat(resolver(TABLE_PREFIX, dbProduct).getMergeQuery()).isNull();
        }
    }

    // =========================================================================
    // 5. buildInsertMergeParams and buildInsertMergeTypes (INSERT / MERGE param order)
    // =========================================================================

    @Nested
    @DisplayName("buildInsertMergeParams and buildInsertMergeTypes — INSERT / MERGE param order")
    class BuildStoreObjectsScenarios {

        @Test
        @DisplayName("buildInsertMergeParams returns array of length 6")
        void returnsArrayOfSixElements() {
            assertThat(defaultResolver().buildInsertMergeParams(payload(new TestPayload("v")))).hasSize(6);
        }

        @Test
        @DisplayName("element[0] is FAILOVER_NAME (String)")
        void element0IsName() {
            var obj = defaultResolver().buildInsertMergeParams(payload(new TestPayload("v")));
            assertThat(obj[0]).isEqualTo(NAME);
        }

        @Test
        @DisplayName("element[1] is FAILOVER_KEY (String)")
        void element1IsKey() {
            var obj = defaultResolver().buildInsertMergeParams(payload(new TestPayload("v")));
            assertThat(obj[1]).isEqualTo(KEY);
        }

        @Test
        @DisplayName("element[2] is AS_OF as java.sql.Timestamp")
        void element2IsAsOfTimestamp() {
            var obj = defaultResolver().buildInsertMergeParams(payload(new TestPayload("v")));
            assertThat(obj[2]).isInstanceOf(Timestamp.class);
            assertThat(((Timestamp) obj[2]).toLocalDateTime()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("element[3] is EXPIRE_ON as java.sql.Timestamp")
        void element3IsExpireOnTimestamp() {
            var obj = defaultResolver().buildInsertMergeParams(payload(new TestPayload("v")));
            assertThat(obj[3]).isInstanceOf(Timestamp.class);
            assertThat(((Timestamp) obj[3]).toLocalDateTime()).isEqualTo(EXPIRE);
        }

        @Test
        @DisplayName("element[4] is serialized payload JSON string")
        void element4IsSerializedPayloadJson() {
            var p   = new TestPayload("hello-world");
            var obj = defaultResolver().buildInsertMergeParams(payload(p));
            assertThat(obj[4]).isEqualTo(OBJECT_MAPPER.writeValueAsString(p));
        }

        @Test
        @DisplayName("element[5] is fully-qualified payload class name")
        void element5IsFullyQualifiedPayloadClassName() {
            var obj = defaultResolver().buildInsertMergeParams(payload(new TestPayload("v")));
            assertThat(obj[5]).isEqualTo(TestPayload.class.getName());
        }

        @Test
        @DisplayName("null payload → element[4] (PAYLOAD) is null")
        void nullPayloadProducesNullPayloadElement() {
            var obj = defaultResolver().buildInsertMergeParams(payload(null));
            assertThat(obj[4]).isNull();
        }

        @Test
        @DisplayName("null payload → element[5] (PAYLOAD_CLASS) is null")
        void nullPayloadProducesNullPayloadClassElement() {
            var obj = defaultResolver().buildInsertMergeParams(payload(null));
            assertThat(obj[5]).isNull();
        }

        @Test
        @DisplayName("null payload → name, key, and timestamps are still correctly set")
        void nullPayloadDoesNotAffectIdentityOrTimestamps() {
            var obj = defaultResolver().buildInsertMergeParams(payload(null));
            assertThat(obj[0]).isEqualTo(NAME);
            assertThat(obj[1]).isEqualTo(KEY);
            assertThat(obj[2]).isInstanceOf(Timestamp.class);
            assertThat(obj[3]).isInstanceOf(Timestamp.class);
        }

        @Test
        @DisplayName("buildInsertMergeTypes returns 6 types in order: VARCHAR, VARCHAR, TIMESTAMP, TIMESTAMP, VARCHAR, VARCHAR")
        void buildInsertMergeTypesOrder() {
            int[] types = defaultResolver().buildInsertMergeTypes();
            assertThat(types).hasSize(6);
            assertThat(types[0]).isEqualTo(Types.VARCHAR);   // FAILOVER_NAME
            assertThat(types[1]).isEqualTo(Types.VARCHAR);   // FAILOVER_KEY
            assertThat(types[2]).isEqualTo(Types.TIMESTAMP); // AS_OF
            assertThat(types[3]).isEqualTo(Types.TIMESTAMP); // EXPIRE_ON
            assertThat(types[4]).isEqualTo(Types.VARCHAR);   // PAYLOAD (VarcharPayloadColumnHandler)
            assertThat(types[5]).isEqualTo(Types.VARCHAR);   // PAYLOAD_CLASS
        }
    }

    // =========================================================================
    // 6. buildUpdateParams and buildUpdateTypes (UPDATE param order)
    // =========================================================================

    @Nested
    @DisplayName("buildUpdateParams and buildUpdateTypes — UPDATE param order (SET first, WHERE last)")
    class BuildUpdateObjectsScenarios {

        @Test
        @DisplayName("buildUpdateParams returns array of length 6")
        void returnsArrayOfSixElements() {
            assertThat(defaultResolver().buildUpdateParams(payload(new TestPayload("v")))).hasSize(6);
        }

        @Test
        @DisplayName("element[0] is AS_OF as Timestamp — first SET column")
        void element0IsAsOfTimestamp() {
            var obj = defaultResolver().buildUpdateParams(payload(new TestPayload("v")));
            assertThat(obj[0]).isInstanceOf(Timestamp.class);
            assertThat(((Timestamp) obj[0]).toLocalDateTime()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("element[1] is EXPIRE_ON as Timestamp — second SET column")
        void element1IsExpireOnTimestamp() {
            var obj = defaultResolver().buildUpdateParams(payload(new TestPayload("v")));
            assertThat(obj[1]).isInstanceOf(Timestamp.class);
            assertThat(((Timestamp) obj[1]).toLocalDateTime()).isEqualTo(EXPIRE);
        }

        @Test
        @DisplayName("element[2] is serialized payload JSON — third SET column")
        void element2IsSerializedPayloadJson() {
            var p   = new TestPayload("update-value");
            var obj = defaultResolver().buildUpdateParams(payload(p));
            assertThat(obj[2]).isEqualTo(OBJECT_MAPPER.writeValueAsString(p));
        }

        @Test
        @DisplayName("element[3] is fully-qualified payload class name — fourth SET column")
        void element3IsPayloadClassName() {
            var obj = defaultResolver().buildUpdateParams(payload(new TestPayload("v")));
            assertThat(obj[3]).isEqualTo(TestPayload.class.getName());
        }

        @Test
        @DisplayName("element[4] is FAILOVER_NAME — first WHERE column")
        void element4IsName() {
            var obj = defaultResolver().buildUpdateParams(payload(new TestPayload("v")));
            assertThat(obj[4]).isEqualTo(NAME);
        }

        @Test
        @DisplayName("element[5] is FAILOVER_KEY — second WHERE column")
        void element5IsKey() {
            var obj = defaultResolver().buildUpdateParams(payload(new TestPayload("v")));
            assertThat(obj[5]).isEqualTo(KEY);
        }

        @Test
        @DisplayName("null payload → element[2] (PAYLOAD) and element[3] (PAYLOAD_CLASS) are null")
        void nullPayloadProducesNullPayloadAndClass() {
            var obj = defaultResolver().buildUpdateParams(payload(null));
            assertThat(obj[2]).isNull();
            assertThat(obj[3]).isNull();
        }

        @Test
        @DisplayName("null payload → name, key, and timestamps remain correctly set")
        void nullPayloadDoesNotAffectIdentityOrTimestamps() {
            var obj = defaultResolver().buildUpdateParams(payload(null));
            assertThat(obj[0]).isInstanceOf(Timestamp.class);
            assertThat(obj[1]).isInstanceOf(Timestamp.class);
            assertThat(obj[4]).isEqualTo(NAME);
            assertThat(obj[5]).isEqualTo(KEY);
        }

        @Test
        @DisplayName("buildUpdateTypes returns 6 types in order: TIMESTAMP, TIMESTAMP, VARCHAR, VARCHAR, VARCHAR, VARCHAR")
        void buildUpdateTypesOrder() {
            int[] types = defaultResolver().buildUpdateTypes();
            assertThat(types).hasSize(6);
            assertThat(types[0]).isEqualTo(Types.TIMESTAMP); // AS_OF
            assertThat(types[1]).isEqualTo(Types.TIMESTAMP); // EXPIRE_ON
            assertThat(types[2]).isEqualTo(Types.VARCHAR);   // PAYLOAD (VarcharPayloadColumnHandler)
            assertThat(types[3]).isEqualTo(Types.VARCHAR);   // PAYLOAD_CLASS
            assertThat(types[4]).isEqualTo(Types.VARCHAR);   // FAILOVER_NAME
            assertThat(types[5]).isEqualTo(Types.VARCHAR);   // FAILOVER_KEY
        }
    }

    // =========================================================================
    // 7. mapRow — ResultSet → ReferentialPayload mapping
    // =========================================================================

    @Nested
    @DisplayName("mapRow — ResultSet to ReferentialPayload mapping")
    class MapRowScenarios {

        private void stubRow(LocalDateTime asOf, LocalDateTime expireOn, String payloadJson, String payloadClass)
                throws SQLException {
            when(resultSet.getString("FAILOVER_NAME")).thenReturn(NAME);
            when(resultSet.getString("FAILOVER_KEY")).thenReturn(KEY);
            when(resultSet.getTimestamp("AS_OF")).thenReturn(Timestamp.valueOf(asOf));
            when(resultSet.getTimestamp("EXPIRE_ON")).thenReturn(Timestamp.valueOf(expireOn));
            when(resultSet.getString("PAYLOAD_CLASS")).thenReturn(payloadClass);
            when(resultSet.getString("PAYLOAD")).thenReturn(payloadJson);
        }

        @Test
        @DisplayName("should map all columns correctly to ReferentialPayload fields")
        void mapsAllColumnsToReferentialPayload() throws SQLException {
            var expected = new TestPayload("mapped-value");
            stubRow(NOW, EXPIRE, OBJECT_MAPPER.writeValueAsString(expected), TestPayload.class.getName());

            ReferentialPayload<TestPayload> result = defaultResolver().mapRow(resultSet);

            assertThat(result.getName()).isEqualTo(NAME);
            assertThat(result.getKey()).isEqualTo(KEY);
            assertThat(result.getAsOf()).isEqualTo(NOW);
            assertThat(result.getExpireOn()).isEqualTo(EXPIRE);
            assertThat(result.getPayload()).isEqualTo(expected);
        }

        @Test
        @DisplayName("upToDate is always false — payloads served from store are never live")
        void upToDateIsAlwaysFalse() throws SQLException {
            stubRow(NOW, EXPIRE,
                    OBJECT_MAPPER.writeValueAsString(new TestPayload("x")),
                    TestPayload.class.getName());

            ReferentialPayload<TestPayload> result = defaultResolver().mapRow(resultSet);

            assertThat(result.isUpToDate()).isFalse();
        }

        @Test
        @DisplayName("null PAYLOAD column produces a null payload field in ReferentialPayload")
        void nullPayloadColumnProducesNullPayloadField() throws SQLException {
            stubRow(NOW, EXPIRE, null, TestPayload.class.getName());

            ReferentialPayload<TestPayload> result = defaultResolver().mapRow(resultSet);

            assertThat(result.getPayload()).isNull();
            assertThat(result.getName()).isEqualTo(NAME);
            assertThat(result.getKey()).isEqualTo(KEY);
        }

        @Test
        @DisplayName("asOf and expireOn timestamps are converted from SQL Timestamp to LocalDateTime")
        void timestampColumnsAreConvertedToLocalDateTime() throws SQLException {
            var customAsOf    = LocalDateTime.of(2023, 6, 1, 8, 0);
            var customExpireOn = customAsOf.plusDays(30);
            stubRow(customAsOf, customExpireOn,
                    OBJECT_MAPPER.writeValueAsString(new TestPayload("ts")),
                    TestPayload.class.getName());

            ReferentialPayload<TestPayload> result = defaultResolver().mapRow(resultSet);

            assertThat(result.getAsOf()).isEqualTo(customAsOf);
            assertThat(result.getExpireOn()).isEqualTo(customExpireOn);
        }
    }

    // =========================================================================
    // 8. deserializePayload — JSON deserialization edge cases
    // =========================================================================

    @Nested
    @DisplayName("deserializePayload — JSON deserialization edge cases")
    class DeserializePayloadScenarios {

        private FailoverStoreQueryResolver resolver;

        @BeforeEach
        void setUp() {
            resolver = defaultResolver();
        }

        @Test
        @DisplayName("null payload string → returns null without inspecting class name")
        void nullPayloadStringReturnsNull() {
            assertThat(resolver.<TestPayload>deserializePayload(null, TestPayload.class.getName())).isNull();
        }

        @Test
        @DisplayName("null payload with null class name → returns null (null guard fires before class lookup)")
        void nullPayloadWithNullClassNameReturnsNull() {
            assertThat(resolver.<TestPayload>deserializePayload(null, null)).isNull();
        }

        @Test
        @DisplayName("valid JSON and valid class → returns deserialized object equal to original")
        void validJsonAndValidClassDeserializesCorrectly() {
            var expected = new TestPayload("round-trip-value");
            String json  = OBJECT_MAPPER.writeValueAsString(expected);

            TestPayload result = resolver.deserializePayload(json, TestPayload.class.getName());

            assertThat(result).isEqualTo(expected);
        }

        @Test
        @DisplayName("valid JSON with String class → deserializes plain string")
        void validJsonWithStringClassDeserializesString() {
            String result = resolver.deserializePayload("\"hello\"", String.class.getName());
            assertThat(result).isEqualTo("hello");
        }

        @Test
        @DisplayName("non-existent class name → throws FailoverStoreException wrapping ClassNotFoundException")
        void nonExistentClassNameThrowsFailoverStoreException() {
            String json       = OBJECT_MAPPER.writeValueAsString(new TestPayload("x"));
            String ghostClass = "com.example.ghost.NonExistentClass";

            assertThatThrownBy(() -> resolver.deserializePayload(json, ghostClass))
                    .isInstanceOf(FailoverStoreException.class)
                    .hasMessage("Failed to resolve payload class '%s'".formatted(ghostClass))
                    .hasCauseInstanceOf(ClassNotFoundException.class);
        }

        @Test
        @DisplayName("FailoverStoreException message contains exact formatted class name")
        void failoverStoreExceptionMessageContainsExactClassName() {
            String json       = "{\"value\":\"x\"}";
            String ghostClass = "com.missing.Ghost";

            assertThatThrownBy(() -> resolver.deserializePayload(json, ghostClass))
                    .isInstanceOf(FailoverStoreException.class)
                    .hasMessage("Failed to resolve payload class 'com.missing.Ghost'");
        }
    }

    // =========================================================================
    // Test domain class
    // =========================================================================

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class TestPayload {
        private String value;
    }
}
