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
import com.societegenerale.failover.core.exception.MethodExceptionContext;
import com.societegenerale.failover.core.exception.MethodExceptionHandler;
import com.societegenerale.failover.core.observable.Metrics;
import com.societegenerale.failover.core.observable.publisher.ObservablePublisher;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.lang.reflect.Method;
import java.util.List;
import java.util.function.Supplier;

import static com.societegenerale.failover.core.util.CastingUtils.cast;
import static com.societegenerale.failover.core.util.CommonsUtil.methodId;
import static com.societegenerale.failover.core.util.FailoverNameResolver.effectiveName;

/**
 * Basic {@link FailoverExecution} that invokes the supplier, stores the result on success,
 * and attempts recovery via the {@link FailoverHandler} on any exception.
 *
 * <p>Times the protected upstream call and publishes {@code failover.upstream.duration} (action
 * {@code upstream}, tagged by result success/failure) — capturing the latency of the actual call,
 * separate from the store/recover path. The {@code observablePublisher} is the non-blocking dispatching
 * publisher, so this never slows the caller; it is optional ({@code null} = no upstream metric).
 *
 * @param <T> the return type of the protected method
 * @author Anand Manissery
 */
@Slf4j
public class BasicFailoverExecution<T> implements FailoverExecution<T> {

    private static final String UPSTREAM_SUCCESS = "success";
    private static final String UPSTREAM_FAILURE = "failure";

    private final FailoverHandler<T> failoverHandler;

    private final MethodExceptionHandler methodExceptionHandler;

    private final @Nullable ObservablePublisher observablePublisher;

    /**
     * @param failoverHandler        handler for store and recover operations
     * @param methodExceptionHandler policy for handling exceptions after recovery
     * @param observablePublisher    non-blocking publisher for the {@code failover.upstream.duration} metric
     *                               ({@code null} to disable upstream timing)
     */
    public BasicFailoverExecution(FailoverHandler<T> failoverHandler, MethodExceptionHandler methodExceptionHandler,
                                  @Nullable ObservablePublisher observablePublisher) {
        this.failoverHandler = failoverHandler;
        this.methodExceptionHandler = methodExceptionHandler;
        this.observablePublisher = observablePublisher;
    }

    /** Convenience constructor without upstream-duration metrics (used in unit tests). */
    public BasicFailoverExecution(FailoverHandler<T> failoverHandler, MethodExceptionHandler methodExceptionHandler) {
        this(failoverHandler, methodExceptionHandler, null);
    }

    @Override
    public T execute(Failover failover, Supplier<T> supplier, Method method, List<Object> args) {
        T result;
        try {
            result = failoverSupplier(failover, method, supplier, args).get();
        } catch (Exception cause) {
            log.warn("Exception occurred while trying to 'execute' the actual method '{}' with failover. We will try to recover the data from failover...", method.getName(), cause);
            result = executeRecoverOnException(method, args, failover, cause);
        }
        return result;
    }

    private Supplier<T> failoverSupplier(Failover failover, Method method, Supplier<T> supplier, List<Object> args) {
        return decorateSupplier(failover,
                () -> {
                    // Time only the protected upstream call. On a decorated supplier (e.g. an OPEN circuit
                    // breaker) that short-circuits before this lambda, nothing is timed — upstream wasn't called.
                    long startNanos = System.nanoTime();
                    T result;
                    try {
                        result = supplier.get();
                    } catch (RuntimeException upstreamFailure) {
                        publishUpstreamDuration(failover, method, UPSTREAM_FAILURE, System.nanoTime() - startNanos);
                        throw upstreamFailure;
                    }
                    publishUpstreamDuration(failover, method, UPSTREAM_SUCCESS, System.nanoTime() - startNanos);
                    try {
                        failoverHandler.store(failover, method, args, result);
                    } catch (Exception exception) {
                        log.error("Ignoring Failover Exception !! Exception occurred while trying to 'store' the payload for failover '{}'. This will impact only the failover flow", failover.name(), exception);
                    }
                    return result;
                }, args);
    }

    /** Publishes the upstream-call latency (non-blocking). No-op when no publisher was supplied. */
    private void publishUpstreamDuration(Failover failover, Method method, String result, long durationNanos) {
        if (observablePublisher == null) {
            return;
        }
        observablePublisher.publish(Metrics.of(failover.name())
                .collect("action", "upstream")
                .collect("domain", effectiveName(failover))
                .collect("method", methodId(method))
                .collect("upstream-result", result)
                .collect("upstream-duration-ns", durationNanos));
    }

    /**
     * Extension point for subclasses to wrap the supplier (e.g. to add context propagation).
     * This base implementation is a pass-through.
     *
     * @param failover annotation metadata for the failover point
     * @param supplier the supplier to optionally wrap
     * @param args     method arguments
     * @return the (possibly wrapped) supplier
     */
    protected Supplier<T> decorateSupplier(Failover failover, Supplier<T> supplier, List<Object> args) {
        log.trace("Simple pass through supplier decorator for failover {} with args {}", failover.name(), args);
        return supplier;
    }

    private T executeRecoverOnException(Method method, List<Object> args, Failover failover, Exception cause) {
        T recovered = null;
        try {
            Class<T> clazz = cast(method.getReturnType());
            recovered = failoverHandler.recover(failover, method, args, clazz, cause);
        } catch (Exception exception) {
            log.error("Ignoring Failover Exception !! Exception occurred while trying to 'recover' the payload for failover '{}'. This will impact only the failover flow", failover.name(), exception);
        }
        return methodExceptionHandler.handle(new MethodExceptionContext<>(failover, method, args, recovered, cause));
    }
}
