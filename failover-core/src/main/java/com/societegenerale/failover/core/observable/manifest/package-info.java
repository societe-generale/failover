/**
 * Manifest-based metadata extraction for the failover startup report.
 *
 * <p>Reads build-time information (version, build date) from the JAR manifest
 * or classpath resources to enrich the startup report emitted by
 * {@link com.societegenerale.failover.core.report.FailoverObserver}.
 */
package com.societegenerale.failover.core.observable.manifest;
