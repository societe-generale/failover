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

package com.societegenerale.failover.core.exception;

import com.societegenerale.failover.annotations.Failover;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Carries all contextual information available at the point where a method exception is handled.
 *
 * <p>An instance is constructed by {@code BasicFailoverExecution} after the primary method call
 * fails and the failover recovery has been attempted. It is passed to the active
 * {@link com.societegenerale.failover.core.exception.policy.MethodExceptionPolicy} to decide the final outcome.
 *
 * @param failover        the {@link Failover} annotation from the intercepted method
 * @param method          the intercepted method that threw the exception
 * @param args            the arguments passed to the method at the call site
 * @param recoveredResult the value recovered from the failover store, or {@code null} if recovery
 *                        produced no result (store miss, expiry, or store failure)
 * @param cause           the original exception thrown by the primary method call
 * @param <T>             the return type of the intercepted method
 * @author Anand Manissery
 */
public record MethodExceptionContext<T>(
        Failover failover,
        Method method,
        List<Object> args,
        @Nullable T recoveredResult,
        Throwable cause
) {}
