/**
 * Clock abstraction for failover expiry calculations.
 *
 * <p>{@link com.societegenerale.failover.core.clock.FailoverClock} decouples the framework
 * from {@code System.currentTimeMillis()} so that expiry logic is testable with a fixed clock.
 */
package com.societegenerale.failover.core.clock;
