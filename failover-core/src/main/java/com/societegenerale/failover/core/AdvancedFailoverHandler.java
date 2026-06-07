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
import com.societegenerale.failover.core.expiry.FailoverExpiryExtractor;
import com.societegenerale.failover.core.payload.RecoveredPayloadHandler;
import com.societegenerale.failover.core.observable.publisher.ObservablePublisher;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static com.societegenerale.failover.core.observable.Metrics.of;

/**
 * {@link FailoverHandler} decorator that publishes metrics on every store/recover operation
 * and delegates payload post-processing to a {@link RecoveredPayloadHandler}.
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
    public T store(Failover failover, List<Object> args, T payload) {
        T result = null;
        long startNanos = System.nanoTime();
        try {
            result = failoverHandler.store(failover, args, payload);
        } finally {
            observablePublisher.publish(of(failover.name())
                    .collect("action", "store")
                    .collect("expiry-duration",Long.toString(failoverExpiryExtractor.expiryDuration(failover)))
                    .collect("expiry-unit", failoverExpiryExtractor.expiryUnit(failover).name())
                    .collect("is-stored", Boolean.toString(result!=null))
                    .collect("duration-ns", Long.toString(System.nanoTime() - startNanos)));
        }
        return result;
    }

    @Override
    public T recover(Failover failover, List<Object> args, Class<T> clazz, Throwable cause) {
        T result = null;
        String recoveryFailureMsg = null;
        long startNanos = System.nanoTime();
        try {
            result = failoverHandler.recover(failover, args, clazz, cause);
        } catch( Exception exception) {
            recoveryFailureMsg = exception.getMessage();
            log.error("Ignoring Failover Exception !! Exception occurred while trying to 'recover' the payload for failover. This will impact only the failover flow. However a 'null' payload will be handled by RecoveredPayloadHandler and returned.", exception);
        } finally {
            observablePublisher.publish(of(failover.name())
                    .collect("action", "recover")
                    .collect("expiry-duration",Long.toString(failoverExpiryExtractor.expiryDuration(failover)))
                    .collect("expiry-unit", failoverExpiryExtractor.expiryUnit(failover).name())
                    .collect("exception-type", cause.getClass().getCanonicalName())
                    .collect("exception-cause-type", cause.getCause() !=null ? cause.getCause().getClass().getCanonicalName() : "")
                    .collect("exception-message", cause.getMessage() != null ? cause.getMessage() : "")
                    .collect("exception-cause-message", cause.getCause() != null && cause.getCause().getMessage() != null ? cause.getCause().getMessage() : "")
                    .collect("is-recovered", Boolean.toString(result != null))
                    .collect("is-recovery-failed", Boolean.toString(recoveryFailureMsg!=null))
                    .collect("recovery-failure-message", recoveryFailureMsg)
                    .collect("duration-ns", Long.toString(System.nanoTime() - startNanos)));
        }
        return recoveredPayloadHandler.handle(failover, args, clazz, result, cause);
    }

    @Override
    public void clean() {
        failoverHandler.clean();
    }
}
