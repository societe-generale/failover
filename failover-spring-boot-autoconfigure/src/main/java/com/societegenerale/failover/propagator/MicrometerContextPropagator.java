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

package com.societegenerale.failover.propagator;

import com.societegenerale.failover.core.propagator.CompositeContextPropagator;
import com.societegenerale.failover.core.propagator.ContextPropagator;
import com.societegenerale.failover.core.propagator.MdcContextPropagator;
import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;

/**
 * {@link ContextPropagator} that propagates the active Micrometer {@link Span} across
 * executor boundaries in scatter/gather operations.
 *
 * <p>The current span is captured via {@link Tracer#currentSpan()} on the calling (request)
 * thread when {@link #wrap} is invoked. On the executor thread, the captured span is restored
 * as the active span via {@link Tracer#withSpan(Span)} before the task runs. The previous span
 * scope is closed — and the previous active span restored — when the task completes.
 *
 * <p>A null captured span (no active span on the calling thread) is propagated as "no span",
 * which clears any pre-existing span that the executor thread might have had.
 *
 * <h2>Why this matters</h2>
 * <p>{@link MdcContextPropagator} propagates trace/span IDs
 * into MDC keys (sufficient for log correlation), but does <b>not</b> restore the actual
 * {@link Span} object. Without this propagator, work performed on executor threads during
 * scatter/gather is not recorded as child spans under the originating trace, so it becomes
 * invisible to distributed tracing back-ends (Zipkin, Jaeger, etc.).
 *
 * <p>Auto-configured when {@code io.micrometer.tracing.Tracer} is on the classpath and a
 * {@code Tracer} bean is present. Auto-composed with
 * {@link com.societegenerale.failover.store.multitenant.TenantContextPropagator} (if multi-tenant) and
 * {@link MdcContextPropagator} into a
 * {@link CompositeContextPropagator}.
 *
 * @author Anand Manissery
 * @see ContextPropagator
 * @see CompositeContextPropagator
 */
@RequiredArgsConstructor
public class MicrometerContextPropagator implements ContextPropagator {

    private final Tracer tracer;

    @Override
    public @NonNull Runnable wrap(@NonNull Runnable task) {
        Span capturedSpan = tracer.currentSpan();
        return () -> {
            try (Tracer.SpanInScope _ = tracer.withSpan(capturedSpan)) {
                task.run();
            }
        };
    }
}
