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

import java.time.Instant;
import java.util.List;
import java.util.Optional;

/**
 * Persistence contract for failover referential data.
 *
 * <p>Implementations are responsible for storing, retrieving, and evicting
 * {@link ReferentialPayload} entries so that the failover mechanism can serve
 * cached data when the primary source is unavailable.
 *
 * @param <T> the type of the payload held by each referential entry
 * @author Anand Manissery
 */
public interface FailoverStore<T> {

    /**
     * Persists or updates a referential payload entry.
     *
     * @param referentialPayload the payload to store; must not be {@code null}
     * @throws FailoverStoreException if the underlying store operation fails
     */
    void store(ReferentialPayload<T> referentialPayload) throws FailoverStoreException;

    /**
     * Removes a referential payload entry from the store.
     *
     * @param referentialPayload the payload to remove; must not be {@code null}
     * @throws FailoverStoreException if the underlying delete operation fails
     */
    void delete(ReferentialPayload<T> referentialPayload) throws FailoverStoreException;

    /**
     * Looks up a referential payload by its logical name and key.
     *
     * <p><strong>Implementation contract:</strong> return a defensive copy of the stored entry,
     * not a live internal reference. Callers may mutate the returned object (e.g. set
     * {@code upToDate = false}) without affecting the data held in the store.
     * {@link com.societegenerale.failover.core.store.DefaultFailoverStore} already satisfies this contract
     * for all standard store implementations. Custom stores that do not delegate to
     * {@code DefaultFailoverStore} must ensure this themselves.
     *
     * @param name the referential name (e.g. the entity or endpoint identifier)
     * @param key  the unique key within that referential
     * @return an {@link Optional} containing the stored payload, or empty if not found
     * @throws FailoverStoreException if the underlying lookup operation fails
     */
    Optional<ReferentialPayload<T>> find(String name, String key) throws FailoverStoreException;

    /**
     * Looks up every referential payload stored under the given logical name.
     *
     * <p>Used by the recover-all path, where there is no single key to resolve (e.g. a list
     * endpoint recovering all known entities of a referential).
     *
     * <p><strong>Implementation contract:</strong> like {@link #find}, each returned entry must be
     * a defensive copy, not a live internal reference. Callers mutate the returned payloads (e.g.
     * set {@code upToDate = false}, {@code asOf}) without affecting the data held in the store.
     *
     * <p>Returns an empty list (never {@code null}) when no entry matches the name; not-found is
     * not an error. Ordering is unspecified.
     *
     * @param name the referential name whose entries should be returned
     * @return a list of defensive copies of the stored payloads, or an empty list if none match
     * @throws FailoverStoreException if the underlying lookup operation fails
     */
    List<ReferentialPayload<T>> findAll(String name) throws FailoverStoreException;

    /**
     * Evicts all entries whose expiry instant is before the given cutoff.
     *
     * @param expiry the cutoff instant; entries whose expireOn is before this value are removed
     * @throws FailoverStoreException if the underlying cleanup operation fails
     */
    void cleanByExpiry(Instant expiry) throws FailoverStoreException;
}
