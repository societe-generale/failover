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

package com.societegenerale.failover.aspect;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.FailoverExecution;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;

import static com.societegenerale.failover.core.util.CastingUtils.cast;
import static java.util.Arrays.asList;

/**
 * AspectJ around-advice that intercepts methods annotated with {@link Failover} and delegates
 * execution to the configured {@link FailoverExecution} strategy.
 *
 * @param <T> the return type of the intercepted methods
 * @author Anand Manissery
 */
@Aspect
@Slf4j
@AllArgsConstructor
public class FailoverAspect<T> {

    private final FailoverExecution<T> failoverExecution;

    /**
     * Around advice applied to all methods annotated with {@link Failover}.
     * Stores the result on success and recovers from the store on failure.
     *
     * @param joinPoint the proceeding join point for the intercepted method
     * @param failover  the {@link Failover} annotation on the intercepted method
     * @return the method result or a recovered failover value
     */
    @Around(value = "@annotation(com.societegenerale.failover.annotations.Failover) && @annotation(failover)", argNames = "joinPoint, failover")
    public T failoverAroundAdvice(ProceedingJoinPoint joinPoint, @Nullable Failover failover) {
        Method method = ((MethodSignature)joinPoint.getSignature()).getMethod();
        if (failover != null && failover.name()!=null && !failover.name().isEmpty()) {
            return failoverExecution.execute(failover, ()-> returnResult(joinPoint), method, asList(joinPoint.getArgs()));
        }
         return returnResult(joinPoint);
    }

    private T returnResult(ProceedingJoinPoint joinPoint) {
        try {
            return cast(joinPoint.proceed());
        } catch (Throwable throwable) {
            throw new ExecutionException("Exception occurred while executing method '%s' execution failed due to '%s'"
                    .formatted(((MethodSignature)joinPoint.getSignature()).getMethod().getName(), throwable.getMessage()), throwable);
        }
    }
}
