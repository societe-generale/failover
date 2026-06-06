/**
 * Multi-tenant failover store with per-tenant routing.
 *
 * <p>{@link com.societegenerale.failover.store.multitenant.MultiTenantFailoverStore} delegates
 * all store operations to a tenant-specific {@code FailoverStore} resolved at runtime by
 * {@link com.societegenerale.failover.store.multitenant.TenantResolver}.
 *
 * <p>{@link com.societegenerale.failover.store.multitenant.TenantContext} holds the current
 * tenant ID in a {@code ThreadLocal}.
 * {@link com.societegenerale.failover.store.multitenant.TenantContextPropagator} propagates
 * it across executor threads for scatter/gather operations.
 */
package com.societegenerale.failover.store.multitenant;
