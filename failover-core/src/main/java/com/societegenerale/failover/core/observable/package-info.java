/**
 * Observable SPI and core implementations for the failover lifecycle.
 *
 * <p>{@link com.societegenerale.failover.core.observable.FailoverObserver} collects metrics
 * from all registered {@code @Failover} configurations and fans them out to
 * {@link com.societegenerale.failover.core.observable.publisher.ObservablePublisher} instances.
 * {@link com.societegenerale.failover.core.observable.publisher.CompositeObservablePublisher}
 * stamps a single publish timestamp and delegates to all registered publishers simultaneously.
 */
package com.societegenerale.failover.core.observable;
