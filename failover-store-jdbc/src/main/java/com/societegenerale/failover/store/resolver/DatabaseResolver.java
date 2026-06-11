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

import org.jspecify.annotations.Nullable;

/**
 * Strategy for detecting the underlying database product name from a live JDBC connection.
 *
 * <p>The resolved name is used by {@link FailoverStoreQueryResolver} to select the appropriate
 * native merge/upsert dialect. Returning {@code null} signals that the product name could not
 * be determined, which causes the store to fall back to the INSERT + UPDATE on duplicate strategy.
 *
 * @author Anand Manissery
 * @see DefaultDatabaseResolver
 */
public interface DatabaseResolver {

    /**
     * @return database product name (e.g. {@code "H2"}, {@code "PostgreSQL"}), or {@code null}
     *         if the name cannot be determined
     */
    @Nullable
    String resolve();
}
