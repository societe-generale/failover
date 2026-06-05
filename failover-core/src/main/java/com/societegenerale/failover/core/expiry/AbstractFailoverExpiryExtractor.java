package com.societegenerale.failover.core.expiry;

import com.societegenerale.failover.annotations.Failover;

import java.time.temporal.ChronoUnit;

/**
 * Base {@link FailoverExpiryExtractor} that resolves expiry configuration from a {@code @Failover}
 * annotation, preferring SpEL expression attributes over the literal numeric ones.
 *
 * @author Anand Manissery
 */
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

    /**
     * Resolves the expiry duration from a string expression (e.g. a SpEL expression or property placeholder).
     *
     * @param expression the raw expression string from {@code @Failover#expiryDurationExpression()}
     * @return the resolved numeric duration
     */
    protected abstract long resolveExpiryDuration(String expression);

    /**
     * Resolves the expiry unit from a string expression (e.g. a SpEL expression or property placeholder).
     *
     * @param expression the raw expression string from {@code @Failover#expiryUnitExpression()}
     * @return the resolved {@link ChronoUnit}
     */
    protected abstract ChronoUnit resolveExpiryUnit(String expression);


}
