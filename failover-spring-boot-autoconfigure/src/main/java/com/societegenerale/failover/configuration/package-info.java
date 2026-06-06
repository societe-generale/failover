/**
 * Spring Boot auto-configuration for the failover framework.
 *
 * <p>Registers the aspect, store, scheduler, scatter/gather executor, and
 * reporter beans.  Select the store type via {@code failover.store.type}
 * ({@code INMEMORY}, {@code CAFFEINE}, {@code JDBC}, or {@code CUSTOM}).
 */
package com.societegenerale.failover.configuration;
