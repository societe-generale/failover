/**
 * Expiry policy and extractor abstractions for controlling cached payload lifetime.
 *
 * <p>{@link com.societegenerale.failover.core.expiry.ExpiryPolicy} computes a payload's
 * {@code expireOn} timestamp and checks whether it has expired.
 * {@code FailoverExpiryExtractor} resolves the policy bean for a given failover point.
 */
package com.societegenerale.failover.core.expiry;
