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

import java.sql.Types;
import java.time.LocalDateTime;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * @author Anand Manissery
 */
@Slf4j
public class FailoverStoreJdbc<T> implements FailoverStore<T> {

    private final JdbcTemplate jdbcTemplate;

    private final FailoverStoreQueryResolver queryResolver;

    /** Merge/upsert SQL resolved once at construction; {@code null} when no dialect is available. */
    @Getter
    private final String mergeQuery;

    /** Flipped to false at runtime if the merge SQL fails with a grammar error. */
    private final AtomicBoolean mergeEnabled;

    public FailoverStoreJdbc(JdbcTemplate jdbcTemplate, FailoverStoreQueryResolver failoverStoreQueryResolver) {
        this.jdbcTemplate  = jdbcTemplate;
        this.queryResolver = failoverStoreQueryResolver;
        this.mergeQuery = queryResolver.getMergeQuery();
        this.mergeEnabled  = new AtomicBoolean(this.mergeQuery != null);
    }

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

    @Override
    public void delete(ReferentialPayload<T> referentialPayload) {
        var count = jdbcTemplate.update(queryResolver.getDeleteQuery(),
                new Object[]{referentialPayload.getName(), referentialPayload.getKey()},
                new int[]{Types.VARCHAR, Types.VARCHAR});
        log.debug("Referential payload deleted. No of record deleted : '{}'", count);
    }

    @Override
    public Optional<ReferentialPayload<T>> find(String name, String key) {
        try {
            return Optional.ofNullable(jdbcTemplate.queryForObject(
                    queryResolver.getSelectQuery(),
                    new Object[]{name, key},
                    new int[]{Types.VARCHAR, Types.VARCHAR},
                    (rs, _) -> queryResolver.mapRow(rs)));
        } catch (EmptyResultDataAccessException e) {
            log.debug("No referential found for name : '{}'", name, e);
            return Optional.empty();
        }
    }

    @Override
    public void cleanByExpiry(LocalDateTime expiry) {
        var count = jdbcTemplate.update(queryResolver.getCleanUpQuery(),
                new Object[]{expiry}, new int[]{Types.TIMESTAMP});
        log.debug("Referential payload cleaned up by given expiry : '{}' . No of record deleted : '{}'", expiry, count);
    }

}
