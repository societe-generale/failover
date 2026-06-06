/**
 * Pluggable policies that govern what happens when a failover method throws.
 *
 * <p>Two built-in implementations:
 * <ul>
 *   <li>{@link com.societegenerale.failover.core.exception.policy.NeverRethrowMethodExceptionPolicy}
 *       — always return recovered data (or {@code null} on a store miss)</li>
 *   <li>{@link com.societegenerale.failover.core.exception.policy.RethrowIfNoRecoveryMethodExceptionPolicy}
 *       — return recovered data when available, rethrow when the store has nothing</li>
 * </ul>
 */
package com.societegenerale.failover.core.exception.policy;
