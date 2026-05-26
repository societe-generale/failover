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

import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.FailoverStore;

import java.time.LocalDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link FailoverStore} implementation backed by a plain {@link ConcurrentHashMap}.
 *
 * <p>Suitable for single-node, non-persistent failover caching where simplicity is preferred
 * over advanced eviction policies. Entries are evicted explicitly via {@link #cleanByExpiry},
 * which removes all entries whose {@code expireOn} timestamp is strictly before the given cutoff.
 *
 * <p>All reads and writes operate on defensive copies of {@link ReferentialPayload} to prevent
 * callers from mutating stored state.
 *
 * <p>Cache keys use the composite format {@code name##key}.
 *
 * @param <T> the type of the business payload
 * @author Anand Manissery
 */
public class FailoverStoreInmemory<T> implements FailoverStore<T> {

    private final Map<String, ReferentialPayload<T>> store = new ConcurrentHashMap<>();

    /**
     * Stores a defensive copy of the payload, keyed by its referential name and key.
     *
     * <p>An existing entry for the same ({@code name}, {@code key}) pair is overwritten.
     *
     * @param referentialPayload the payload to store; must not be {@code null}
     */
    @Override
    public void store(ReferentialPayload<T> referentialPayload) {
        var payload = referentialPayload.copy();
        store.put(storeKey(payload.getName(), payload.getKey()), payload);
    }

    /**
     * Removes the entry for the given payload's ({@code name}, {@code key}) pair.
     *
     * <p>A no-op if no entry exists for that pair.
     *
     * @param referentialPayload the payload to remove; must not be {@code null}
     */
    @Override
    public void delete(ReferentialPayload<T> referentialPayload) {
        store.remove(storeKey(referentialPayload.getName(), referentialPayload.getKey()));
    }

    /**
     * Looks up a payload by name and key, returning a defensive copy if found.
     *
     * @param name the referential name
     * @param key  the unique key within that referential
     * @return an {@link Optional} containing a copy of the stored payload, or empty if not found
     */
    @Override
    public Optional<ReferentialPayload<T>> find(String name, String key) {
        return Optional.ofNullable(store.get(storeKey(name, key))).map(ReferentialPayload::copy);
    }

    /**
     * Evicts all entries whose {@code expireOn} timestamp is strictly before the given cutoff.
     *
     * @param expiry the cutoff datetime; entries with {@code expireOn} before this value are removed
     */
    @Override
    public void cleanByExpiry(LocalDateTime expiry) {
        store.entrySet().removeIf(entry -> expiry.isAfter(entry.getValue().getExpireOn()));
    }

    /**
     * Builds a composite store key from the referential name and entry key.
     *
     * @param name the referential name
     * @param key  the entry key
     * @return composite key in the form {@code name##key}
     */
    private String storeKey(String name, String key) {
        return name + "##" + key;
    }
}
