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

import com.societegenerale.failover.core.propagator.CompositeContextPropagator;
import com.societegenerale.failover.core.propagator.ContextPropagator;
import com.societegenerale.failover.core.propagator.MdcContextPropagator;
import org.jspecify.annotations.NonNull;

/**
 * {@link ContextPropagator} that propagates the current tenant ID from {@link TenantContext}
 * across executor boundaries in scatter/gather operations.
 *
 * <p>The tenant ID is captured on the calling (request) thread when {@link #wrap} is called.
 * On the executor thread, the captured ID is set via {@link TenantContext#set(String)} before
 * the task runs, and the previous state is fully restored in a {@code finally} block afterward.
 *
 * <p>This propagator is auto-configured and wired into a {@link CompositeContextPropagator}
 * alongside {@link MdcContextPropagator} when
 * {@code failover.store.multitenant.enabled=true}.
 *
 * <p>Without this propagator, scatter/gather slices dispatched to executor threads would
 * call {@link TenantContext#get()} and receive {@code null}, causing
 * {@link MultiTenantFailoverStore} to fall back to the default tenant — routing slices
 * to the wrong store.
 *
 * @author Anand Manissery
 * @see TenantContext
 * @see MultiTenantFailoverStore
 */
public class TenantContextPropagator implements ContextPropagator {

    @Override
    public @NonNull Runnable wrap(@NonNull Runnable task) {
        String capturedTenantId = TenantContext.get();
        return () -> {
            String previousTenantId = TenantContext.get();
            apply(capturedTenantId);
            try {
                task.run();
            } finally {
                apply(previousTenantId);
            }
        };
    }

    private static void apply(String tenantId) {
        if (tenantId != null) {
            TenantContext.set(tenantId);
        } else {
            TenantContext.clear();
        }
    }
}