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

package com.societegenerale.failover.core.observable.publisher;

import com.societegenerale.failover.core.observable.Metrics;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;

import java.util.Map;

/**
 * {@link AbstractObservablePublisher} that publishes failover metrics by writing each metric
 * entry into the MDC before emitting a single INFO log line, then restoring the prior MDC state.
 *
 * @author Anand Manissery
 */
@Slf4j
public class MdcLoggerObservablePublisher extends AbstractObservablePublisher {

    @Override
    public void doPublish(Metrics metrics) {
        final Map<String, String> copyOfMdc = MDC.getCopyOfContextMap();
        metrics.getInfo().forEach(MDC::put);
        try {
            log.info("Failover metrics : {}", metrics.getName());
        } finally {
            if (copyOfMdc != null) {
                MDC.setContextMap(copyOfMdc);
            } else {
                MDC.clear();
            }
        }
    }
}
