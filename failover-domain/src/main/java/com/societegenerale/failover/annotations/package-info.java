/**
 * Core annotations for declaring failover points.
 *
 * <p>The {@link com.societegenerale.failover.annotations.Failover} annotation is the entry point:
 * place it on any Spring-managed method to register it as a failover point with its name,
 * expiry, key generator, expiry policy, and optional payload splitter.
 */
package com.societegenerale.failover.annotations;
