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

package com.societegenerale.failover.execution.resilience;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.BasicFailoverExecution;
import com.societegenerale.failover.core.FailoverHandler;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;

import java.util.List;
import java.util.function.Supplier;

/**
 * @author Anand Manissery
 */
public class ResilienceFailoverExecution<T> extends BasicFailoverExecution<T> {

    private final CircuitBreakerRegistry circuitBreakerRegistry;

    public ResilienceFailoverExecution(FailoverHandler<T> failoverHandler, CircuitBreakerRegistry circuitBreakerRegistry) {
        super(failoverHandler);
        this.circuitBreakerRegistry = circuitBreakerRegistry;
    }

    @Override
    protected Supplier<T> decorateSupplier(Failover failover, Supplier<T> supplier, List<Object> args) {
        CircuitBreaker circuitBreaker = this.circuitBreakerRegistry.circuitBreaker(failover.name());
        return CircuitBreaker.decorateSupplier(circuitBreaker, supplier);
    }
}
