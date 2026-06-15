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

package com.societegenerale.failover.store.jdbc.resolver;

import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Strategy for resolving the SQL type and extracting the value of the PAYLOAD column.
 *
 * <p>Implement this interface to support payload column types other than the default
 * {@code VARCHAR} (e.g. {@code TEXT} or {@code CLOB}).
 *
 * @author Anand Manissery
 * @see VarcharPayloadColumnResolver
 */
public interface PayloadColumnResolver {

    /**
     * Returns the JDBC type constant for the payload column.
     *
     * @return the JDBC type constant (see {@link java.sql.Types})
     */
    int payloadType();

    /**
     * Extracts the payload value from the current row of the given result set.
     *
     * @param resultSet     result set positioned on the current row
     * @param payloadColumn name of the payload column
     * @return the payload value as a {@code String}, or {@code null} if the column is SQL NULL
     * @throws SQLException if a database access error occurs
     */
    String extractPayload(ResultSet resultSet, String payloadColumn) throws SQLException;
}
