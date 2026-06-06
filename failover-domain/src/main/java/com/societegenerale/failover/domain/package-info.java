/**
 * Domain model for failover-aware referential data.
 *
 * <p>Extend {@link com.societegenerale.failover.domain.Referential} or implement
 * {@code ReferentialAware} to receive {@code upToDate}, {@code asOf}, and
 * {@link com.societegenerale.failover.domain.Metadata} fields automatically
 * populated during recovery.
 */
package com.societegenerale.failover.domain;
