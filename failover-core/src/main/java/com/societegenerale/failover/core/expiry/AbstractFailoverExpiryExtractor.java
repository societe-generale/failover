package com.societegenerale.failover.core.expiry;

import com.societegenerale.failover.annotations.Failover;

import java.time.temporal.ChronoUnit;

public abstract class AbstractFailoverExpiryExtractor implements FailoverExpiryExtractor {

    @Override
    public long expiryDuration(Failover failover) {
        if(failover.expiryDurationExpression()!=null && !failover.expiryDurationExpression().isBlank()) {
            return resolveExpiryDuration(failover.expiryDurationExpression());
        }
        return failover.expiryDuration();
    }

    @Override
    public ChronoUnit expiryUnit(Failover failover) {
        if(failover.expiryUnitExpression()!=null && !failover.expiryUnitExpression().isBlank()) {
            return resolveExpiryUnit(failover.expiryUnitExpression());
        }
        return failover.expiryUnit();
    }

    protected abstract long resolveExpiryDuration(String expression);

    protected abstract ChronoUnit resolveExpiryUnit(String expression);


}
