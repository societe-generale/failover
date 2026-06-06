/**
 * Thread-local context propagation for async scatter/gather dispatch.
 *
 * <p>{@link com.societegenerale.failover.core.propagator.ContextPropagator} captures context
 * on the calling thread and restores it on executor threads.
 * {@link com.societegenerale.failover.core.propagator.CompositeContextPropagator} chains
 * multiple propagators (e.g. MDC + tenant) into one.
 */
package com.societegenerale.failover.core.propagator;
