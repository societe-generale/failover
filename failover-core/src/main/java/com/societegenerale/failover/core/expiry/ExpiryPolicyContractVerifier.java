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
import java.time.temporal.ChronoUnit;

/**
 * Reusable test harness that checks a custom {@link ExpiryPolicy} against the contract every
 * implementation must honour. SPI implementors drop this into a unit test and call {@link #verify()}
 * to catch the common mistakes — a {@code null} expiry, an {@code isExpired} that ignores the stored
 * {@code expireOn}, or a {@code computeExpiry} that returns an already-past instant.
 *
 * <p>This class is dependency-free (no test library on the classpath) and throws {@link AssertionError}
 * on the first violation, so it composes with JUnit, TestNG, or a plain {@code main}.
 *
 * <h2>Usage</h2>
 * <pre>{@code
 * @Test
 * void honoursTheExpiryContract() {
 *     Failover failover = ...;            // a mock/stub configured with your duration + unit
 *     ExpiryPolicyContractVerifier.forPolicy(myPolicy)
 *             .withFailover(failover)
 *             .withSamplePayload(new Country("FR"))
 *             .verify();
 * }
 * }</pre>
 *
 * <p>The harness verifies the <b>standard {@code expireOn}-based contract</b>: {@code isExpired} is
 * driven by the payload's {@code expireOn} field. Policies that derive expiry from a payload field
 * instead (payload-driven) should test that bespoke logic directly — call
 * {@link #verifyComputeExpiry()} for the {@code computeExpiry} checks only.
 *
 * @param <T> the payload type whose expiry the policy governs
 * @author Anand Manissery
 */
public final class ExpiryPolicyContractVerifier<T> {

    private static final String NAME = "contract-verifier";
    private static final String KEY = "contract-key";

    private final ExpiryPolicy<T> policy;
    private Failover failover;
    private T samplePayload;

    private ExpiryPolicyContractVerifier(ExpiryPolicy<T> policy) {
        if (policy == null) {
            throw new IllegalArgumentException("policy must not be null");
        }
        this.policy = policy;
    }

    /** Starts a verification for the given policy. */
    public static <T> ExpiryPolicyContractVerifier<T> forPolicy(ExpiryPolicy<T> policy) {
        return new ExpiryPolicyContractVerifier<>(policy);
    }

    /** The {@link Failover} metadata passed to the policy — typically a Mockito mock with the duration/unit stubbed. */
    public ExpiryPolicyContractVerifier<T> withFailover(Failover failover) {
        this.failover = failover;
        return this;
    }

    /** A representative payload instance stored in the synthetic {@link ReferentialPayload} used for the checks. */
    public ExpiryPolicyContractVerifier<T> withSamplePayload(T samplePayload) {
        this.samplePayload = samplePayload;
        return this;
    }

    /** Runs the full standard contract: {@code computeExpiry} checks plus the {@code expireOn}-based {@code isExpired} checks. */
    public void verify() {
        Instant expiry = verifyComputeExpiry();
        verifyIsExpired(expiry);
    }

    /**
     * Verifies only the {@code computeExpiry} contract and returns the computed instant:
     * it must be non-null and strictly in the future (a freshly computed expiry is never already past).
     */
    public Instant verifyComputeExpiry() {
        requireFailover();
        Instant before = Instant.now();
        Instant expiry = policy.computeExpiry(failover);
        if (expiry == null) {
            throw new AssertionError("computeExpiry(failover) returned null — it must return the absolute expiry instant");
        }
        if (expiry.isBefore(before)) {
            throw new AssertionError("computeExpiry(failover) returned a past instant (" + expiry
                    + "); a freshly stored payload would already be expired");
        }
        return expiry;
    }

    private void verifyIsExpired(Instant freshExpiry) {
        // A just-stored entry (expireOn == computeExpiry) must NOT be reported expired.
        if (policy.isExpired(failover, payload(freshExpiry))) {
            throw new AssertionError("isExpired(...) reported a just-stored payload (expireOn=" + freshExpiry
                    + ") as expired — store-time and recover-time clocks disagree");
        }
        // An entry whose expireOn is far in the past MUST be expired.
        Instant past = Instant.now().minus(3650, ChronoUnit.DAYS);
        if (!policy.isExpired(failover, payload(past))) {
            throw new AssertionError("isExpired(...) did not report an entry with expireOn=" + past
                    + " (10 years ago) as expired — does it read the payload's expireOn?");
        }
        // An entry whose expireOn is far in the future MUST NOT be expired.
        Instant future = Instant.now().plus(3650, ChronoUnit.DAYS);
        if (policy.isExpired(failover, payload(future))) {
            throw new AssertionError("isExpired(...) reported an entry with expireOn=" + future
                    + " (10 years ahead) as expired — does it read the payload's expireOn?");
        }
    }

    private ReferentialPayload<T> payload(Instant expireOn) {
        return new ReferentialPayload<>(NAME, KEY, false, Instant.now(), expireOn, samplePayload);
    }

    private void requireFailover() {
        if (failover == null) {
            throw new IllegalStateException("withFailover(...) must be called before verify()");
        }
    }
}
