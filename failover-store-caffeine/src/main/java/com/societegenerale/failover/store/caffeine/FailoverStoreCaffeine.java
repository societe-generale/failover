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

package com.societegenerale.failover.store.caffeine;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.core.store.FailoverStoreException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.util.Optional.ofNullable;

/**
 * {@link FailoverStore} implementation backed by a single Caffeine in-memory cache.
 *
 * <h2>Storage layout</h2>
 * <p>All entries — regardless of referential name — are stored in one flat {@link Cache}.
 * Each entry's cache key is the composite string {@code "<name>##<key>"}, keeping name and
 * key scoped together without a nested map structure.
 *
 * <h2>Per-entry expiry</h2>
 * <p>A custom {@link Expiry} policy derives each entry's TTL individually from its own
 * {@link ReferentialPayload} {@code expireOn} timestamp at the moment of creation or update:
 * <pre>
 *   TTL = Duration.between(clock.now(), payload.getExpireOn())
 * </pre>
 * This means two entries stored under the same referential name can have different TTLs
 * and are evicted independently. Caffeine handles eviction automatically on a background
 * thread, so {@link #cleanByExpiry} is a no-op for this implementation.
 *
 * <h2>Read expiry</h2>
 * <p>Reading an entry ({@link #find}) does not reset its TTL — the remaining duration is
 * preserved unchanged on every read access.
 *
 * <h2>Defensive copies</h2>
 * <p>Both {@link #store} and {@link #find} operate on copies of {@link ReferentialPayload}
 * ({@link ReferentialPayload#copy()}). This prevents callers from mutating cached state
 * through a retained reference.
 *
 * <h2>Thread safety</h2>
 * <p>Caffeine's {@link Cache} is fully thread-safe. All operations ({@link #store},
 * {@link #find}, {@link #delete}) may be called concurrently without external synchronisation.
 *
 * @param <T> the type of the business payload held by each {@link ReferentialPayload}
 * @author Anand Manissery
 * @see FailoverStore
 * @see ReferentialPayload
 */
@Slf4j
public class FailoverStoreCaffeine<T> implements FailoverStore<T> {

    /** Separator between referential name and entry key in the composite cache key. */
    private static final String STORE_KEY_DELIMITER = "##";

    private final FailoverClock failoverClock;

    private final Cache<String, ReferentialPayload<T>> cache;

    /**
     * Constructs an <b>unbounded</b> {@code FailoverStoreCaffeine} (size limited only by per-entry expiry).
     *
     * @param fClock clock used to compute each entry's remaining TTL from its {@code expireOn}
     *               timestamp; must not be {@code null}
     */
    public FailoverStoreCaffeine(FailoverClock fClock) {
        this(fClock, 0);
    }

    /**
     * Constructs a {@code FailoverStoreCaffeine} with an optional maximum entry count.
     *
     * <p>When {@code maximumSize > 0}, Caffeine bounds the cache and evicts entries on a
     * size-based (Window TinyLFU) policy once the cap is exceeded — capping heap use from
     * high-cardinality keys. When {@code maximumSize <= 0} the cache is unbounded and limited only
     * by per-entry expiry (audit I-15).
     *
     * @param fClock      clock used to compute each entry's remaining TTL from its {@code expireOn}
     *                    timestamp; must not be {@code null}
     * @param maximumSize maximum number of entries to retain; {@code <= 0} means unbounded
     */
    public FailoverStoreCaffeine(FailoverClock fClock, long maximumSize) {
        this.failoverClock = fClock;
        var builder = Caffeine.newBuilder()
                .expireAfter(new Expiry<String, ReferentialPayload<T>>() {
                    @Override
                    public long expireAfterCreate(@NonNull String key, @NonNull ReferentialPayload<T> value, long currentTime) {
                        return Duration.between(failoverClock.now(), value.getExpireOn()).toNanos();
                    }
                    @Override
                    public long expireAfterUpdate(@NonNull String key, @NonNull ReferentialPayload<T> value, long currentTime, long currentDuration) {
                        return Duration.between(failoverClock.now(), value.getExpireOn()).toNanos();
                    }
                    @Override
                    public long expireAfterRead(@NonNull String key, @NonNull ReferentialPayload<T> value, long currentTime, long currentDuration) {
                        return currentDuration;
                    }
                });
        if (maximumSize > 0) {
            builder.maximumSize(maximumSize);
        }
        this.cache = builder.build();
    }

    /**
     * Stores a defensive copy of the payload in the cache.
     *
     * <p>The cache key is the composite {@code "<name>##<key>"}. If an entry for that key
     * already exists, it is replaced and its TTL is re-derived from the new payload's
     * {@code expireOn}. If no entry exists, a new one is created with TTL computed as
     * {@code Duration.between(clock.now(), payload.getExpireOn())}.
     *
     * @param referentialPayload the payload to cache; must not be {@code null}
     */
    @Override
    public void store(ReferentialPayload<T> referentialPayload) {
        var rPayload = referentialPayload.copy();
        cache.put(storeKey(rPayload.getName(), rPayload.getKey()), rPayload);
    }

    /**
     * Invalidates the cache entry for the given payload.
     *
     * <p>This is a no-op if the entry does not exist or has already been evicted by Caffeine.
     *
     * @param referentialPayload the payload to remove; must not be {@code null}
     */
    @Override
    public void delete(ReferentialPayload<T> referentialPayload) {
        cache.invalidate(storeKey(referentialPayload.getName(), referentialPayload.getKey()));
    }

    /**
     * Looks up a payload by referential name and key.
     *
     * <p>Returns a defensive copy of the cached payload if present and not yet evicted.
     * Returns {@link Optional#empty()} if the entry was never stored, has expired, or was
     * explicitly deleted. Reading an entry does not reset its TTL.
     *
     * @param name the referential name
     * @param key  the unique key within that referential
     * @return an {@link Optional} containing a copy of the cached payload, or empty if absent
     */
    @Override
    public Optional<ReferentialPayload<T>> find(String name, String key) {
        return ofNullable(cache.getIfPresent(storeKey(name, key))).map(ReferentialPayload::copy);
    }

    /**
     * Returns defensive copies of all cached entries whose composite key is prefixed by
     * {@code name##}.
     *
     * <p>Performs a prefix scan over the live cache view ({@link Cache#asMap()}); already-expired
     * entries are excluded by Caffeine. Returns an empty list when no entry matches.
     *
     * @param name the referential name
     * @return defensive copies of all matching payloads, or an empty list if none match
     */
    @Override
    public List<ReferentialPayload<T>> findAll(String name) throws FailoverStoreException {
        return cache.asMap().entrySet().stream().filter(e->
                e.getKey().startsWith(name + STORE_KEY_DELIMITER)
        ).map(e-> e.getValue().copy()).toList();
    }

    /**
     * No-op for this implementation.
     *
     * <p>Expiry is managed automatically by Caffeine's per-entry expiry policy; there is no
     * need to scan and remove entries manually. The {@code expiry} argument is ignored.
     *
     * @param expiry ignored
     */
    @Override
    public void cleanByExpiry(Instant expiry) {
        log.debug("Ignoring the clean up as the expiry is already managed by Caffeine Cache with per-entry expiry policy");
    }

    /**
     * Builds the composite cache key {@code "<name>##<key>"}.
     *
     * @param name the referential name
     * @param key  the entry key
     * @return composite key in the form {@code name##key}
     */
    private String storeKey(String name, String key) {
        return name + STORE_KEY_DELIMITER + key;
    }
}
