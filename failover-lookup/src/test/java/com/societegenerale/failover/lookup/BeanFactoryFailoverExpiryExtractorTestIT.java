/*
 * Copyright 2022-2026, Société Générale All rights reserved.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.societegenerale.failover.lookup;

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