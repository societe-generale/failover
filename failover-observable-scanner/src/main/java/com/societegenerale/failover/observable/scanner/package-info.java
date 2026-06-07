/**
 * Spring-native {@link com.societegenerale.failover.core.observable.scanner.FailoverScanner} implementation.
 * Uses the {@link org.springframework.context.ApplicationContext} and Spring reflection utilities
 * to discover {@code @Failover}-annotated methods — no classpath scanning or external libraries required.
 */
package com.societegenerale.failover.observable.scanner;
