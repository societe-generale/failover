/**
 * Non-blocking write decorator for {@link com.societegenerale.failover.core.store.FailoverStore}.
 *
 * <p>{@link com.societegenerale.failover.store.async.FailoverStoreAsync} wraps a delegate store and
 * offloads its mutating operations ({@code store}, {@code delete}, {@code cleanByExpiry}) to a
 * virtual-thread {@code TaskExecutor} so they never block the request thread; {@code find} stays
 * synchronous. Enabled by default ({@code failover.store.async=true}) and assembled as the outermost
 * decorator of the store chain.
 */
package com.societegenerale.failover.store.async;
