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
import com.societegenerale.failover.core.report.ReportPublisher;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import java.util.List;

import static com.societegenerale.failover.core.report.Metrics.of;

/**
 * @author Anand Manissery
 */
@Slf4j
@AllArgsConstructor
public class AdvancedFailoverHandler<T> implements FailoverHandler<T> {

    private final FailoverHandler<T> failoverHandler;

    private final RecoveredPayloadHandler recoveredPayloadHandler;

    private final ReportPublisher reportPublisher;

    private final FailoverExpiryExtractor failoverExpiryExtractor;

    @Override
    public T store(Failover failover, List<Object> args, T payload) {
        reportPublisher.publish(of(failover.name())
                .collect("action", "store")
                .collect("expiry-duration",Long.toString(failoverExpiryExtractor.expiryDuration(failover)))
                .collect("expiry-unit", failoverExpiryExtractor.expiryUnit(failover).name()));
        return failoverHandler.store(failover, args, payload);
    }

    @Override
    public T recover(Failover failover, List<Object> args, Class<T> clazz, Throwable throwable) {
        T result = null;
        try {
            result = failoverHandler.recover(failover, args, clazz, throwable);
        } catch( Exception exception) {
            log.error("Ignoring Failover Exception !! Exception occurred while trying to 'recover' the payload for failover. This will impact only the failover flow. However a 'null' payload will be handled by RecoveredPayloadHandler and returned.", exception);
        } finally {
            reportPublisher.publish(of(failover.name())
                    .collect("action", "recover")
                    .collect("expiry-duration",Long.toString(failoverExpiryExtractor.expiryDuration(failover)))
                    .collect("expiry-unit", failoverExpiryExtractor.expiryUnit(failover).name())
                    .collect("exception-type", throwable.getClass().getCanonicalName())
                    .collect("exception-cause-type", throwable.getCause() !=null ? throwable.getCause().getClass().getCanonicalName() : "")
                    .collect("exception-message", throwable.getMessage())
                    .collect("exception-cause-message", throwable.getCause() !=null ? throwable.getCause().getMessage() : "")
                    .collect("is-recovered", result==null ? "false" : "true"));
        }
        return recoveredPayloadHandler.handle(failover, args, clazz, result);
    }

    @Override
    public void clean() {
        failoverHandler.clean();
    }
}
