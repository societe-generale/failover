/*
 * Copyright 2022-2023, Société Générale All rights reserved.
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

package com.societegenerale.failover.core.expiry;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author Anand Manissery
 */
@SpringBootTest
class BeanFactoryExpiryPolicyLookupTestIT {

    private static final LocalDateTime NOW = LocalDateTime.now();

    @Autowired
    private ExpiryPolicyLookup<Object> expiryPolicyLookup;

    @Test
    void shouldReturnTheKeyGeneratorBean() {
        ExpiryPolicy<Object> expiryPolicy = expiryPolicyLookup.lookup("custom-expiry-policy");
        assertThat(expiryPolicy).isInstanceOf(CustomExpiryPolicy.class);
    }

    @Test
    void shouldThrowExceptionWhenNoBeanFoundForAGivenName() {
        NoSuchBeanDefinitionException exception = assertThrows(NoSuchBeanDefinitionException.class, () -> expiryPolicyLookup.lookup("invalid-expiry-policy-name"));
        assertThat(exception).isInstanceOf(NoSuchBeanDefinitionException.class);
        assertThat(exception.getMessage()).isEqualTo("No bean named 'invalid-expiry-policy-name' available");
    }

    @EnableAsync
    @EnableScheduling
    @Configuration
    static class ConfigurationClass {

        @Bean(name = "custom-expiry-policy")
        public ExpiryPolicy<Object> customExpiryPolicy() {
            return new CustomExpiryPolicy<>();
        }

        @Bean
        public ExpiryPolicyLookup<Object> expiryPolicyLookup() {
            return new BeanFactoryExpiryPolicyLookup<>();
        }
    }

    static class CustomExpiryPolicy<T> implements ExpiryPolicy<T> {
        @Override
        public LocalDateTime computeExpiry(Failover failover) {
            return NOW;
        }

        @Override
        public boolean isExpired(Failover failover, ReferentialPayload<T> referentialPayload) {
            return false;
        }
    }
}