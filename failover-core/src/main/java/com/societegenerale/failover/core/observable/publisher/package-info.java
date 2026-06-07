/**
 * Observable publisher SPI and built-in implementations.
 *
 * <p>{@link com.societegenerale.failover.core.observable.publisher.ObservablePublisher} is the
 * core SPI for publishing failover metrics to external sinks.
 * {@link com.societegenerale.failover.core.observable.publisher.AbstractObservablePublisher} provides
 * a base implementation. Built-in publishers:
 * {@link com.societegenerale.failover.core.observable.publisher.MdcLoggerObservablePublisher} (SLF4J) and
 * {@link com.societegenerale.failover.core.observable.publisher.MetricsObservablePublisher} (MDC-based).
 */
package com.societegenerale.failover.core.observable.publisher;
