/**
 * Concrete {@link com.societegenerale.failover.core.store.FailoverStore} implementations.
 *
 * <p>Available backends:
 * <ul>
 *   <li>{@code FailoverStoreInmemory} — {@code ConcurrentHashMap}-backed; suitable for tests only</li>
 *   <li>{@code FailoverStoreCaffeine} — Caffeine cache with per-entry TTL expiry</li>
 *   <li>{@code FailoverStoreJdbc} — relational database backed by a single {@code FAILOVER_STORE} table</li>
 *   <li>{@code FailoverStoreAsync} — decorator that offloads writes to a background executor</li>
 * </ul>
 */
package com.societegenerale.failover.store;
