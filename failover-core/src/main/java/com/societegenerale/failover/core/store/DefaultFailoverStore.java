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

package com.societegenerale.failover.core.store;

import com.societegenerale.failover.core.payload.ReferentialPayload;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Instant;
import java.util.List;
import java.util.Optional;

import static java.lang.Boolean.FALSE;

/**
 * Default {@link FailoverStore} decorator that ensures every payload written to or read from
 * the delegate store has its {@code upToDate} flag forced to {@code false}.
 *
 * <p>This prevents stale failover data from being mistaken for a fresh response: consumers
 * can check {@code upToDate} to distinguish a live result from a failover fallback.
 * The {@link #cleanByExpiry} operation is delegated as-is since it does not produce payloads.
 *
 * @param <T> the type of the payload held by each referential entry
 */
@RequiredArgsConstructor
public class DefaultFailoverStore<T> implements FailoverStore<T> {

    @Getter
    private final FailoverStore<T> failoverStore;

    /**
     * Stores a copy of the given payload with {@code upToDate} forced to {@code false},
     * then delegates to the underlying store.
     *
     * @param referentialPayload the payload to persist; must not be {@code null}
     * @throws FailoverStoreException if the delegate store operation fails
     */
    @Override
    public void store(ReferentialPayload<T> referentialPayload) throws FailoverStoreException {
        failoverStore.store(referentialPayload.copy().withUpToDate(FALSE));
    }

    /**
     * Deletes a copy of the given payload with {@code upToDate} forced to {@code false},
     * then delegates to the underlying store.
     *
     * @param referentialPayload the payload to remove; must not be {@code null}
     * @throws FailoverStoreException if the delegate delete operation fails
     */
    @Override
    public void delete(ReferentialPayload<T> referentialPayload) throws FailoverStoreException {
        failoverStore.delete(referentialPayload.copy().withUpToDate(FALSE));
    }

    /**
     * Looks up a payload by name and key, returning a copy with {@code upToDate} forced to
     * {@code false} if found.
     *
     * @param name the referential name
     * @param key  the unique key within that referential
     * @return an {@link Optional} containing a defensive copy of the stored payload with {@code upToDate=false}, or empty if not found
     * @throws FailoverStoreException if the delegate lookup operation fails
     */
    @Override
    public Optional<ReferentialPayload<T>> find(String name, String key) throws FailoverStoreException {
        return failoverStore.find(name, key).map(r -> r.copy().withUpToDate(FALSE));
    }

    /**
     * Returns all payloads for the given name, each as a defensive copy with {@code upToDate}
     * forced to {@code false}.
     *
     * <p>This is the enforcement point for the {@link FailoverStore#findAll} defensive-copy
     * contract: standard stores delegating through {@code DefaultFailoverStore} satisfy it
     * automatically.
     *
     * @param name the referential name
     * @return defensive copies of all matching payloads with {@code upToDate=false}, or an empty list
     * @throws FailoverStoreException if the delegate lookup operation fails
     */
    @Override
    public List<ReferentialPayload<T>> findAll(String name) throws FailoverStoreException {
        return failoverStore.findAll(name).stream().map(r -> r.copy().withUpToDate(FALSE)).toList();
    }

    /**
     * Delegates expiry-based cleanup directly to the underlying store without modification.
     *
     * @param expiry the cutoff instant; entries whose expireOn is before this value are removed
     * @throws FailoverStoreException if the delegate cleanup operation fails
     */
    @Override
    public void cleanByExpiry(Instant expiry) throws FailoverStoreException {
        failoverStore.cleanByExpiry(expiry);
    }
}