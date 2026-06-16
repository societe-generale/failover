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
