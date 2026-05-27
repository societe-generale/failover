/*
 * Copyright 2022-2023, Société Générale All rights reserved.
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

package com.societegenerale.failover.store;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.github.benmanes.caffeine.cache.Expiry;
import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.FailoverStore;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Optional;

import static java.util.Optional.ofNullable;

/**
 * {@link FailoverStore} implementation backed by a single Caffeine cache.
 *
 * <p>All referential entries are stored in one flat {@link Cache} keyed on the composite
 * {@code name##key} format. A per-entry {@link Expiry} policy derives each entry's TTL from
 * its own {@code expireOn} timestamp, so entries with different expiry values are evicted
 * independently. Caffeine handles eviction automatically; {@link #cleanByExpiry} is a no-op.
 *
 * <p>All reads and writes operate on defensive copies of {@link ReferentialPayload} to prevent
 * callers from mutating cached state.
 *
 * @param <T> the type of the business payload
 * @author Anand Manissery
 */
@Slf4j
public class FailoverStoreCaffeine<T> implements FailoverStore<T> {

    private final FailoverClock failoverClock;

    private final Cache<String, ReferentialPayload<T>> cache;

    public FailoverStoreCaffeine(FailoverClock fClock) {
        this.failoverClock = fClock;
        this.cache = Caffeine.newBuilder()
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
                })
                .build();
    }

    /**
     * Stores a defensive copy of the payload using the composite {@code name##key} cache key.
     * The entry's TTL is derived from its own {@code expireOn} timestamp.
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
     * No-op if the entry does not exist or has already been evicted.
     *
     * @param referentialPayload the payload to remove; must not be {@code null}
     */
    @Override
    public void delete(ReferentialPayload<T> referentialPayload) {
        cache.invalidate(storeKey(referentialPayload.getName(), referentialPayload.getKey()));
    }

    /**
     * Looks up a payload by name and key, returning a defensive copy if found.
     *
     * @param name the referential name
     * @param key  the unique key within that referential
     * @return an {@link Optional} containing a copy of the cached payload, or empty if not found or evicted
     */
    @Override
    public Optional<ReferentialPayload<T>> find(String name, String key) {
        return ofNullable(cache.getIfPresent(storeKey(name, key))).map(ReferentialPayload::copy);
    }

    /**
     * No-op: expiry is managed automatically by Caffeine's per-entry expiry policy.
     *
     * @param expiry ignored
     */
    @Override
    public void cleanByExpiry(LocalDateTime expiry) {
        log.debug("Ignoring the clean up as the expiry is already managed by Caffeine Cache with per-entry expiry policy");
    }

    /**
     * Builds a composite cache key from the referential name and entry key.
     *
     * @param name the referential name
     * @param key  the entry key
     * @return composite key in the form {@code name##key}
     */
    private String storeKey(String name, String key) {
        return name + "##" + key;
    }
}
