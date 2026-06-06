/**
 * Spring AOP aspect that intercepts {@code @Failover}-annotated methods.
 *
 * <p>{@link com.societegenerale.failover.aspect.FailoverAspect} wraps each annotated method:
 * on success it stores the result; on failure it attempts recovery from the store
 * and delegates exception handling to the configured
 * {@link com.societegenerale.failover.core.exception.policy.MethodExceptionPolicy}.
 */
package com.societegenerale.failover.aspect;
