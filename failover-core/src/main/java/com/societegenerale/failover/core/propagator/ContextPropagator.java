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

package com.societegenerale.failover.core.propagator;

import com.societegenerale.failover.core.ScatterGatherFailoverHandler;
import org.jspecify.annotations.NonNull;

import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Supplier;

/**
 * Strategy for propagating thread-bound context (tenant ID, security principal, MDC trace)
 * across executor boundaries in scatter/gather operations.
 *
 * <p>Implementations capture context on the calling (request) thread when {@link #wrap} is
 * invoked, then restore it before executing the task on the executor thread.
 *
 * <p>The default no-op implementation ({@link #noOp()}) is suitable when no thread-bound
 * context needs to be propagated. Applications using a {@link ThreadLocal}-based tenant context
 * (e.g. {@code TenantContext}) should supply a custom implementation.
 *
 * <h2>Example — tenant propagator</h2>
 * <pre>{@code
 * ContextPropagator tenantPropagator = task -> {
 *     String tenantId = TenantContext.get();          // captured on the calling thread
 *     return () -> {
 *         if (tenantId != null) TenantContext.set(tenantId);
 *         try { task.run(); } finally { TenantContext.clear(); }
 *     };
 * };
 * }</pre>
 *
 * @author Anand Manissery
 * @see ScatterGatherFailoverHandler
 */
@FunctionalInterface
public interface ContextPropagator {

    /**
     * Captures the current thread context and returns a {@link Runnable} that restores it
     * before running {@code task} on the executor thread.
     *
     * <p><b>Must be called on the originating (request) thread</b> so that context is
     * captured before the executor boundary is crossed.
     *
     * @param task the task to wrap with context propagator
     * @return a context-restoring wrapper around {@code task}
     */
    @NonNull Runnable wrap(@NonNull Runnable task);

    /**
     * Variant of {@link #wrap} for tasks that produce a value.
     *
     * <p>Context is captured when this method is called (on the request thread).
     * The returned {@link Supplier} restores context and produces the value on the executor thread.
     *
     * @param task the value-producing task to wrap
     * @param <V>  the return type
     * @return a context-restoring wrapper around {@code task}
     */
    default <V> Supplier<V> wrapSupplier(Supplier<V> task) {
        AtomicReference<V> ref = new AtomicReference<>();
        Runnable wrapped = wrap(() -> ref.set(task.get()));
        return () -> { wrapped.run(); return ref.get(); };
    }

    /**
     * No-op propagator — passes tasks through unchanged.
     * Suitable when no thread-bound context needs to be propagated.
     *
     * @return a propagator that returns the task unchanged
     */
    static @NonNull ContextPropagator noOp() {
        return task -> task;
    }
}
