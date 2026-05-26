package com.societegenerale.failover.core.store;

import com.societegenerale.failover.core.payload.ReferentialPayload;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.LocalDateTime;
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
     * @return an {@link Optional} containing the payload with {@code upToDate=false}, or empty if not found
     * @throws FailoverStoreException if the delegate lookup operation fails
     */
    @Override
    public Optional<ReferentialPayload<T>> find(String name, String key) throws FailoverStoreException {
        return failoverStore.find(name, key).map(r -> r.copy().withUpToDate(false));
    }

    /**
     * Delegates expiry-based cleanup directly to the underlying store without modification.
     *
     * @param expiry the cutoff datetime; entries expiring at or before this value are removed
     * @throws FailoverStoreException if the delegate cleanup operation fails
     */
    @Override
    public void cleanByExpiry(LocalDateTime expiry) throws FailoverStoreException {
        failoverStore.cleanByExpiry(expiry);
    }
}