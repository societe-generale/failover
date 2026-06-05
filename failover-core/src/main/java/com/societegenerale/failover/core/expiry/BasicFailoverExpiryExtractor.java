package com.societegenerale.failover.core.expiry;

import java.time.temporal.ChronoUnit;

/**
 * {@link AbstractFailoverExpiryExtractor} that interprets expression strings as plain literals:
 * durations are parsed with {@link Long#parseLong} and units with {@link ChronoUnit#valueOf}.
 *
 * @author Anand Manissery
 */
public class BasicFailoverExpiryExtractor extends AbstractFailoverExpiryExtractor {

    @Override
    protected long resolveExpiryDuration(String expression) {
        return Long.parseLong(expression);
    }

    @Override
    protected ChronoUnit resolveExpiryUnit(String expression) {
        return ChronoUnit.valueOf(expression.toUpperCase());
    }
}
