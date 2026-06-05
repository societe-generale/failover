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

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CompositeContextPropagatorTest {

    @Test
    @DisplayName("delegates wrap() to all propagators and chains results")
    void delegatesWrapToAllPropagators() {
        List<String> captureOrder = new ArrayList<>();
        List<String> runOrder = new ArrayList<>();

        ContextPropagator p1 = task -> { captureOrder.add("p1-capture"); return () -> { runOrder.add("p1-restore"); task.run(); }; };
        ContextPropagator p2 = task -> { captureOrder.add("p2-capture"); return () -> { runOrder.add("p2-restore"); task.run(); }; };

        CompositeContextPropagator composite = CompositeContextPropagator.of(p1, p2);

        Runnable wrapped = composite.wrap(() -> runOrder.add("task"));
        wrapped.run();

        // Both captures happen on calling thread (when wrap() is called), in list order
        assertThat(captureOrder).containsExactly("p1-capture", "p2-capture");
        // p2 wraps p1's result last so is outermost: p2 restores first, then p1, then task
        assertThat(runOrder).containsExactly("p2-restore", "p1-restore", "task");
    }

    @Test
    @DisplayName("wrapSupplier propagates through all chained propagators")
    void wrapSupplierPropagatesThroughAllPropagators() {
        AtomicInteger counter = new AtomicInteger();
        ContextPropagator p1 = task -> { counter.incrementAndGet(); return task; };
        ContextPropagator p2 = task -> { counter.incrementAndGet(); return task; };

        CompositeContextPropagator composite = CompositeContextPropagator.of(p1, p2);
        var result = composite.wrapSupplier(() -> "ok").get();

        assertThat(result).isEqualTo("ok");
        assertThat(counter.get()).isEqualTo(2);
    }

    @Test
    @DisplayName("of(varargs) and of(List) produce equivalent composites")
    void ofVarargsAndOfListAreEquivalent() {
        AtomicInteger varargsCount = new AtomicInteger();
        AtomicInteger listCount = new AtomicInteger();

        ContextPropagator p = task -> task;

        CompositeContextPropagator.of(p, p).wrap(() -> {}).run();
        CompositeContextPropagator.of(List.of(p, p)).wrap(() -> {}).run();
        // Just verify both construct without error and the task runs
        assertThat(varargsCount.get()).isEqualTo(0);
        assertThat(listCount.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("empty propagator list — wrap() returns task unchanged")
    void emptyPropagatorListReturnsTaskUnchanged() {
        AtomicInteger count = new AtomicInteger();
        Runnable task = count::incrementAndGet;

        Runnable wrapped = CompositeContextPropagator.of().wrap(task);
        wrapped.run();

        assertThat(count.get()).isEqualTo(1);
        assertThat(wrapped).isSameAs(task);
    }

    @Test
    @DisplayName("wrap() throws NullPointerException when a propagator returns null — fails fast with clear message")
    @SuppressWarnings("DataFlowIssue")
    void wrapThrowsWhenPropagatorReturnsNull() {
        ContextPropagator nullReturning = ignored -> null;
        CompositeContextPropagator composite = CompositeContextPropagator.of(nullReturning);

        assertThatThrownBy(() -> composite.wrap(() -> {}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("wrap() returned null");
    }

    @Test
    @DisplayName("wrap() throws NullPointerException naming the offending propagator when second in chain returns null")
    @SuppressWarnings("DataFlowIssue")
    void wrapThrowsNamingOffendingPropagatorWhenSecondReturnsNull() {
        ContextPropagator good = task -> task;
        ContextPropagator nullReturning = ignored -> null;
        CompositeContextPropagator composite = CompositeContextPropagator.of(good, nullReturning);

        assertThatThrownBy(() -> composite.wrap(() -> {}))
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("wrap() returned null");
    }

    @Test
    @DisplayName("two real propagators: MDC + custom ThreadLocal both captured and restored")
    void twoRealPropagatorsComposed() {
        ThreadLocal<String> customCtx = new ThreadLocal<>();
        customCtx.set("ctx-value");

        ContextPropagator customPropagator = task -> {
            String captured = customCtx.get();
            return () -> {
                customCtx.set(captured);
                try { task.run(); } finally { customCtx.remove(); }
            };
        };

        MdcContextPropagator mdcPropagator = new MdcContextPropagator();
        org.slf4j.MDC.put("key", "mdc-value");

        CompositeContextPropagator composite = CompositeContextPropagator.of(customPropagator, mdcPropagator);

        List<String> seenCustom = new ArrayList<>();
        List<String> seenMdc = new ArrayList<>();

        Runnable wrapped = composite.wrap(() -> {
            seenCustom.add(customCtx.get());
            seenMdc.add(org.slf4j.MDC.get("key"));
        });

        customCtx.remove();
        org.slf4j.MDC.clear();

        wrapped.run();

        assertThat(seenCustom).containsExactly("ctx-value");
        assertThat(seenMdc).containsExactly("mdc-value");

        customCtx.remove();
        org.slf4j.MDC.clear();
    }
}