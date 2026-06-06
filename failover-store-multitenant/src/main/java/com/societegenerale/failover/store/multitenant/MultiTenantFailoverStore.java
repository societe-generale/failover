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

package com.societegenerale.failover.store.multitenant;

import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.core.store.FailoverStoreException;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.time.LocalDateTime;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * Outermost {@link FailoverStore} decorator that routes every operation to the correct
 * per-tenant store. Always the outermost bean when multi-tenant mode is enabled.
 *
 * <h2>Decorator chain</h2>
 *
 * <p>When {@code failover.store.async=true} (default):
 * <pre>
 *   MultiTenantFailoverStore          ← this class; routes per tenant
 *     ├─ tenant-a → FailoverStoreAsync → DefaultFailoverStore → rawStore-a
 *     └─ tenant-b → FailoverStoreAsync → DefaultFailoverStore → rawStore-b
 * </pre>
 *
 * <p>When {@code failover.store.async=false}:
 * <pre>
 *   MultiTenantFailoverStore          ← this class; routes per tenant
 *     ├─ tenant-a → DefaultFailoverStore → rawStore-a
 *     └─ tenant-b → DefaultFailoverStore → rawStore-b
 * </pre>
 *
 * <p>The per-tenant decorated chain is built lazily on first access (or eagerly via
 * {@link #prewarm}) using the {@code decorator} function injected by the autoconfiguration.
 *
 * <h2>Threading</h2>
 * <p>Tenant resolution ({@link TenantResolver#resolve()}) is always called on the
 * <b>calling thread</b>, before any executor boundary is crossed.  The per-tenant store
 * returned by {@link #tenantStore()} is already fully bound; the inner
 * {@code FailoverStoreAsync} (when present) submits work to the executor without needing
 * to re-read any {@code ThreadLocal}.
 *
 * @param <T> the type of the payload
 * @author Anand Manissery
 */
@Slf4j
@RequiredArgsConstructor
public class MultiTenantFailoverStore<T> implements FailoverStore<T> {

    private final TenantResolver tenantResolver;

    /** Creates a raw {@link FailoverStore} for a given tenant ID. */
    private final TenantStoreFactory<T> rawFactory;

    /**
     * Applies the standard decorator chain to a raw store.
     * Injected by autoconfiguration; mirrors what is done in single-tenant mode.
     * Example: {@code raw -> new FailoverStoreAsync<>(new DefaultFailoverStore<>(raw), executor)}
     */
    private final UnaryOperator<FailoverStore<T>> decorator;

    /** Fallback tenant when {@link TenantResolver#resolve()} returns {@code null}. */
    private final String defaultTenant;

    private final ConcurrentHashMap<String, FailoverStore<T>> stores = new ConcurrentHashMap<>();

    /**
     * Stores the payload in the current tenant's failover store.
     *
     * @param payload the payload to persist
     * @throws FailoverStoreException if the delegate store operation fails
     */
    @Override
    public void store(ReferentialPayload<T> payload) {
        tenantStore().store(payload);
    }

    /**
     * Deletes the payload from the current tenant's failover store.
     *
     * @param payload the payload to remove
     * @throws FailoverStoreException if the delegate delete operation fails
     */
    @Override
    public void delete(ReferentialPayload<T> payload) {
        tenantStore().delete(payload);
    }

    /**
     * Looks up a payload by name and key in the current tenant's failover store.
     *
     * @param name the referential name
     * @param key  the unique key within that referential
     * @return an {@link Optional} containing the payload if found, or empty
     * @throws FailoverStoreException if the delegate lookup operation fails
     */
    @Override
    public Optional<ReferentialPayload<T>> find(String name, String key) {
        return tenantStore().find(name, key);
    }

    /**
     * Calls {@code cleanByExpiry} on every tenant store that has been initialised.
     * Called by the scheduler — not on a request thread, so no tenant resolution is needed.
     */
    @Override
    public void cleanByExpiry(LocalDateTime expiry) {
        stores.values().forEach(s -> s.cleanByExpiry(expiry));
    }

    /**
     * Pre-warms the store for each known tenant so {@link #cleanByExpiry} covers all tenants
     * from application startup, not just those that have received a request.
     *
     * @param tenantIds the set of tenant IDs to pre-initialise
     */
    public void prewarm(Set<String> tenantIds) {
        tenantIds.forEach(id -> stores.computeIfAbsent(id, this::buildDecoratedStore));
        log.info("MultiTenantFailoverStore pre-warmed for {} tenant(s): {}", stores.size(), stores.keySet());
    }

    /**
     * Resolves the current tenant on the <b>calling thread</b> and returns the corresponding
     * fully-decorated store.  Falls back to {@code defaultTenant} when the resolver returns
     * {@code null}.
     *
     * @throws FailoverStoreException if both the resolver and {@code defaultTenant} are {@code null}
     */
    private FailoverStore<T> tenantStore() {
        String id = tenantResolver.resolve();
        if (id == null) {
            id = defaultTenant;
        }
        if (id == null) {
            throw new FailoverStoreException(
                    "No tenant resolved and no default-tenant configured. " +
                    "Set failover.store.multitenant.default-tenant or ensure TenantResolver returns a non-null value.");
        }
        return stores.computeIfAbsent(id, this::buildDecoratedStore);
    }

    private FailoverStore<T> buildDecoratedStore(String tenantId) {
        log.debug("Building decorated store for tenant '{}'", tenantId);
        return decorator.apply(rawFactory.create(tenantId));
    }
}