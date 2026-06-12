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

package com.societegenerale.failover.core.exception.policy;

import com.societegenerale.failover.core.exception.MethodExceptionContext;
import com.societegenerale.failover.core.exception.MethodExceptionHandler;

/**
 * Strategy for deciding the final outcome after a primary method call fails and failover
 * recovery has been attempted.
 *
 * <p>Implementations receive a {@link MethodExceptionContext} containing the original exception,
 * the recovered result (if any), and the full call context. They may:
 * <ul>
 *   <li>return {@code context.recoveredResult()} — serve stale data transparently</li>
 *   <li>return {@code null} — propagate nothing to the caller</li>
 *   <li>rethrow {@code context.cause()} — cascade the original exception to the caller</li>
 * </ul>
 *
 * <p>Built-in implementations:
 * <ul>
 *   <li>{@link MethodExceptionHandler} — default; returns recovered result or {@code null}, never throws</li>
 *   <li>{@link RethrowIfNoRecoveryMethodExceptionPolicy} — returns recovered result if present, otherwise rethrows</li>
 * </ul>
 *
 * <p>Custom policies can be registered as a Spring bean and will be picked up automatically
 * by the auto-configuration.
 *
 * @author Anand Manissery
 */
@FunctionalInterface
public interface MethodExceptionPolicy {

    /**
     * Handles the exception context and returns the value to return to the caller,
     * or throws to propagate the failure.
     *
     * @param <T>     the return type of the intercepted method
     * @param context all available information about the failure and recovery attempt
     * @return the value to return to the caller; may be {@code null}
     */
    <T> T handle(MethodExceptionContext<T> context);
}
