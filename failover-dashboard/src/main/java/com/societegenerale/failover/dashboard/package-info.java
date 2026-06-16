/**
 * Embedded, opt-in, secure-by-default observability dashboard for failover. Consumes the existing
 * {@link com.societegenerale.failover.core.scanner.FailoverScanner} configuration and Micrometer
 * {@code failover.*} meters; introduces no new instrumentation. Obtained only via
 * {@code failover-dashboard-spring-boot-starter} and disabled until {@code failover.dashboard.enabled=true}.
 */
package com.societegenerale.failover.dashboard;
