/**
 * Resilience4j integration for failover method execution.
 *
 * <p>{@link com.societegenerale.failover.execution.resilience.ResilienceFailoverExecution}
 * wraps the underlying method call in a Resilience4j
 * {@code TimeLimiter} / {@code CircuitBreaker} / {@code Retry} chain,
 * so that transient failures are handled before the failover recovery path is triggered.
 */
package com.societegenerale.failover.execution.resilience;
