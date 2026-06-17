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
import com.societegenerale.failover.core.expiry.FailoverExpiryExtractor;
import com.societegenerale.failover.core.observable.Metrics;
import com.societegenerale.failover.core.observable.publisher.ObservablePublisher;
import com.societegenerale.failover.core.payload.RecoveredPayloadHandler;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

import java.lang.reflect.Method;
import java.util.List;

import static com.societegenerale.failover.core.observable.Metrics.of;
import static com.societegenerale.failover.core.util.CommonsUtil.isNotNullOrEmpty;

/**
 * {@link FailoverHandler} decorator that publishes metrics on every store/recover operation
 * and delegates payload post-processing to a {@link RecoveredPayloadHandler}.
 *
 * <p>This is the outermost handler — its {@code store}/{@code recover} fire <b>once per intercepted
 * method call</b> (the composite), so the metrics it publishes are at method-call granularity (e.g.
 * a single {@code findAll()} is one recover event, not one per slice). Each metric carries the
 * intercepted {@code method} and the failover {@code domain}; the method is forwarded downstream.
 *
 * @param <T> the type of the payload managed by this handler
 * @author Anand Manissery
 */
@Slf4j
@AllArgsConstructor
public class AdvancedFailoverHandler<T> implements FailoverHandler<T> {

    private final FailoverHandler<T> failoverHandler;

    private final RecoveredPayloadHandler recoveredPayloadHandler;

    private final ObservablePublisher observablePublisher;

    private final FailoverExpiryExtractor failoverExpiryExtractor;

    @Override
    public T store(@NonNull Failover failover, @NonNull Method method, List<Object> args, T payload) {
        T result = null;
        long startNanos = System.nanoTime();
        try {
            result = failoverHandler.store(failover, method, args, payload);
        } finally {
            observablePublisher.publish(baseMetrics(failover, method, "store")
                    .collect("expiry-duration", failoverExpiryExtractor.expiryDuration(failover))
                    .collect("expiry-unit", failoverExpiryExtractor.expiryUnit(failover).name())
                    .collect("is-stored", isNotNullOrEmpty(result))
                    .collect("duration-ns", System.nanoTime() - startNanos));
        }
        return result;
    }

    @Override
    public T recover(@NonNull Failover failover, @NonNull Method method, List<Object> args, Class<T> clazz, Throwable cause) {
        T result = null;
        String recoveryFailureMsg = null;
        long startNanos = System.nanoTime();
        try {
            result = failoverHandler.recover(failover, method, args, clazz, cause);
        } catch( Exception exception) {
            recoveryFailureMsg = exception.getMessage();
            log.error("Ignoring Failover Exception !! Exception occurred while trying to 'recover' the payload for failover. This will impact only the failover flow. However a 'null' payload will be handled by RecoveredPayloadHandler and returned.", exception);
        } finally {
            Throwable rootCause = cause.getCause();
            observablePublisher.publish(baseMetrics(failover, method, "recover")
                    .collect("expiry-duration", failoverExpiryExtractor.expiryDuration(failover))
                    .collect("expiry-unit", failoverExpiryExtractor.expiryUnit(failover).name())
                    .collect("exception-type", cause.getClass().getCanonicalName())
                    .collect("exception-cause-type", canonicalTypeOf(rootCause))
                    .collect("exception-message", cause.getMessage())
                    .collect("exception-cause-message", messageOf(rootCause))
                    .collect("is-recovered", isNotNullOrEmpty(result))
                    .collect("is-recovery-failed", recoveryFailureMsg != null)
                    .collect("recovery-failure-message", recoveryFailureMsg)
                    .collect("duration-ns", System.nanoTime() - startNanos));
        }
        return handleRecoveredPayload(failover, args, clazz, result, cause);
    }

    /**
     * Post-processes the recovered payload via the {@link RecoveredPayloadHandler}, guarding against a
     * misbehaving handler. A handler failure must not break the failover flow — it is logged at
     * {@code ERROR} and the raw recovered payload is returned unchanged as the fallback (audit I-06).
     */
    private T handleRecoveredPayload(@NonNull Failover failover, List<Object> args, Class<T> clazz, T result, Throwable cause) {
        try {
            return recoveredPayloadHandler.handle(failover, args, clazz, result, cause);
        } catch (Exception exception) {
            log.error("Ignoring Failover Exception !! Exception occurred in RecoveredPayloadHandler while post-processing the recovered payload for failover '{}'. This will impact only the failover flow; the raw recovered payload is returned unchanged.", failover.name(), exception);
            return result;
        }
    }

    @Override
    public void clean() {
        failoverHandler.clean();
    }

    /** Builds the common metric bag carrying the action, failover {@code domain} and intercepted {@code method}. */
    private Metrics baseMetrics(@NonNull Failover failover, @NonNull Method method, String action) {
        return of(failover.name())
                .collect("action", action)
                .collect("domain", domainOf(failover))
                .collect("method", methodId(method));
    }

    /** Failover domain, falling back to the failover {@code name} when no explicit domain is set. */
    private static String domainOf(Failover failover) {
        String domain = failover.domain();
        return (domain == null || domain.isBlank()) ? failover.name() : domain;
    }

    /** Intercepted method identity as {@code SimpleClassName#methodName}. */
    private static String methodId(@NonNull Method method) {
        return method.getDeclaringClass().getSimpleName() + "#" + method.getName();
    }

    /** Null-safe canonical class name; {@code null} is coerced to {@code ""} by {@link com.societegenerale.failover.core.observable.Metrics#collect}. */
    private static String canonicalTypeOf(Throwable throwable) {
        return throwable == null ? null : throwable.getClass().getCanonicalName();
    }

    /** Null-safe message; {@code null} is coerced to {@code ""} by {@link com.societegenerale.failover.core.observable.Metrics#collect}. */
    private static String messageOf(Throwable throwable) {
        return throwable == null ? null : throwable.getMessage();
    }
}
