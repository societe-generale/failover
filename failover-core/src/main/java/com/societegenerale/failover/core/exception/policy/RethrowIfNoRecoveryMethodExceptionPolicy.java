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

package com.societegenerale.failover.core.exception.policy;

import com.societegenerale.failover.core.exception.MethodExceptionContext;

/**
 * {@link MethodExceptionPolicy} that serves recovered data when available, and cascades
 * the original exception only when there is nothing to recover.
 *
 * <p>Decision logic:
 * <ul>
 *   <li>If {@code recoveredResult} is non-null — return it; the caller is served stale data
 *       transparently and the failure is hidden.</li>
 *   <li>If {@code recoveredResult} is {@code null} (store miss, expiry, or store failure) —
 *       rethrow the original exception so the caller can react to the outage explicitly.</li>
 * </ul>
 *
 * <p>This is the "best-effort" policy: prefer stale data, but be honest when even that is
 * unavailable.
 *
 * @author Anand Manissery
 */
public class RethrowIfNoRecoveryMethodExceptionPolicy implements MethodExceptionPolicy {

    @Override
    public <T> T handle(MethodExceptionContext<T> context) {
        if (context.recoveredResult() != null) {
            return context.recoveredResult();
        }
        return sneakyThrow(context.cause());
    }

    @SuppressWarnings("unchecked")
    private <E extends Throwable, T> T sneakyThrow(Throwable cause) throws E {
        throw (E) cause;
    }
}
