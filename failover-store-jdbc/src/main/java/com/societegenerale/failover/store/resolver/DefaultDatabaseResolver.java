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

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Default {@link DatabaseResolver} that reads the database product name from JDBC connection metadata.
 *
 * <p>Returns {@code null} and logs a warning when the product name cannot be determined,
 * which causes the store to fall back to the INSERT + UPDATE on duplicate strategy.
 *
 * @author Anand Manissery
 */
@RequiredArgsConstructor
@Slf4j
public class DefaultDatabaseResolver implements DatabaseResolver {

    private final JdbcTemplate jdbcTemplate;

    @Override
    @Nullable
    public String resolve() {
        try {
            return jdbcTemplate.execute((java.sql.Connection conn) -> conn.getMetaData().getDatabaseProductName());
        } catch (Exception e) {
            log.warn("Failed to detect database product name — merge/upsert disabled, using INSERT/UPDATE fallback. Cause: {}", e.getMessage());
            return null;
        }
    }
}
