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

/**
 * {@link MethodExceptionPolicy} that <b>never</b> rethrows: it always returns the recovered result,
 * suppressing the original exception in every case.
 *
 * <p>Decision logic:
 * <ul>
 *   <li>If {@code recoveredResult} is non-null — return it; the caller is served stale data
 *       transparently and the failure is hidden.</li>
 *   <li>If {@code recoveredResult} is {@code null} (store miss, expiry, or store failure) —
 *       return {@code null} (or the {@code RecoveredPayloadHandler} fallback) <b>without</b>
 *       rethrowing. The caller cannot tell an outage occurred from the return value alone.</li>
 * </ul>
 *
 * <p>This is the most lenient policy: callers are never interrupted, at the cost of <b>masking
 * upstream outages</b> from the caller. Because the failure is invisible to the caller, the outage
 * must be observed through metrics — the recover event still fires regardless of policy, so
 * {@code failover.recovery.outcome.total} (outcome {@code not_recovered}) and
 * {@code failover.user.impact.total} (impact {@code blocked}) remain the signal to alert on.
 *
 * <p>Contrast {@link RethrowIfNoRecoveryMethodExceptionPolicy}, the default, which rethrows when there
 * is nothing to recover so the outage surfaces to the caller.
 *
 * @author Anand Manissery
 * @see RethrowIfNoRecoveryMethodExceptionPolicy
 */
public class NeverRethrowMethodExceptionPolicy implements MethodExceptionPolicy {

    @Override
    public <T> T handle(MethodExceptionContext<T> context) {
        return context.recoveredResult();
    }
}
