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

/**
 * Validates the {@code table-prefix} configured for the JDBC shared-store tier, shared by
 * {@link SnapshotStoreJdbc} and {@link HeartbeatStoreJdbc} — both concatenate it directly into SQL.
 *
 * @author Anand Manissery
 */
final class TablePrefix {

    private TablePrefix() {
    }

    /**
     * Validates the table prefix — it is concatenated into SQL, so it must be a safe SQL identifier fragment:
     * empty, or letters/digits/underscore only (no whitespace, quotes, or punctuation). Prevents SQL injection
     * via the prefix. A {@code null} prefix is treated as empty.
     *
     * @param prefix the configured {@code table-prefix} ({@code ""} ⇒ the base table name is used as-is)
     * @return the validated prefix
     */
    static String validate(String prefix) {
        String p = prefix == null ? "" : prefix;
        if (!p.matches("[A-Za-z0-9_]*")) {
            throw new IllegalArgumentException(
                    "Illegal table-prefix '" + prefix + "' — only letters, digits and underscore are allowed.");
        }
        return p;
    }
}
