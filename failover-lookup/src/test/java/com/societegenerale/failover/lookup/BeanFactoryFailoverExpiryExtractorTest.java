package com.societegenerale.failover.lookup;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.config.ConfigurableBeanFactory;

import static java.time.temporal.ChronoUnit.DAYS;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;

@ExtendWith(MockitoExtension.class)
class BeanFactoryFailoverExpiryExtractorTest {

    @Mock
    private ConfigurableBeanFactory beanFactory;

    private BeanFactoryFailoverExpiryExtractor beanFactoryFailoverExpiryExtractor;

    @BeforeEach
    void setUp() {
        beanFactoryFailoverExpiryExtractor = new BeanFactoryFailoverExpiryExtractor();
        beanFactoryFailoverExpiryExtractor.setBeanFactory(beanFactory);
    }

    @Test
    @DisplayName("should resolve expiry duration")
    void shouldResolveExpiryDuration() {
        var expression = "${expiry-duration-expression}";
        given(beanFactory.resolveEmbeddedValue(expression)).willReturn("10");
        var result = beanFactoryFailoverExpiryExtractor.resolveExpiryDuration(expression);
        assertThat(result).isEqualTo(10L);
    }

    @Test
    @DisplayName("should resolve expiry unit")
    void shouldResolveExpiryUnit() {
        var expression = "${expiry-unit-expression}";
        given(beanFactory.resolveEmbeddedValue(expression)).willReturn("DAYS");
        var result = beanFactoryFailoverExpiryExtractor.resolveExpiryUnit(expression);
        assertThat(result).isEqualTo(DAYS);
    }

    @Test
    @DisplayName("should resolve expiry unit in lower case")
    void shouldResolveExpiryUnitInLowerCase() {
        var expression = "${expiry-unit-expression}";
        given(beanFactory.resolveEmbeddedValue(expression)).willReturn("days");
        var result = beanFactoryFailoverExpiryExtractor.resolveExpiryUnit(expression);
        assertThat(result).isEqualTo(DAYS);
    }

}