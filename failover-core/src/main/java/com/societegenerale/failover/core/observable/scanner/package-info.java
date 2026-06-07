/**
 * Classpath scanner that discovers {@code @Failover}-annotated methods at startup.
 *
 * <p>{@link com.societegenerale.failover.core.scanner.FailoverScanner} uses the Reflections
 * library to locate all methods annotated with
 * {@link com.societegenerale.failover.annotations.Failover} and registers them with the
 * handler and store.
 */
package com.societegenerale.failover.core.observable.scanner;
