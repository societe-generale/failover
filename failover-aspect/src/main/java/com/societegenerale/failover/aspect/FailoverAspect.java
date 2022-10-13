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

package com.societegenerale.failover.aspect;

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.FailoverExecution;
import lombok.AllArgsConstructor;
import lombok.SneakyThrows;
import lombok.extern.slf4j.Slf4j;
import org.aspectj.lang.ProceedingJoinPoint;
import org.aspectj.lang.annotation.Around;
import org.aspectj.lang.annotation.Aspect;
import org.aspectj.lang.reflect.MethodSignature;

import java.lang.reflect.Method;

import static java.lang.String.format;
import static java.util.Arrays.asList;

/**
 * @author Anand Manissery
 */
@Aspect
@Slf4j
@AllArgsConstructor
public class FailoverAspect<T> {

    private final FailoverExecution<T> failoverExecution;

    @SneakyThrows
    @Around(value = "@annotation(com.societegenerale.failover.annotations.Failover) && @annotation(failover)", argNames = "joinPoint, failover")
    public T failoverAroundAdvice(ProceedingJoinPoint joinPoint, Failover failover) {
        Method method = ((MethodSignature)joinPoint.getSignature()).getMethod();
        if (failover != null && failover.name()!=null && !failover.name().isEmpty()) {
            return failoverExecution.execute(failover, ()-> {
                try {
                    return (T) joinPoint.proceed();
                } catch (Throwable throwable) {
                    throw new ExecutionException(format("Exception occurred while executing method '%s' execution failed due to '%s'", method.getName(), throwable.getMessage()), throwable);
                }
            }, method, asList(joinPoint.getArgs()));
        }
        return (T) joinPoint.proceed();
    }
}
