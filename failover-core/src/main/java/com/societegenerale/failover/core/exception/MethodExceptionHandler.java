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

package com.societegenerale.failover.core.exception;

import com.societegenerale.failover.core.exception.policy.MethodExceptionPolicy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Default {@link MethodExceptionPolicy} — graceful handling with no exception propagator.
 *
 * <p>Returns whatever the failover recovery produced: the recovered result if the store had a
 * valid entry, or {@code null} if recovery failed or the store was empty. The original exception
 * is logged at {@code WARN} and swallowed so the calling thread is never interrupted by a
 * failover-internal failure.
 *
 * <p>This is the safest policy: the application continues with stale or absent data rather than
 * surfacing an error to the end user.
 *
 * @author Anand Manissery
 */
@RequiredArgsConstructor
@Slf4j
public class MethodExceptionHandler {

    private final MethodExceptionPolicy methodExceptionPolicy;

    /**
     * Handles the exception from a failed primary method call by delegating to the configured policy.
     *
     * @param <T>     the return type of the failed method
     * @param context full context of the failure, including the recovered result (may be {@code null})
     * @return the result determined by the policy (recovered value, {@code null}, or rethrows)
     */
    public <T> T handle(MethodExceptionContext<T> context) {
        log.debug("Failover: primary call failed for '{}'. Returning recovered result (may be null). Cause: {}",
                context.method().getName(), context.cause().getMessage(), context.cause());
        return methodExceptionPolicy.handle(context);
    }
}
