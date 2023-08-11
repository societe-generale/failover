package com.societegenerale.failover.core.expiry;

import com.societegenerale.failover.annotations.Failover;

import java.time.temporal.ChronoUnit;

public interface FailoverExpiryExtractor {

    long expiryDuration(Failover failover);

    ChronoUnit expiryUnit(Failover failover);
}
