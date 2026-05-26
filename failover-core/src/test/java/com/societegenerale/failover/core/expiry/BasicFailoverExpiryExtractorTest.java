package com.societegenerale.failover.core.expiry;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static java.time.temporal.ChronoUnit.DAYS;
import static org.assertj.core.api.Assertions.assertThat;

class BasicFailoverExpiryExtractorTest {

    private final BasicFailoverExpiryExtractor basicFailoverExpiryExtractor = new BasicFailoverExpiryExtractor();

    @Test
    @DisplayName("should resolve expiry duration expression")
    void shouldResolveExpiryDurationExpression() {
        var result = basicFailoverExpiryExtractor.resolveExpiryDuration("1");
        assertThat(result).isOne();
    }

    @Test
    @DisplayName("resolve expiry unit expression")
    void resolveExpiryUnitExpression() {
        var result = basicFailoverExpiryExtractor.resolveExpiryUnit("DAYS");
        assertThat(result).isEqualTo(DAYS);
    }

    @Test
    @DisplayName("resolve expiry unit expression when unit in lower case")
    void resolveExpiryUnitExpressionWhenUnitInLowerCase() {
        var result = basicFailoverExpiryExtractor.resolveExpiryUnit("days");
        assertThat(result).isEqualTo(DAYS);
    }
}