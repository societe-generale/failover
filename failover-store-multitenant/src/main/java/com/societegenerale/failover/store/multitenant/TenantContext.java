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

import org.jspecify.annotations.Nullable;

/**
 * Thread-local holder for the current tenant ID.
 *
 * <p>Populated at the start of each request (by {@code TenantContextFilter} or equivalent)
 * and cleared in a {@code finally} block so the thread is clean when returned to a pool.
 *
 * <p><b>Threading note:</b> this value is NOT propagated to executor threads. Tenant routing in
 * {@link MultiTenantFailoverStore} reads this context on the calling thread — before any async
 * dispatch — so no propagator is needed.
 *
 * @author Anand Manissery
 */
public final class TenantContext {

    private static final ThreadLocal<String> CURRENT_TENANT = new ThreadLocal<>();

    private TenantContext() {}

    /**
     * Returns the current tenant ID for this thread.
     *
     * @return the current tenant ID, or {@code null} if not set
     */
    @Nullable
    public static String get() {
        return CURRENT_TENANT.get();
    }

    /**
     * Sets the current tenant ID on this thread.
     *
     * @param tenantId the tenant ID to set
     */
    public static void set(String tenantId) {
        CURRENT_TENANT.set(tenantId);
    }

    /** Removes the tenant ID from this thread. Must be called in a {@code finally} block. */
    public static void clear() {
        CURRENT_TENANT.remove();
    }
}