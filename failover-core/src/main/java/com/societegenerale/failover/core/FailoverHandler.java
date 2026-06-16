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

package com.societegenerale.failover.core;

import com.societegenerale.failover.annotations.Failover;
import org.jspecify.annotations.NonNull;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Core handler for failover lifecycle operations: storing payloads, recovering them on failure,
 * and cleaning up expired entries.
 *
 * <p>Every operation carries the reflected intercepted {@code method}, so the outermost handler can
 * tag observability per intercepted method (e.g. {@code CountryService#findAll}). Handlers that do
 * not need the method should extend {@link AbstractFailoverHandler}, which bridges these signatures
 * to a clean method-less {@code (Failover, …)} form so subclasses implement only that.
 *
 * @param <T> the type of the payload managed by this handler
 * @author Anand Manissery
 * @see AbstractFailoverHandler
 */
public interface FailoverHandler<T> {

    /**
     * Stores the payload for later recovery.
     *
     * @param failover annotation metadata for the failover point
     * @param method   the reflected intercepted method (never {@code null})
     * @param args     method arguments used to derive the store key
     * @param payload  the result to store
     * @return the stored payload
     */
    T store(@NonNull Failover failover, @NonNull Method method, List<Object> args, T payload);

    /**
     * Recovers a previously stored payload after a failure.
     *
     * @param failover  annotation metadata for the failover point
     * @param method    the reflected intercepted method (never {@code null})
     * @param args      method arguments used to derive the lookup key
     * @param clazz     expected return type
     * @param throwable the exception that triggered recovery
     * @return the recovered payload, or {@code null} if not found or expired
     */
    T recover(@NonNull Failover failover, @NonNull Method method, List<Object> args, Class<T> clazz, Throwable throwable);

    /**
     * Recovers every stored entry for the failover's referential (the recover-all / {@code findAll} path).
     *
     * <p><strong>This is an optional operation.</strong> It is meaningful only for the innermost
     * <em>store-backed</em> handler that can enumerate a referential — {@link DefaultFailoverHandler},
     * which overrides it. Decorator and composite handlers
     * ({@link AdvancedFailoverHandler}, {@link ScatterGatherFailoverHandler}) drive recover-all through
     * {@link #recover} instead (the scatter path dispatches one {@code recoverAll} per slice onto its
     * slice delegate), so they do not implement this method and inherit the default.
     *
     * <p><strong>Implementation contract:</strong> The default throws {@link UnsupportedOperationException}, following the JDK
     * "optional operation" convention (cf. {@code Collection.add} on an immutable collection). Only call
     * this on a handler documented to support it — i.e. the slice/store-level recover-all delegate, never
     * a decorator reference. Implementations that support it must return a non-null list (empty, never
     * {@code null}, when the referential has no live entries) and must not throw for the empty case.
     *
     * @param failover  annotation metadata for the failover point
     * @param method    the reflected intercepted method (never {@code null})
     * @param args      method arguments used to derive the lookup key
     * @param clazz     expected return type
     * @param throwable the exception that triggered recovery
     * @return the recovered payloads (never {@code null} for supporting implementations)
     * @throws UnsupportedOperationException if this handler does not support recover-all (the default)
     */
    default List<T> recoverAll(@NonNull Failover failover, @NonNull Method method, List<Object> args, Class<T> clazz, Throwable throwable) {
        throw new UnsupportedOperationException(
                "recoverAll is an optional operation supported only by the store-level recover-all delegate; "
                        + "this handler (" + getClass().getSimpleName() + ") drives recover-all via recover(...)");
    }

    /** Removes all expired entries from the failover store. */
    void clean();
}
