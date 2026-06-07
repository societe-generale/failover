/**
 * Observable publisher SPI and built-in implementations.
 *
 * <p>{@link com.societegenerale.failover.core.observable.publisher.ObservablePublisher} is the
 * core SPI for publishing failover metrics to external sinks.
 * {@link com.societegenerale.failover.core.observable.publisher.AbstractObservablePublisher} provides
 * a base implementation. Default built-in publisher:
 * {@link com.societegenerale.failover.core.observable.publisher.MdcLoggerObservablePublisher}
 * (enriches MDC with metric attributes and logs via SLF4J).
 */
package com.societegenerale.failover.core.observable.publisher;
