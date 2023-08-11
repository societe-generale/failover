package com.societegenerale.failover.core.expiry;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.test.context.TestPropertySource;

import static java.time.temporal.ChronoUnit.DAYS;
import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(classes = {BeanFactoryFailoverExpiryExtractorTestIT.TestConfiguration.class})
@TestPropertySource(properties = {"expiry-duration=10", "expiry-unit=DAYS", "expiry-unit-in-lowercase=days"})
class BeanFactoryFailoverExpiryExtractorTestIT {

    @Autowired
    private BeanFactoryFailoverExpiryExtractor beanFactoryFailoverExpiryExtractor;

    @Test
    void shouldResolveExpiryDuration() {
        var result = beanFactoryFailoverExpiryExtractor.resolveExpiryDuration("${expiry-duration}");
        assertThat(result).isEqualTo(10L);
    }

    @Test
    void shouldResolveExpiryUnit() {
        var result = beanFactoryFailoverExpiryExtractor.resolveExpiryUnit("${expiry-unit}");
        assertThat(result).isEqualTo(DAYS);
    }

    @Test
    void shouldResolveExpiryUnitInLowerCase() {
        var result = beanFactoryFailoverExpiryExtractor.resolveExpiryUnit("${expiry-unit-in-lowercase}");
        assertThat(result).isEqualTo(DAYS);
    }

    @Configuration
    static class TestConfiguration {
        @Bean
        public BeanFactoryFailoverExpiryExtractor failoverExpiryExtractor() {
            return new BeanFactoryFailoverExpiryExtractor();
        }
    }
}