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

package com.societegenerale.failover.execution.resilience;

import com.societegenerale.failover.aspect.FailoverAspect;
import com.societegenerale.failover.core.AdvancedFailoverHandler;
import com.societegenerale.failover.core.DefaultFailoverHandler;
import com.societegenerale.failover.core.FailoverExecution;
import com.societegenerale.failover.core.FailoverHandler;
import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.exception.MethodExceptionHandler;
import com.societegenerale.failover.core.exception.policy.MethodExceptionPolicy;
import com.societegenerale.failover.core.exception.policy.NeverRethrowMethodExceptionPolicy;
import com.societegenerale.failover.core.expiry.BasicFailoverExpiryExtractor;
import com.societegenerale.failover.core.expiry.DefaultExpiryPolicy;
import com.societegenerale.failover.core.expiry.ExpiryPolicy;
import com.societegenerale.failover.core.expiry.FailoverExpiryExtractor;
import com.societegenerale.failover.core.key.DefaultKeyGenerator;
import com.societegenerale.failover.core.key.KeyGenerator;
import com.societegenerale.failover.core.payload.DefaultPayloadEnricher;
import com.societegenerale.failover.core.payload.PassThroughRecoveredPayloadHandler;
import com.societegenerale.failover.core.payload.PayloadEnricher;
import com.societegenerale.failover.core.payload.RecoveredPayloadHandler;
import com.societegenerale.failover.core.observable.publisher.MdcLoggerObservablePublisher;
import com.societegenerale.failover.core.observable.publisher.ObservablePublisher;
import com.societegenerale.failover.core.store.DefaultFailoverStore;
import com.societegenerale.failover.core.store.FailoverStore;
import com.societegenerale.failover.store.inmemory.FailoverStoreInmemory;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.EnableAspectJAutoProxy;

/**
 * @author Anand Manissery
 */
@EnableAspectJAutoProxy
@SpringBootApplication
public class MySpringBootTestApplication {

    public static void main(String[] args) {
        SpringApplication.run(MySpringBootTestApplication.class, args);
    }

    @Bean
    public KeyGenerator keyGenerator() {
        return new DefaultKeyGenerator();
    }

    @Bean
    public FailoverStore<Object> failoverStore() {
        return new DefaultFailoverStore<>(new FailoverStoreInmemory<>());
    }

    @Bean
    public ExpiryPolicy<Object> expiryPolicy(FailoverClock clock, FailoverExpiryExtractor failoverExpiryExtractor) {
        return new DefaultExpiryPolicy<>(clock, failoverExpiryExtractor);
    }

    @Bean
    public PayloadEnricher<Object> payloadEnricher() {
        return new DefaultPayloadEnricher<>();
    }

    @Bean
    public RecoveredPayloadHandler recoveredPayloadHandler() {
        return new PassThroughRecoveredPayloadHandler();
    }

    @Bean
    public FailoverExpiryExtractor failoverExpiryExtractor() {
        return new BasicFailoverExpiryExtractor();
    }

    @Bean
    public ObservablePublisher loggerObservablePublisher() {
        return new MdcLoggerObservablePublisher();
    }

    @Bean
    public FailoverHandler<Object> failoverHandler(KeyGenerator keyGenerator, FailoverClock clock, FailoverStore<Object> failoverStore, ExpiryPolicy<Object> expiryPolicy, PayloadEnricher<Object> payloadEnricher, RecoveredPayloadHandler recoveredPayloadHandler, ObservablePublisher observablePublisher, FailoverExpiryExtractor failoverExpiryExtractor) {
        return new AdvancedFailoverHandler<>(new DefaultFailoverHandler<>(keyGenerator, clock, failoverStore, expiryPolicy, payloadEnricher), recoveredPayloadHandler, observablePublisher, failoverExpiryExtractor);
    }

    @Bean
    public MethodExceptionPolicy methodExceptionPolicy() {
        return new NeverRethrowMethodExceptionPolicy();
    }

    @Bean
    public MethodExceptionHandler methodExceptionHandler(MethodExceptionPolicy methodExceptionPolicy) {
        return new MethodExceptionHandler(methodExceptionPolicy);
    }

    @Bean
    public FailoverExecution<Object> failoverExecution(FailoverHandler<Object> failoverHandler, MethodExceptionHandler methodExceptionHandler, CircuitBreakerRegistry circuitBreakerRegistry) {
        return new ResilienceFailoverExecution<>(failoverHandler, methodExceptionHandler, circuitBreakerRegistry);
    }

    @Bean
    public FailoverAspect<Object> failoverAspect(FailoverExecution<Object> failoverExecution) {
        return new FailoverAspect<>(failoverExecution);
    }
}
