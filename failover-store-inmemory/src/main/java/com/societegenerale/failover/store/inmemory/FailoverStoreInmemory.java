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

package com.societegenerale.failover.store.inmemory;

import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.core.store.FailoverStoreException;
import com.societegenerale.failover.core.store.FailoverStoreSizeAware;
import lombok.extern.slf4j.Slf4j;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link FailoverStore} implementation backed by an in-process map.
 *
 * <p>Suitable for single-node, non-persistent failover caching where simplicity is preferred
 * over advanced eviction policies. Entries are evicted explicitly via {@link #cleanByExpiry},
 * which removes all entries whose {@code expireOn} timestamp is strictly before the given cutoff.
 *
 * <p><b>Bounded mode (audit I-10).</b> When constructed with a positive {@code maxEntries}, the store
 * caps its size and evicts the <em>least-recently-accessed</em> entry (LRU) once the cap is exceeded,
 * preventing unbounded heap growth from high-cardinality keys. When {@code maxEntries <= 0} the store
 * is unbounded (backed by a {@link ConcurrentHashMap}) — the historical behaviour.
 *
 * <p>All reads and writes operate on defensive copies of {@link ReferentialPayload} to prevent
 * callers from mutating stored state.
 *
 * <p>Cache keys use the composite format {@code name##key}.
 *
 * @param <T> the type of the business payload
 * @author Anand Manissery
 */
@Slf4j
public class FailoverStoreInmemory<T> implements FailoverStore<T>, FailoverStoreSizeAware {

    /** Separator between referential name and entry key in the composite store key. */
    private static final String STORE_KEY_DELIMITER = "##";

    private final Map<String, ReferentialPayload<T>> store;

    /** Creates an <b>unbounded</b> store (backward-compatible default). */
    public FailoverStoreInmemory() {
        this(0);
    }

    /**
     * Creates a store with an optional size cap.
     *
     * @param maxEntries maximum number of entries to retain; {@code <= 0} means unbounded. When the
     *                   cap is exceeded the least-recently-accessed entry is evicted (LRU).
     */
    public FailoverStoreInmemory(int maxEntries) {
        this.store = maxEntries > 0 ? boundedLruMap(maxEntries) : new ConcurrentHashMap<>();
    }

    /** Access-ordered {@link LinkedHashMap} that evicts the eldest (LRU) entry past {@code cap}, wrapped for thread-safety. */
    private Map<String, ReferentialPayload<T>> boundedLruMap(int cap) {
        return Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            @Override
            protected boolean removeEldestEntry(Map.Entry<String, ReferentialPayload<T>> eldest) {
                boolean evict = size() > cap;
                if (evict) {
                    log.debug("FailoverStoreInmemory at capacity ({}); evicting LRU entry '{}'", cap, eldest.getKey());
                }
                return evict;
            }
        });
    }

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
     * Returns defensive copies of all entries whose composite key is prefixed by {@code name##}.
     *
     * <p>Performs a full-map prefix scan — O(n) over the store size; acceptable for the
     * dev/test in-memory store but not intended for large datasets.
     *
     * @param name the referential name
     * @return defensive copies of all matching payloads, or an empty list if none match
     */
    @Override
    public List<ReferentialPayload<T>> findAll(String name) throws FailoverStoreException {
        // synchronized: a wrapped LinkedHashMap (bounded mode) requires the caller to hold the lock while iterating.
        synchronized (store) {
            return store.entrySet().stream().filter(e->
                    e.getKey().startsWith(name + STORE_KEY_DELIMITER)
            ).map(e-> e.getValue().copy()).toList();
        }
    }

    /**
     * Evicts all entries whose {@code expireOn} instant is strictly before the given cutoff.
     *
     * @param expiry the cutoff instant; entries with {@code expireOn} before this value are removed
     */
    @Override
    public void cleanByExpiry(Instant expiry) {
        synchronized (store) {
            store.entrySet().removeIf(entry -> expiry.isAfter(entry.getValue().getExpireOn()));
        }
    }

    /**
     * Counts the entries currently held for the given referential name (composite-key prefix scan).
     *
     * @param name the referential name
     * @return number of live entries stored under {@code name}
     */
    @Override
    public long liveEntryCount(String name) {
        String prefix = name + STORE_KEY_DELIMITER;
        synchronized (store) {
            return store.keySet().stream().filter(k -> k.startsWith(prefix)).count();
        }
    }

    /**
     * Builds a composite store key from the referential name and entry key.
     *
     * @param name the referential name
     * @param key  the entry key
     * @return composite key in the form {@code name##key}
     */
    private String storeKey(String name, String key) {
        return name + STORE_KEY_DELIMITER + key;
    }
}
