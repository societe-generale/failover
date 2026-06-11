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

package com.societegenerale.failover.store.multitenant;

import com.societegenerale.failover.core.store.FailoverStore;

/**
 * SPI for creating a raw {@link FailoverStore} for a given tenant.
 *
 * <h2>Threading contract</h2>
 * <p>{@link #create} is always called on the <b>calling (request) thread</b>, never inside an
 * executor or any other thread. This is guaranteed by the fact that {@code MultiTenantFailoverStore}
 * is always the <em>outermost</em> decorator in the chain:
 *
 * <pre>
 *   Calling thread:
 *     MultiTenantFailoverStore.store(payload)
 *       → tenantStore()              ← reads TenantContext (ThreadLocal) here, on calling thread
 *       → factory.create(tenantId)   ← called here if first access for this tenant
 *       → FailoverStoreAsync.store() ← submits to executor; no ThreadLocal needed inside lambda
 *         → executor thread: DefaultFailoverStore.store() → rawStore.store()
 * </pre>
 *
 * <p>Because {@link #create} is called before any thread boundary is crossed, implementations
 * may safely read {@code ThreadLocal} values (e.g. to select a schema or DataSource for the
 * current tenant), but they must NOT store those values and rely on them being available later
 * on a different thread.
 *
 * <p>In single-tenant mode {@link #create} is called once at startup with {@link #SINGLE_TENANT_ID};
 * implementations may ignore the tenant ID in that case.
 *
 * @param <T> the type of the payload
 * @author Anand Manissery
 */
@FunctionalInterface
public interface TenantStoreFactory<T> {

    /**
     * Sentinel tenant ID used in single-tenant mode.
     * Passed to {@link #create} once at application startup; implementations may ignore it.
     */
    String SINGLE_TENANT_ID = "_single_";

    /**
     * Creates a raw {@link FailoverStore} for the given {@code tenantId}.
     *
     * <p>Always called on the calling (request) thread — never from inside an executor.
     * See class-level Javadoc for the threading guarantee.
     *
     * @param tenantId the tenant identifier; {@link #SINGLE_TENANT_ID} in single-tenant mode
     * @return raw {@link FailoverStore} for that tenant (decorator wrapping is applied by the assembler)
     */
    FailoverStore<T> create(String tenantId);
}