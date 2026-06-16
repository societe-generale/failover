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

package com.societegenerale.failover.core.payload;

import com.societegenerale.failover.annotations.Failover;

import java.util.List;

/**
 * Post-processor for payloads recovered from the failover store.
 *
 * <p>Implementations may enrich, transform, or substitute the recovered payload.
 * The default pass-through implementation is {@link PassThroughRecoveredPayloadHandler}.
 *
 * @author Anand Manissery
 */
public interface RecoveredPayloadHandler {
    /**
     * Handles the recovered payload after a failover recovery attempt.
     *
     * <p><strong>Implementation contract:</strong> {@code payload} is {@code null} when nothing was recovered (store miss, expiry, or
     * store failure); implementations must handle that case (e.g. substitute an empty result). Prefer
     * not to throw — a thrown exception is caught by {@code AdvancedFailoverHandler}, logged at
     * {@code ERROR}, and the raw recovered payload is returned unchanged. Returning {@code null} is
     * permitted and propagates to the caller.
     *
     * @param <T>     the payload type
     * @param failover annotation metadata for the failover point
     * @param args     method arguments used to look up the payload
     * @param clazz    expected return type
     * @param payload  the recovered payload, or {@code null} if recovery failed
     * @param cause    the exception that triggered recovery
     * @return the final payload to return to the caller
     */
    <T> T handle(Failover failover, List<Object> args, Class<T> clazz, T payload, Throwable cause);
}
