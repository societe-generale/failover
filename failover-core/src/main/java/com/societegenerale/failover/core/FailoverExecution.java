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

package com.societegenerale.failover.core;

import com.societegenerale.failover.annotations.Failover;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

/**
 * Strategy for executing a method call with failover semantics.
 *
 * @param <T> the return type of the protected method
 * @author Anand Manissery
 */
public interface FailoverExecution<T> {

    /**
     * Executes the given supplier with failover protection.
     *
     * @param failover annotation metadata for the failover point
     * @param supplier the actual method invocation to execute
     * @param method   the reflected method, used for logging and type resolution
     * @param args     resolved method arguments
     * @return the result from the supplier, or a recovered value on failure
     */
    T execute(Failover failover, Supplier<T> supplier, Method method, List<Object> args);
}
