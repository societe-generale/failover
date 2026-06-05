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

package com.societegenerale.failover.it;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import tools.jackson.databind.ObjectMapper;

/**
 * Minimal Spring Boot application for scatter/gather + JDBC failover integration tests.
 *
 * <p>Scans only {@code com.societegenerale.failover.it} to pick up
 * {@link com.societegenerale.failover.it.service.ThirdPartyServiceImpl},
 * the custom expiry policy, the custom key generator, and the payload splitter.
 *
 * @author Anand Manissery
 */
@SpringBootApplication(scanBasePackages = "com.societegenerale.failover.it")
public class FailoverScatterGatherITApplication {

    public static void main(String[] args) {
        SpringApplication.run(FailoverScatterGatherITApplication.class, args);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnClass(name = {"javax.sql.DataSource"})
    @ConditionalOnProperty(prefix = "failover.store", name = "type", havingValue = "jdbc")
    public tools.jackson.databind.ObjectMapper objectMapper() {
        return new ObjectMapper();
    }
}