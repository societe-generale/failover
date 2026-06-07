/**
 * Reporting and metrics publication for the failover lifecycle.
 *
 * <p>{@link com.societegenerale.failover.core.report.FailoverObserver} emits startup, store,
 * and recover events. Built-in publishers target SLF4J and Micrometer.
 * {@link com.societegenerale.failover.core.report.CompositeObservablePublisher} fans out to
 * multiple publishers simultaneously.
 */
package com.societegenerale.failover.core.observable;
