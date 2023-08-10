package com.societegenerale.failover.core.expiry;

import java.time.temporal.ChronoUnit;

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
