/**
 * Store abstraction and default in-memory implementation.
 *
 * <p>{@link com.societegenerale.failover.core.store.FailoverStore} defines the four
 * persistence operations: {@code store}, {@code find}, {@code delete},
 * and {@code cleanByExpiry}.  Concrete implementations live in the
 * {@code failover-store-*} modules.
 */
package com.societegenerale.failover.core.store;
