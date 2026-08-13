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

/**
 * Durable JDBC-backed {@link com.societegenerale.failover.core.store.FailoverStore}.
 *
 * <p>{@link com.societegenerale.failover.store.jdbc.FailoverStoreJdbc} persists entries to a
 * {@code FAILOVER_STORE} table via a {@code JdbcTemplate}, using a native merge/upsert dialect when
 * one is detected (H2, PostgreSQL, MySQL/MariaDB, Oracle, SQL Server) and falling back to an
 * INSERT-then-UPDATE pattern otherwise. Payloads are serialised by the
 * {@link com.societegenerale.failover.store.jdbc.serializer serializer} package, mapped by the
 * {@link com.societegenerale.failover.store.jdbc.mapper mapper} package, and the SQL is produced by the
 * {@link com.societegenerale.failover.store.jdbc.resolver resolver} package. The recommended production
 * store.
 */
package com.societegenerale.failover.store.jdbc;
