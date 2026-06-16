/**
 * Micrometer-backed observability for the failover framework.
 *
 * <p>{@link com.societegenerale.failover.observable.micrometer.MicrometerObservablePublisher}
 * translates failover lifecycle events into Micrometer meters — store/recover counters, the
 * per-method recovery outcome metric, and the partial-recovery counter — recorded against the
 * application's {@code MeterRegistry}. The package also contributes a failover health indicator. Active
 * only when Micrometer is on the classpath.
 */
package com.societegenerale.failover.observable.micrometer;
