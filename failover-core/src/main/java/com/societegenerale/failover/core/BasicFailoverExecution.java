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

package com.societegenerale.failover.core;

import com.societegenerale.failover.annotations.Failover;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

/**
 * @author Anand Manissery
 */
@Slf4j
@AllArgsConstructor
public class BasicFailoverExecution<T> implements FailoverExecution<T> {

    private final FailoverHandler<T> failoverHandler;

    @Override
    public T execute(Failover failover, Supplier<T> supplier, Method method, List<Object> args) {
        T result;
        try {
            result = failoverSupplier(failover, supplier, args).get();
        } catch (Exception cause) {
            log.warn("Exception occurred while trying to 'execute' the actual method '{}' with failover. We will try to recover the data from failover...", method.getName(), cause);
            result = executeRecoverOnException(method, args, failover, cause);
        }
        return result;
    }

    private Supplier<T> failoverSupplier(Failover failover, Supplier<T> supplier, List<Object> args) {
        return decorateSupplier(failover,
                () -> {
                    T result = supplier.get();
                    try {
                        failoverHandler.store(failover, args, result);
                    } catch (Exception exception) {
                        log.error("Ignoring Failover Exception !! Exception occurred while trying to 'store' the payload for failover '{}'. This will impact only the failover flow", failover.name(), exception);
                    }
                    return result;
                }, args);
    }

    protected Supplier<T> decorateSupplier(Failover failover, Supplier<T> supplier, List<Object> args) {
        log.trace("Simple pass through supplier decorator for failover {} with args {}", failover.name(), args);
        return supplier;
    }

    private T executeRecoverOnException(Method method, List<Object> args, Failover failover, Exception cause) {
        T result = null;
        try {
            Class<T> clazz = (Class<T>) method.getReturnType();
            result = failoverHandler.recover(failover, args, clazz, cause);
        } catch (Exception exception) {
            log.error("Ignoring Failover Exception !! Exception occurred while trying to 'recover' the payload for failover '{}'. This will impact only the failover flow", failover.name(), exception);
        }
        return result;
    }
}
