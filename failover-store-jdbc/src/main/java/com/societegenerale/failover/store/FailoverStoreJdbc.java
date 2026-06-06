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

package com.societegenerale.failover.store;

import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.store.resolver.FailoverStoreQueryResolver;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.jdbc.BadSqlGrammarException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * JDBC-backed {@link FailoverStore} implementation.
 *
 * <p>Persistence strategy for {@link #store}:
 * <ol>
 *   <li>If the detected database provides a native merge/upsert dialect (H2, PostgreSQL,
 *       MySQL/MariaDB, Oracle), a single merge statement is attempted first.</li>
 *   <li>If the merge SQL fails with a {@link org.springframework.jdbc.BadSqlGrammarException}
 *       (unsupported dialect), merge is disabled permanently for this instance and all
 *       subsequent stores fall back to an INSERT + UPDATE-on-duplicate pattern.</li>
 *   <li>When no merge query is available at construction time, the INSERT/UPDATE fallback
 *       is used from the start.</li>
 * </ol>
 *
 * @param <T> the type of the business payload held by each {@link ReferentialPayload}
 * @author Anand Manissery
 * @see FailoverStore
 */
@Slf4j
public class FailoverStoreJdbc<T> implements FailoverStore<T> {

    private final JdbcTemplate jdbcTemplate;

    private final FailoverStoreQueryResolver queryResolver;

    private final RowMapper<ReferentialPayload<T>> rowMapper;

    /** Merge/upsert SQL resolved once at construction; {@code null} when no dialect is available. */
    @Getter
    private final String mergeQuery;

    /** Flipped to false at runtime if the merge SQL fails with a grammar error. */
    private final AtomicBoolean mergeEnabled;

    /**
     * Constructs the store and resolves the merge query at construction time.
     *
     * @param jdbcTemplate               the JDBC template used for all SQL operations
     * @param failoverStoreQueryResolver provides SQL strings, parameter arrays, and type arrays
     * @param rowMapper                  maps {@code FAILOVER_STORE} rows to {@link ReferentialPayload}
     */
    public FailoverStoreJdbc(JdbcTemplate jdbcTemplate, FailoverStoreQueryResolver failoverStoreQueryResolver, RowMapper<ReferentialPayload<T>> rowMapper) {
        this.jdbcTemplate  = jdbcTemplate;
        this.queryResolver = failoverStoreQueryResolver;
        this.rowMapper = rowMapper;
        this.mergeQuery = queryResolver.getMergeQuery();
        this.mergeEnabled  = new AtomicBoolean(this.mergeQuery != null);
    }

    /**
     * Persists or updates the payload.
     *
     * <p>Attempts a native merge/upsert if available. On {@link org.springframework.jdbc.BadSqlGrammarException},
     * merge is disabled permanently and the INSERT/UPDATE fallback takes over.
     *
     * @param referentialPayload the payload to persist; must not be {@code null}
     */
    @Override
    public void store(ReferentialPayload<T> referentialPayload) {
        if (mergeEnabled.get()) {
            try {
                var count = jdbcTemplate.update(mergeQuery,
                        queryResolver.buildInsertMergeParams(referentialPayload),
                        queryResolver.buildInsertMergeTypes());
                log.debug("Referential payload merged. Records affected: '{}'", count);
                return;
            } catch (BadSqlGrammarException e) {
                log.warn("Native merge/upsert not supported by this database — switching permanently to INSERT/UPDATE fallback. Cause: {}", e.getMessage());
                mergeEnabled.set(false);
            }
        }
        insertOrUpdate(referentialPayload);
    }

    /**
     * Inserts the payload; on {@link org.springframework.dao.DuplicateKeyException} falls back to UPDATE.
     */
    private void insertOrUpdate(ReferentialPayload<T> referentialPayload) {
        try {
            var count = jdbcTemplate.update(queryResolver.getInsertQuery(),
                    queryResolver.buildInsertMergeParams(referentialPayload),
                    queryResolver.buildInsertMergeTypes());
            log.debug("Referential payload inserted. Records inserted: '{}'", count);
        } catch (DuplicateKeyException e) {
            log.debug("Referential payload already exists for name='{}', key='{}'. Retrying as UPDATE.", referentialPayload.getName(), referentialPayload.getKey());
            var count = jdbcTemplate.update(queryResolver.getUpdateQuery(),
                    queryResolver.buildUpdateParams(referentialPayload),
                    queryResolver.buildUpdateTypes());
            log.debug("Referential payload updated. Records updated: '{}'", count);
        }
    }

    /**
     * Deletes the row identified by the payload's {@code name} and {@code key}.
     *
     * @param referentialPayload the payload to delete; must not be {@code null}
     */
    @Override
    public void delete(ReferentialPayload<T> referentialPayload) {
        var count = jdbcTemplate.update(queryResolver.getDeleteQuery(),
                referentialPayload.getName(), referentialPayload.getKey());
        log.debug("Referential payload deleted. No of record deleted : '{}'", count);
    }

    /**
     * Looks up the payload for the given {@code name} and {@code key}.
     *
     * @param name the referential name
     * @param key  the unique key within that referential
     * @return an {@link Optional} containing the payload, or empty if not found
     */
    @Override
    public Optional<ReferentialPayload<T>> find(String name, String key) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    queryResolver.getSelectQuery(),
                    rowMapper,
                    name, key));
        } catch (EmptyResultDataAccessException e) {
            log.debug("No referential found for name : '{}'", name, e);
            return Optional.empty();
        }
    }

    /**
     * Deletes all rows whose {@code EXPIRE_ON} is before {@code expiry}.
     *
     * @param expiry the cut-off instant; rows with {@code EXPIRE_ON < expiry} are removed
     */
    @Override
    public void cleanByExpiry(Instant expiry) {
        var count = jdbcTemplate.update(queryResolver.getCleanUpQuery(), Timestamp.from(expiry));
        log.debug("Referential payload cleaned up by given expiry : '{}' . No of record deleted : '{}'", expiry, count);
    }
}
