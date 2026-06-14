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

package com.societegenerale.failover.store;

import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.store.resolver.FailoverStoreQueryResolver;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.SQLException;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Mock-based unit tests for {@link FailoverStoreJdbc} store paths that the H2-backed integration
 * test cannot exercise: the {@code BadSqlGrammarException} permanent merge-fallback, and the
 * INSERT → {@code DuplicateKeyException} → UPDATE branch including the 0-row (concurrent-delete) case.
 *
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class FailoverStoreJdbcMergeFallbackTest {

    private static final String MERGE_SQL  = "MERGE_SQL";
    private static final String INSERT_SQL = "INSERT_SQL";
    private static final String UPDATE_SQL = "UPDATE_SQL";

    @Mock
    private JdbcTemplate jdbcTemplate;

    @Mock
    private FailoverStoreQueryResolver queryResolver;

    @Mock
    private RowMapper<ReferentialPayload<String>> rowMapper;

    private final ReferentialPayload<String> payload =
            new ReferentialPayload<>("country", "FR", false, Instant.now(), Instant.now().plusSeconds(3600), "France");

    @BeforeEach
    void setUp() {
        given(queryResolver.getInsertQuery()).willReturn(INSERT_SQL);
        given(queryResolver.getUpdateQuery()).willReturn(UPDATE_SQL);
        given(queryResolver.buildInsertMergeParams(any())).willReturn(new Object[]{});
        given(queryResolver.buildInsertMergeTypes()).willReturn(new int[]{});
        given(queryResolver.buildUpdateParams(any())).willReturn(new Object[]{});
        given(queryResolver.buildUpdateTypes()).willReturn(new int[]{});
    }

    private FailoverStoreJdbc<String> storeWithMerge(String mergeQuery) {
        given(queryResolver.getMergeQuery()).willReturn(mergeQuery);
        return new FailoverStoreJdbc<>(jdbcTemplate, queryResolver, rowMapper);
    }

    // ── Merge path ──────────────────────────────────────────────────────────────

    @Test
    @DisplayName("merge enabled — uses the merge query and does not fall back to INSERT/UPDATE")
    void mergeEnabledUsesMergeQuery() {
        given(jdbcTemplate.update(eq(MERGE_SQL), any(Object[].class), any(int[].class))).willReturn(1);
        FailoverStoreJdbc<String> store = storeWithMerge(MERGE_SQL);

        store.store(payload);

        verify(jdbcTemplate).update(eq(MERGE_SQL), any(Object[].class), any(int[].class));
        verify(jdbcTemplate, never()).update(eq(INSERT_SQL), any(Object[].class), any(int[].class));
    }

    @Test
    @DisplayName("merge throws BadSqlGrammarException — disables merge permanently and falls back to INSERT/UPDATE")
    void badSqlGrammarDisablesMergePermanently() {
        given(jdbcTemplate.update(eq(MERGE_SQL), any(Object[].class), any(int[].class)))
                .willThrow(new BadSqlGrammarException("store", MERGE_SQL, new SQLException("unsupported")));
        given(jdbcTemplate.update(eq(INSERT_SQL), any(Object[].class), any(int[].class))).willReturn(1);
        FailoverStoreJdbc<String> store = storeWithMerge(MERGE_SQL);

        store.store(payload);   // first call: merge fails → fallback
        store.store(payload);   // second call: merge must NOT be attempted again

        verify(jdbcTemplate, times(1)).update(eq(MERGE_SQL), any(Object[].class), any(int[].class));
        verify(jdbcTemplate, times(2)).update(eq(INSERT_SQL), any(Object[].class), any(int[].class));
    }

    // ── INSERT/UPDATE fallback (no merge dialect) ────────────────────────────────

    @Test
    @DisplayName("no merge dialect — INSERT succeeds, UPDATE is not attempted")
    void insertSucceedsNoUpdate() {
        given(jdbcTemplate.update(eq(INSERT_SQL), any(Object[].class), any(int[].class))).willReturn(1);
        FailoverStoreJdbc<String> store = storeWithMerge(null);

        store.store(payload);

        verify(jdbcTemplate).update(eq(INSERT_SQL), any(Object[].class), any(int[].class));
        verify(jdbcTemplate, never()).update(eq(UPDATE_SQL), any(Object[].class), any(int[].class));
    }

    @Test
    @DisplayName("INSERT hits DuplicateKeyException — falls back to UPDATE (1 row updated)")
    void duplicateKeyFallsBackToUpdate() {
        given(jdbcTemplate.update(eq(INSERT_SQL), any(Object[].class), any(int[].class)))
                .willThrow(new DuplicateKeyException("exists"));
        given(jdbcTemplate.update(eq(UPDATE_SQL), any(Object[].class), any(int[].class))).willReturn(1);
        FailoverStoreJdbc<String> store = storeWithMerge(null);

        store.store(payload);

        verify(jdbcTemplate).update(eq(UPDATE_SQL), any(Object[].class), any(int[].class));
    }

    @Test
    @DisplayName("UPDATE affects 0 rows (concurrent delete) — write is dropped silently, no exception")
    void updateZeroRowsDoesNotThrow() {
        given(jdbcTemplate.update(eq(INSERT_SQL), any(Object[].class), any(int[].class)))
                .willThrow(new DuplicateKeyException("exists"));
        given(jdbcTemplate.update(eq(UPDATE_SQL), any(Object[].class), any(int[].class))).willReturn(0);
        FailoverStoreJdbc<String> store = storeWithMerge(null);

        assertThatNoException().isThrownBy(() -> store.store(payload));

        verify(jdbcTemplate).update(eq(UPDATE_SQL), any(Object[].class), any(int[].class));
    }
}