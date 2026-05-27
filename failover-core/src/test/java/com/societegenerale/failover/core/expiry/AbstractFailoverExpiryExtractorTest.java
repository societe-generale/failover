package com.societegenerale.failover.core.expiry;

import com.societegenerale.failover.annotations.Failover;
import org.junit.jupiter.api.DisplayName;
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
    @DisplayName("should return expiry duration when expiry duration expression is empty")
    void shouldReturnExpiryDurationWhenExpiryDurationExpressionIsEmpty() {
        given(failover.expiryDurationExpression()).willReturn("");
        given(failover.expiryDuration()).willReturn(1L);
        var result = failoverExpiryExtractor.expiryDuration(failover);
        assertThat(result).isOne();
    }

    @Test
    @DisplayName("should return expiry duration when expiry duration expression is blank")
    void shouldReturnExpiryDurationWhenExpiryDurationExpressionIsBlank() {
        given(failover.expiryDurationExpression()).willReturn("  ");
        given(failover.expiryDuration()).willReturn(1L);
        var result = failoverExpiryExtractor.expiryDuration(failover);
        assertThat(result).isOne();
    }

    @Test
    @DisplayName("should return expiry duration from expression when expiry duration expression is not blank")
    void shouldReturnExpiryDurationFromExpressionWhenExpiryDurationExpressionIsNotBlank() {
        given(failover.expiryDurationExpression()).willReturn("10");
        var result = failoverExpiryExtractor.expiryDuration(failover);
        assertThat(result).isEqualTo(10L);
    }

    @Test
    @DisplayName("should return expiry unit when expiry unit expression is empty")
    void shouldReturnExpiryUnitWhenExpiryUnitExpressionIsEmpty() {
        given(failover.expiryUnitExpression()).willReturn("");
        given(failover.expiryUnit()).willReturn(HOURS);
        var result = failoverExpiryExtractor.expiryUnit(failover);
        assertThat(result).isEqualTo(HOURS);
    }

    @Test
    @DisplayName("should return expiry unit when expiry unit expression is blank")
    void shouldReturnExpiryUnitWhenExpiryUnitExpressionIsBlank() {
        given(failover.expiryUnitExpression()).willReturn("  ");
        given(failover.expiryUnit()).willReturn(HOURS);
        var result = failoverExpiryExtractor.expiryUnit(failover);
        assertThat(result).isEqualTo(HOURS);
    }

    @Test
    @DisplayName("should return expiry unit from expression when expiry unit expression is not blank")
    void shouldReturnExpiryUnitFromExpressionWhenExpiryUnitExpressionIsNotBlank() {
        given(failover.expiryUnitExpression()).willReturn("DAYS");
        var result = failoverExpiryExtractor.expiryUnit(failover);
        assertThat(result).isEqualTo(DAYS);
    }

    @Test
    @DisplayName("should return expiry unit from expression when expiry unit expression is in lower case")
    void shouldReturnExpiryUnitFromExpressionWhenExpiryUnitExpressionIsInLowerCase() {
        given(failover.expiryUnitExpression()).willReturn("days");
        var result = failoverExpiryExtractor.expiryUnit(failover);
        assertThat(result).isEqualTo(DAYS);
    }
}