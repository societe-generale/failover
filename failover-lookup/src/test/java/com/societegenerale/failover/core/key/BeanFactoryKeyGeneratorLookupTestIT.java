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

package com.societegenerale.failover.core.key;

import com.societegenerale.failover.annotations.Failover;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.NoSuchBeanDefinitionException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * @author Anand Manissery
 */
@SpringBootTest
class BeanFactoryKeyGeneratorLookupTestIT {

    @Autowired
    private KeyGeneratorLookup keyGeneratorLookup;

    @Test
    void shouldReturnTheKeyGeneratorBean() {
        KeyGenerator keyGenerator = keyGeneratorLookup.lookup("custom-key-generator");
        assertThat(keyGenerator).isInstanceOf(CustomKeyGenerator.class);
    }

    @Test
    void shouldThrowExceptionWhenNoBeanFoundForAGivenName() {
        NoSuchBeanDefinitionException exception = assertThrows(NoSuchBeanDefinitionException.class, () -> keyGeneratorLookup.lookup("invalid-key-generator-name"));
        assertThat(exception).isInstanceOf(NoSuchBeanDefinitionException.class);
        assertThat(exception.getMessage()).isEqualTo("No bean named 'invalid-key-generator-name' available");
    }

    @EnableAsync
    @EnableScheduling
    @Configuration
    static class ConfigurationClass {

        @Bean(name = "custom-key-generator")
        public KeyGenerator customKeyGenerator() {
            return new CustomKeyGenerator();
        }

        @Bean
        public KeyGeneratorLookup keyGeneratorLookup() {
            return new BeanFactoryKeyGeneratorLookup();
        }
    }

    static class CustomKeyGenerator implements KeyGenerator {
        @Override
        public String key(Failover failover, List<Object> args) {
            return "CUSTOM-KEY";
        }
    }
}