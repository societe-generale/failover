/**
 * Central failover handler and execution abstractions.
 *
 * <p>Key types:
 * <ul>
 *   <li>{@link com.societegenerale.failover.core.DefaultFailoverHandler} — store + recover pipeline</li>
 *   <li>{@link com.societegenerale.failover.core.AdvancedFailoverHandler} — adds async, enrichment, and policy hooks</li>
 *   <li>{@link com.societegenerale.failover.core.ScatterGatherFailoverHandler} — parallel slice store/recover</li>
 * </ul>
 */
package com.societegenerale.failover.core;
