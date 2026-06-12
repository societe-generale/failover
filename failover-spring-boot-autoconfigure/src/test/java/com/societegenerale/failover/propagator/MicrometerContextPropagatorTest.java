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

package com.societegenerale.failover.propagator;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class MicrometerContextPropagatorTest {

    @Mock private Tracer tracer;
    @Mock private Span capturedSpan;
    @Mock private Tracer.SpanInScope spanInScope;

    @InjectMocks
    private MicrometerContextPropagator propagator;

    @Test
    @SuppressWarnings("resource")
    @DisplayName("wrap captures current span on calling thread and activates it on executor thread")
    void wrapCapturesCurrentSpanAndActivatesOnExecutorThread() {
        given(tracer.currentSpan()).willReturn(capturedSpan);
        given(tracer.withSpan(capturedSpan)).willReturn(spanInScope);

        AtomicReference<Boolean> taskRan = new AtomicReference<>(false);
        Runnable wrapped = propagator.wrap(() -> taskRan.set(true));
        wrapped.run();

        assertThat(taskRan.get()).isTrue();
        verify(tracer).currentSpan();                   // captured on calling thread
        verify(tracer).withSpan(capturedSpan);          // activated on executor thread
        verify(spanInScope).close();                    // scope closed after task
    }

    @Test
    @SuppressWarnings("resource")
    @DisplayName("wrap propagates null span — clears active span on executor thread")
    void wrapPropagatesNullSpanClearingActiveSpanOnExecutorThread() {
        given(tracer.currentSpan()).willReturn(null);
        given(tracer.withSpan(null)).willReturn(spanInScope);

        propagator.wrap(() -> {}).run();

        verify(tracer).withSpan(null);
        verify(spanInScope).close();
    }

    @Test
    @DisplayName("span scope is closed in finally — even when task throws")
    void spanScopeIsClosedEvenWhenTaskThrows() {
        given(tracer.currentSpan()).willReturn(capturedSpan);
        given(tracer.withSpan(capturedSpan)).willReturn(spanInScope);

        Runnable wrapped = propagator.wrap(() -> { throw new RuntimeException("boom"); });

        assertThatThrownBy(wrapped::run).isInstanceOf(RuntimeException.class);
        verify(spanInScope).close();
    }

    @Test
    @SuppressWarnings("resource")
    @DisplayName("current span captured exactly once at wrap() time — not re-read on task execution")
    void currentSpanCapturedExactlyOnceAtWrapTime() {
        given(tracer.currentSpan()).willReturn(capturedSpan);
        given(tracer.withSpan(capturedSpan)).willReturn(spanInScope);

        Runnable wrapped = propagator.wrap(() -> {});
        wrapped.run();

        // currentSpan() called only once — at wrap() time, not again at run() time
        verify(tracer, times(1)).currentSpan();
        verify(tracer).withSpan(capturedSpan);
    }
}