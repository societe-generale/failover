/**
 * Exception model for method-level failover handling.
 *
 * <p>{@link com.societegenerale.failover.core.exception.MethodExceptionContext} carries the
 * original exception and any recovered result so that a
 * {@link com.societegenerale.failover.core.exception.policy.MethodExceptionPolicy} can decide
 * whether to return stale data or rethrow.
 */
package com.societegenerale.failover.core.exception;
