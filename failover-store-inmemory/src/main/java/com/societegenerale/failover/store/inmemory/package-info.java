/**
 * In-memory {@link com.societegenerale.failover.core.store.FailoverStore} backend.
 *
 * <p>{@code FailoverStoreInmemory} is {@code ConcurrentHashMap}-backed and holds entries only for the
 * lifetime of the JVM; it is intended for development and tests. Production deployments use the
 * Caffeine, JDBC, or async backends in their own modules.
 */
package com.societegenerale.failover.store.inmemory;
