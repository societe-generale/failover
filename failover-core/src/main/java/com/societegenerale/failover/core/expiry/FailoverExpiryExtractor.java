package com.societegenerale.failover.core.expiry;

import com.societegenerale.failover.annotations.Failover;

import java.time.temporal.ChronoUnit;

/**
 * Extracts expiry configuration (duration and unit) from a {@code @Failover} annotation.
 *
 * @author Anand Manissery
 */
public interface FailoverExpiryExtractor {

    /**
     * Returns the expiry duration configured on the annotation.
     *
     * @param failover the annotation to inspect
     * @return numeric expiry duration
     */
    long expiryDuration(Failover failover);

    /**
     * Returns the time unit for the expiry duration configured on the annotation.
     *
     * @param failover the annotation to inspect
     * @return the {@link ChronoUnit} for the expiry duration
     */
    ChronoUnit expiryUnit(Failover failover);
}
