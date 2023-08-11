package com.societegenerale.failover.core.expiry;

import com.societegenerale.failover.annotations.Failover;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import static java.time.temporal.ChronoUnit.DAYS;
import static java.time.temporal.ChronoUnit.HOURS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class AbstractFailoverExpiryExtractorTest {

    @Mock
    private Failover failover;

    private final AbstractFailoverExpiryExtractor failoverExpiryExtractor = new BasicFailoverExpiryExtractor();

    @Test
    void shouldReturnExpiryDurationWhenExpiryDurationExpressionIsEmpty() {
        given(failover.expiryDurationExpression()).willReturn("");
        given(failover.expiryDuration()).willReturn(1L);
        var result = failoverExpiryExtractor.expiryDuration(failover);
        assertThat(result).isOne();
    }

    @Test
    void shouldReturnExpiryDurationWhenExpiryDurationExpressionIsBlank() {
        given(failover.expiryDurationExpression()).willReturn("  ");
        given(failover.expiryDuration()).willReturn(1L);
        var result = failoverExpiryExtractor.expiryDuration(failover);
        assertThat(result).isOne();
    }

    @Test
    void shouldReturnExpiryDurationFromExpressionWhenExpiryDurationExpressionIsNotBlank() {
        given(failover.expiryDurationExpression()).willReturn("10");
        var result = failoverExpiryExtractor.expiryDuration(failover);
        assertThat(result).isEqualTo(10L);
    }

    @Test
    void shouldReturnExpiryUnitWhenExpiryUnitExpressionIsEmpty() {
        given(failover.expiryUnitExpression()).willReturn("");
        given(failover.expiryUnit()).willReturn(HOURS);
        var result = failoverExpiryExtractor.expiryUnit(failover);
        assertThat(result).isEqualTo(HOURS);
    }

    @Test
    void shouldReturnExpiryUnitWhenExpiryUnitExpressionIsBlank() {
        given(failover.expiryUnitExpression()).willReturn("  ");
        given(failover.expiryUnit()).willReturn(HOURS);
        var result = failoverExpiryExtractor.expiryUnit(failover);
        assertThat(result).isEqualTo(HOURS);
    }

    @Test
    void shouldReturnExpiryUnitFromExpressionWhenExpiryUnitExpressionIsNotBlank() {
        given(failover.expiryUnitExpression()).willReturn("DAYS");
        var result = failoverExpiryExtractor.expiryUnit(failover);
        assertThat(result).isEqualTo(DAYS);
    }

    @Test
    void shouldReturnExpiryUnitFromExpressionWhenExpiryUnitExpressionIsInLowerCase() {
        given(failover.expiryUnitExpression()).willReturn("days");
        var result = failoverExpiryExtractor.expiryUnit(failover);
        assertThat(result).isEqualTo(DAYS);
    }
}