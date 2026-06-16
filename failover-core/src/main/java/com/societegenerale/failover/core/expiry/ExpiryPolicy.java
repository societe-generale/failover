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

package com.societegenerale.failover.core.expiry;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.payload.ReferentialPayload;

import java.time.Instant;

/**
 * Strategy for computing and checking expiry of stored failover payloads.
 *
 * @param <T> the payload type whose expiry this policy governs
 * @author Anand Manissery
 */
public interface ExpiryPolicy<T> {

    /**
     * Computes the expiry instant for a payload stored under the given failover.
     *
     * <p><strong>Implementation contract:</strong> Must return a non-null instant in the <strong>future</strong> relative to "now" — a
     * freshly stored payload must not already be expired. The result is stored as the payload's
     * {@code expireOn}. Verify your implementation with {@link ExpiryPolicyContractVerifier}.
     *
     * @param failover annotation metadata containing the configured expiry duration and unit
     * @return the absolute instant after which the stored payload should be considered expired
     */
    Instant computeExpiry(Failover failover);

    /**
     * Returns {@code true} if the stored payload has expired.
     *
     * <p><strong>Implementation contract:</strong> Must decide expiry from the payload's {@code expireOn} relative to the current time
     * (consistent with {@link #computeExpiry}): an entry stored a moment ago is not expired; an entry
     * whose {@code expireOn} is in the past is. Must be side-effect-free.
     *
     * @param failover           annotation metadata for the failover point
     * @param referentialPayload the stored payload to check
     * @return {@code true} if the payload's expiry time is in the past
     */
    boolean isExpired(Failover failover, ReferentialPayload<T> referentialPayload);
}
