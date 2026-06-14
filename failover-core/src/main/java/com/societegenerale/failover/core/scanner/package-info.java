/**
 * Scanner SPI for discovering {@code @Failover}-annotated methods.
 *
 * <p>{@link com.societegenerale.failover.core.scanner.FailoverScanner} is the core
 * interface. The default implementation ({@code SpringContextFailoverScanner} in the
 * {@code failover-observable-scanner} module) locates all methods annotated with
 * {@link com.societegenerale.failover.annotations.Failover} by walking the Spring
 * {@code ApplicationContext} — no classpath scanning or external libraries required.
 */
package com.societegenerale.failover.core.scanner;
