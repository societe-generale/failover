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

package com.societegenerale.failover.core.propagator;

import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.NonNull;

import java.util.List;
import java.util.Objects;

/**
 * {@link ContextPropagator} that chains multiple propagators, applying each in order.
 *
 * <p>Each propagator in the list captures its context on the calling thread when
 * {@link #wrap} is invoked. On the executor thread, contexts are restored in the same
 * order as the list: the first propagator's context is the outermost wrapper (restored
 * first), the last is innermost (restored last, immediately before the task runs).
 *
 * <h2>Example — tenant + MDC</h2>
 * <pre>{@code
 * ContextPropagator propagator = CompositeContextPropagator.of(
 *     new TenantContextPropagator(),
 *     new MdcContextPropagator()
 * );
 * }</pre>
 *
 * @author Anand Manissery
 * @see MdcContextPropagator
 */
@RequiredArgsConstructor
public class CompositeContextPropagator implements ContextPropagator {

    private final List<ContextPropagator> propagators;

    /**
     * Wraps {@code task} by applying each propagator in list order.
     * All context is captured on the calling thread at this point.
     */
    @Override
    public @NonNull Runnable wrap(@NonNull Runnable task) {
        Runnable wrapped = task;
        for (ContextPropagator propagator : propagators) {
            wrapped = Objects.requireNonNull(propagator.wrap(wrapped),
                    () -> propagator.getClass().getSimpleName() + ".wrap() returned null");
        }
        return wrapped;
    }

    public static CompositeContextPropagator of(ContextPropagator... propagators) {
        return new CompositeContextPropagator(List.of(propagators));
    }

    public static CompositeContextPropagator of(List<ContextPropagator> propagators) {
        return new CompositeContextPropagator(List.copyOf(propagators));
    }
}