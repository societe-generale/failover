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

package com.societegenerale.failover.configuration;

import com.societegenerale.failover.core.FailoverExecution;
import com.societegenerale.failover.core.FailoverHandler;
import com.societegenerale.failover.execution.resilience.ResilienceFailoverExecution;
import com.societegenerale.failover.properties.FailoverType;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnExpression;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * @author Anand Manissery
 */
@ConditionalOnExpression("${failover.enabled:true} eq true and '${failover.type:basic}'.toLowerCase() eq 'resilience'")
@ConditionalOnClass(name = { "io.github.resilience4j.circuitbreaker.CircuitBreaker" } )
@Configuration
@AllArgsConstructor
@Slf4j
public class ResilienceFailoverExecutionAutoConfiguration {

    @Bean
    public FailoverExecution<Object> failoverExecution(FailoverHandler<Object> failoverHandler, CircuitBreakerRegistry circuitBreakerRegistry) {
        log.info("FailoverExecution configured to ResilienceFailoverExecution. NOTE : You should not mix more than 1 framework for failover (like Resilience Retry and Feign Retry etc). Available options are : { {} }", (Object) FailoverType.values());
        return new ResilienceFailoverExecution<>(failoverHandler, circuitBreakerRegistry);
    }
}
