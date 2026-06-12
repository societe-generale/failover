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

package com.societegenerale.failover.store.resolver;

import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.store.serializer.JsonSerializer;
import com.societegenerale.failover.store.serializer.Serializer;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
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
    private static final Instant NOW    = Instant.parse("2024-01-15T10:30:00Z");
    private static final Instant EXPIRE = NOW.plusSeconds(86400);

    private static final ObjectMapper OBJECT_MAPPER  = new ObjectMapper();
    private static final Serializer SERIALIZER  = new JsonSerializer(OBJECT_MAPPER);
    private static final VarcharPayloadColumnResolver VARCHAR_HANDLER = new VarcharPayloadColumnResolver();

    @Mock
    private DatabaseResolver databaseResolver;

    // -------------------------------------------------------------------------
    // Shared helpers
    // -------------------------------------------------------------------------

    private FailoverStoreQueryResolver resolver(String tablePrefix) {
        return new DefaultFailoverStoreQueryResolver(tablePrefix, SERIALIZER, databaseResolver, VARCHAR_HANDLER);
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
            var queries = Stream.of(r.getInsertQuery(), r.getUpdateQuery(), r.getSelectQuery(),
                    r.getSelectAllByNameQuery(), r.getDeleteQuery(), r.getCleanUpQuery(), r.getMergeQuery()).toList();
            assertThat(queries)
                    .hasSize(7)
                    .doesNotContainNull()
                    .allSatisfy(q -> {
                        assertThat(q).doesNotContain("%PREFIX%");
                        assertThat(q).contains("TEST_FAILOVER_STORE");
                    });
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
        @DisplayName("selectAllByNameQuery selects all six columns and filters only by FAILOVER_NAME")
        void selectAllByNameQuerySelectsAllColumnsAndFiltersOnlyByName() {
            String q = defaultResolver().getSelectAllByNameQuery();
            assertThat(q)
                    .contains("FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS")
                    .contains("WHERE FAILOVER_NAME = ?")
                    .doesNotContain("FAILOVER_KEY = ?");
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
            var r          = resolver(TABLE_PREFIX, "H2");
            var mergeQuery = r.getMergeQuery();
            var objects    = r.buildInsertMergeParams(payload(new TestPayload("v")));
            assertThat(mergeQuery).isNotNull();
            assertThat(countPlaceholders(mergeQuery))
                    .isEqualTo(objects.length)
                    .isEqualTo(r.buildInsertMergeTypes().length);
        }

        @ParameterizedTest(name = "dbProduct=''{0}'' mergeQuery placeholder count matches buildInsertMergeParams")
        @ValueSource(strings = {"PostgreSQL", "MySQL", "MariaDB", "Oracle"})
        @DisplayName("all known merge dialects placeholder count matches buildInsertMergeParams and buildInsertMergeTypes")
        void allMergeDialectsPlaceholderCountMatchesStoreBuilders(String dbProduct) {
            var r          = resolver(TABLE_PREFIX, dbProduct);
            var mergeQuery = r.getMergeQuery();
            var objects    = r.buildInsertMergeParams(payload(new TestPayload("v")));
            assertThat(mergeQuery).isNotNull();
            assertThat(countPlaceholders(mergeQuery))
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
            assertThat(((Timestamp) obj[2]).toInstant()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("element[3] is EXPIRE_ON as java.sql.Timestamp")
        void element3IsExpireOnTimestamp() {
            var obj = defaultResolver().buildInsertMergeParams(payload(new TestPayload("v")));
            assertThat(obj[3]).isInstanceOf(Timestamp.class);
            assertThat(((Timestamp) obj[3]).toInstant()).isEqualTo(EXPIRE);
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
            assertThat(((Timestamp) obj[0]).toInstant()).isEqualTo(NOW);
        }

        @Test
        @DisplayName("element[1] is EXPIRE_ON as Timestamp — second SET column")
        void element1IsExpireOnTimestamp() {
            var obj = defaultResolver().buildUpdateParams(payload(new TestPayload("v")));
            assertThat(obj[1]).isInstanceOf(Timestamp.class);
            assertThat(((Timestamp) obj[1]).toInstant()).isEqualTo(EXPIRE);
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
    // Test domain class
    // =========================================================================

    @Data
    @AllArgsConstructor
    @NoArgsConstructor
    static class TestPayload {
        private String value;
    }
}
