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
import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.FailoverStore;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

import static java.util.Optional.empty;
import static java.util.Optional.ofNullable;

/**
 * {@link FailoverStore} implementation backed by Caffeine in-memory caches.
 *
 * <p>Each referential name gets its own {@link Cache} instance, created on first write and
 * configured with {@code expireAfterWrite} set to the duration from the current clock time to
 * the payload's {@code expireOn} timestamp. Caffeine handles eviction automatically, so
 * {@link #cleanByExpiry} is a no-op.
 *
 * <p>All reads and writes operate on defensive copies of {@link ReferentialPayload} to prevent
 * callers from mutating cached state.
 *
 * <p>Cache keys use the composite format {@code name##key}.
 *
 * @param <T> the type of the business payload
 * @author Anand Manissery
 */
@AllArgsConstructor
@Slf4j
public class FailoverStoreCaffeine<T> implements FailoverStore<T> {

    private final Map<String, Cache<String, ReferentialPayload<T>>> store = new ConcurrentHashMap<>();

    private final FailoverClock failoverClock;

    /**
     * Stores a defensive copy of the payload in the Caffeine cache for its referential name.
     *
     * <p>If no cache exists for the given name, one is created with {@code expireAfterWrite}
     * computed as the duration from now to {@code referentialPayload.getExpireOn()}.
     *
     * @param referentialPayload the payload to cache; must not be {@code null}
     */
    @Override
    public void store(ReferentialPayload<T> referentialPayload) {
        var payload = referentialPayload.copy();
        var cache = store.computeIfAbsent(payload.getName(),
                _ -> Caffeine.newBuilder().expireAfterWrite(Duration.between(failoverClock.now(), payload.getExpireOn())).build());
        cache.put(storeKey(payload.getName(), payload.getKey()), payload);
    }

    /**
     * Invalidates the cache entry for the given payload, if the referential cache exists.
     *
     * <p>A no-op if no cache has been created for the payload's referential name.
     *
     * @param referentialPayload the payload to remove; must not be {@code null}
     */
    @Override
    public void delete(ReferentialPayload<T> referentialPayload) {
        if (store.containsKey(referentialPayload.getName())) {
            store.get(referentialPayload.getName()).invalidate(storeKey(referentialPayload.getName(), referentialPayload.getKey()));
        }
    }

    /**
     * Looks up a payload by name and key, returning a defensive copy if found.
     *
     * <p>Returns {@link Optional#empty()} if no cache exists for the given name or if the
     * entry has been evicted by Caffeine.
     *
     * @param name the referential name
     * @param key  the unique key within that referential
     * @return an {@link Optional} containing a copy of the cached payload, or empty if not found
     */
    @Override
    public Optional<ReferentialPayload<T>> find(String name, String key) {
        if (store.containsKey(name)) {
            return ofNullable(store.get(name).get(storeKey(name, key), _ -> null)).map(ReferentialPayload::copy);
        }
        return empty();
    }

    /**
     * No-op: expiry is managed automatically by Caffeine's {@code expireAfterWrite} policy.
     *
     * @param expiry ignored
     */
    @Override
    public void cleanByExpiry(LocalDateTime expiry) {
        log.debug("Ignoring the clean up as the expiry is already managed by Caffeine Cache with 'expireAfterWrite'");
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