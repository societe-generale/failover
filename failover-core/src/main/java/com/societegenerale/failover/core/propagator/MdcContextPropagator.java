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

package com.societegenerale.failover.core.propagator;

import org.jspecify.annotations.NonNull;
import org.slf4j.MDC;

import java.util.Map;

/**
 * {@link ContextPropagator} that propagates the SLF4J Mapped Diagnostic Context (MDC)
 * across executor boundaries in scatter/gather operations.
 *
 * <p>The MDC map is captured on the calling (request) thread when {@link #wrap} is called.
 * On the executor thread, the captured map replaces the thread's MDC before the task runs,
 * and the previous MDC state is fully restored in a {@code finally} block afterward.
 *
 * <p><b>Trace correlation</b>: when Micrometer Tracing or Spring Cloud Sleuth is configured,
 * traceId and spanId are automatically added to the MDC by the tracing bridge. Propagating
 * the MDC therefore also propagates trace/span IDs into log lines emitted on executor threads,
 * without any additional configuration.
 *
 * @author Anand Manissery
 * @see CompositeContextPropagator
 */
public class MdcContextPropagator implements ContextPropagator {

    @Override
    public @NonNull Runnable wrap(@NonNull Runnable task) {
        Map<String, String> capturedMdc = MDC.getCopyOfContextMap();
        return () -> {
            Map<String, String> previousMdc = MDC.getCopyOfContextMap();
            apply(capturedMdc);
            try {
                task.run();
            } finally {
                apply(previousMdc);
            }
        };
    }

    private static void apply(Map<String, String> mdc) {
        if (mdc != null && !mdc.isEmpty()) {
            MDC.setContextMap(mdc);
        } else {
            MDC.clear();
        }
    }
}
