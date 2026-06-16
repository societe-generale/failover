/**
 * Spring {@code BeanFactory}-based lookups that resolve the named {@code KeyGenerator},
 * {@code ExpiryPolicy} and {@code PayloadSplitter} beans referenced by
 * {@link com.societegenerale.failover.annotations.Failover#keyGenerator()},
 * {@link com.societegenerale.failover.annotations.Failover#expiryPolicy()} and
 * {@link com.societegenerale.failover.annotations.Failover#payloadSplitter()}.
 *
 * <p>Each {@code BeanFactory*Lookup} implements the corresponding lookup SPI from
 * {@code failover-core} by fetching the bean by name from the Spring context, so a
 * {@code @Failover} method can point at a custom strategy by its bean name. This module exists to keep
 * the Spring dependency out of {@code failover-core}, which stays framework-agnostic.
 */
package com.societegenerale.failover.lookup;
