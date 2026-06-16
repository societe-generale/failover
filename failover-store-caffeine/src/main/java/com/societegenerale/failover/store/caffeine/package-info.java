/**
 * Caffeine-backed in-process {@link com.societegenerale.failover.core.store.FailoverStore}.
 *
 * <p>{@link com.societegenerale.failover.store.caffeine.FailoverStoreCaffeine} holds all entries in a
 * single Caffeine {@code Cache} keyed by {@code "<name>##<key>"}, deriving each entry's TTL from its
 * own {@code expireOn} via a per-entry {@code Expiry} policy. Optionally size-bounded via
 * {@code failover.store.caffeine.max-size} (Window TinyLFU eviction). Suitable for single-node,
 * non-persistent caching.
 */
package com.societegenerale.failover.store.caffeine;
