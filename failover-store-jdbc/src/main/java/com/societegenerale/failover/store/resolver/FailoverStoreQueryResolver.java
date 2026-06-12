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
import org.jspecify.annotations.Nullable;

/**
 * Contract for resolving JDBC queries, binding parameters, and mapping result-set rows
 * for the failover store.
 *
 * <p>Implementations own the SQL text, column ordering, SQL types, and result-set mapping
 * so that schema changes require edits in exactly one place.
 *
 * @author Anand Manissery
 * @see DefaultFailoverStoreQueryResolver
 */
public interface FailoverStoreQueryResolver {

    // -----------------------------------------------------------------
    // Resolved queries
    // -----------------------------------------------------------------

    /** @return the INSERT SQL for a new row */
    String getInsertQuery();

    /** @return the UPDATE SQL for an existing row (SET columns first, then WHERE predicate) */
    String getUpdateQuery();

    /** @return the SELECT SQL that retrieves a single row by {@code FAILOVER_NAME} and {@code FAILOVER_KEY} */
    String getSelectQuery();

    /** @return the SELECT SQL that retrieves all rows for a given {@code FAILOVER_NAME} */
    String getSelectAllByNameQuery();

    /** @return the DELETE SQL that removes a single row by {@code FAILOVER_NAME} and {@code FAILOVER_KEY} */
    String getDeleteQuery();

    /** @return the DELETE SQL that removes all rows with {@code EXPIRE_ON} before a given timestamp */
    String getCleanUpQuery();

    /**
     * Native merge/upsert query for the detected database dialect, or {@code null} when no
     * known dialect is available — the store falls back to INSERT + UPDATE on duplicate in that case.
     */
    @Nullable
    String getMergeQuery();

    // -----------------------------------------------------------------
    // Parameter builders
    // -----------------------------------------------------------------

    /**
     * Builds the parameter array for INSERT and all MERGE/upsert queries.
     * Column order: FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS.
     */
    <T> Object[] buildInsertMergeParams(ReferentialPayload<T> payload);

    /** SQL types matching {@link #buildInsertMergeParams} column order. */
    int[] buildInsertMergeTypes();

    /**
     * Builds the parameter array for the UPDATE query (SET columns first, then WHERE predicate).
     * Column order: AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS, FAILOVER_NAME, FAILOVER_KEY.
     */
    <T> Object[] buildUpdateParams(ReferentialPayload<T> payload);

    /** SQL types matching {@link #buildUpdateParams} column order. */
    int[] buildUpdateTypes();
}
