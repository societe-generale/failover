/**
 * SQL query and column-type resolvers for the JDBC failover store.
 *
 * <p>{@link com.societegenerale.failover.store.resolver.FailoverStoreQueryResolver} supplies
 * the parameterised SQL statements used by {@code FailoverStoreJdbc}.
 * {@link com.societegenerale.failover.store.resolver.PayloadColumnResolver} abstracts the
 * SQL type of the {@code PAYLOAD} column, allowing {@code VARCHAR}, {@code TEXT}, or
 * {@code CLOB} variants via a pluggable strategy.
 */
package com.societegenerale.failover.store.resolver;
