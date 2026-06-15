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
import com.societegenerale.failover.core.payload.splitter.*;
import com.societegenerale.failover.core.propagator.ContextPropagator;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

/**
 * {@link FailoverHandler} decorator that adds scatter/gather routing to
 * {@link com.societegenerale.failover.annotations.Failover}-annotated methods.
 *
 * <p>When {@link Failover#payloadSplitter()} is non-empty:
 * <ul>
 *   <li><b>store</b> — splits the composite payload into individual slices and stores each
 *       slice via {@code delegateR}.</li>
 *   <li><b>recover</b> — splits the composite key into per-slice keys, recovers each slice via
 *       {@code delegateR}, then merges the slices back into the composite type via the
 *       {@link PayloadSplitter}.</li>
 * </ul>
 *
 * <p>When {@link Failover#payloadSplitter()} is empty, both operations delegate unchanged to
 * {@code delegateT}.
 *
 * <h2>Collaborators</h2>
 * <p>This class is a thin facade: it owns the pass-through decision and delegates the actual work
 * to focused, package-private collaborators (audit A-2):
 * <ul>
 *   <li>{@link PayloadScatter} — the store/scatter side;</li>
 *   <li>{@link PayloadGather} — the recover/gather side;</li>
 *   <li>{@link SliceDispatcher} — parallel-vs-sequential slice dispatch and per-slice timeout;</li>
 *   <li>{@link SplitterInvoker} — splitter lookup and user-exception-wrapping invocation.</li>
 * </ul>
 *
 * <h2>Parallel scatter</h2>
 * <p>Passing a non-null {@link Executor} enables parallel slice dispatch using
 * {@link CompletableFuture}. Slice futures are collected with {@code CompletableFuture.allOf},
 * so any slice failure propagates as an exception on the calling thread.
 *
 * <h2>Context propagator</h2>
 * <p>When parallel execution is enabled, the {@link ContextPropagator} wraps each slice task
 * so that thread-bound context (tenant ID, security principal, MDC) is captured on the calling
 * thread and restored on the executor thread before the slice runs. The no-op propagator
 * ({@link ContextPropagator#noOp()}) is used by default when no propagator is needed.
 *
 * @param <T> composite payload type (the type seen by the annotated method)
 * @param <R> slice payload type (the type stored/recovered per slice)
 * @author Anand Manissery
 */
@Slf4j
public class ScatterGatherFailoverHandler<T, R> implements FailoverHandler<T> {

    private final FailoverHandler<T> delegateT;

    private final FailoverHandler<R> delegateR;

    private final PayloadScatter<T, R> payloadScatter;

    private final PayloadGather<T, R> payloadGather;

    /**
     * Full constructor — use when parallel scatter, context propagator and/or a slice timeout is required.
     *
     * @param delegateT            handler for composite-type operations
     * @param delegateR            handler for slice-type operations
     * @param payloadSplitterLookup lookup for the named {@link PayloadSplitter}
     * @param executor             executor for parallel slice dispatch; {@code null} = sequential
     * @param contextPropagator    context propagator for executor threads; use {@link ContextPropagator#noOp()} when not needed
     * @param timeout              per-slice timeout for the parallel path; {@code null} = wait indefinitely.
     *                             On timeout a recover slice is treated as not recovered (no data) rather than
     *                             hanging the business thread; a store slice surfaces the timeout to the caller
     *                             (already isolated by {@code BasicFailoverExecution}). Ignored on the sequential path.
     */
    public ScatterGatherFailoverHandler(
            FailoverHandler<T> delegateT,
            FailoverHandler<R> delegateR,
            PayloadSplitterLookup<T, R> payloadSplitterLookup,
            @Nullable Executor executor,
            ContextPropagator contextPropagator,
            @Nullable Duration timeout) {
        this.delegateT = delegateT;
        this.delegateR = delegateR;
        var splitterInvoker = new SplitterInvoker<>(payloadSplitterLookup);
        var sliceDispatcher = new SliceDispatcher<R>(executor, contextPropagator, timeout);
        this.payloadScatter = new PayloadScatter<>(delegateR, splitterInvoker, sliceDispatcher);
        this.payloadGather = new PayloadGather<>(delegateR, splitterInvoker, sliceDispatcher);
    }

    /**
     * Parallel constructor without an explicit timeout (waits indefinitely). Retained for backward compatibility.
     *
     * @param delegateT            handler for composite-type operations
     * @param delegateR            handler for slice-type operations
     * @param payloadSplitterLookup lookup for the named {@link PayloadSplitter}
     * @param executor             executor for parallel slice dispatch; {@code null} = sequential
     * @param contextPropagator    context propagator for executor threads; use {@link ContextPropagator#noOp()} when not needed
     */
    public ScatterGatherFailoverHandler(
            FailoverHandler<T> delegateT,
            FailoverHandler<R> delegateR,
            PayloadSplitterLookup<T, R> payloadSplitterLookup,
            @Nullable Executor executor,
            ContextPropagator contextPropagator) {
        this(delegateT, delegateR, payloadSplitterLookup, executor, contextPropagator, null);
    }

    /**
     * Sequential constructor — no executor, no context propagator. Backward-compatible default.
     *
     * @param delegateT             handler for composite-type operations
     * @param delegateR             handler for slice-type operations
     * @param payloadSplitterLookup lookup for the named {@link PayloadSplitter}
     */
    public ScatterGatherFailoverHandler(
            FailoverHandler<T> delegateT,
            FailoverHandler<R> delegateR,
            PayloadSplitterLookup<T, R> payloadSplitterLookup) {
        this(delegateT, delegateR, payloadSplitterLookup, null, ContextPropagator.noOp(), null);
    }

    /**
     * Stores {@code payload} via scatter when {@link Failover#payloadSplitter()} is configured;
     * otherwise delegates unchanged to {@code delegateT}.
     * Returns {@code null} immediately if {@code payload} is {@code null}.
     *
     * @param failover the {@link Failover} annotation on the intercepted method
     * @param args     the method arguments
     * @param payload  the method return value to store; {@code null} is a no-op
     * @return the original {@code payload} (scatter path) or the delegate's return value (pass-through path)
     * @throws PayloadSplitterNotFoundException if {@link Failover#payloadSplitter()} names a bean that does not exist
     */
    @Override
    public T store(Failover failover, List<Object> args, T payload) {
        if (payload == null) {
            log.debug("Failover scatter-store skipped for '{}': method returned null payload", failover.name());
            return null;
        }
        if (!failover.payloadSplitter().isEmpty()) {
            log.debug("Failover scatter-store: storing '{}' via scatter", failover.name());
            return payloadScatter.store(failover, args, payload);
        }
        return delegateT.store(failover, args, payload);
    }

    /**
     * Recovers the composite payload via gather when {@link Failover#payloadSplitter()} is configured;
     * otherwise delegates unchanged to {@code delegateT}.
     *
     * <p>In scatter mode each slice is recovered individually via {@code delegateR} and the results
     * are merged back into a {@code T} by the {@link PayloadSplitter#merge} method. Returns
     * {@code null} if the splitter's {@code merge} produces a context with a {@code null} payload.
     *
     * @param failover the {@link Failover} annotation on the intercepted method
     * @param args     the method arguments (used to derive per-slice keys via the splitter)
     * @param clazz    the composite return type {@code T}
     * @param cause    the exception thrown by the primary method invocation
     * @return the recovered composite payload, or {@code null} if recovery produced no data
     * @throws PayloadSplitterNotFoundException if {@link Failover#payloadSplitter()} names a bean that does not exist
     */
    @Override
    public @Nullable T recover(Failover failover, List<Object> args, Class<T> clazz, Throwable cause) {
        if (!failover.payloadSplitter().isEmpty()) {
            log.debug("Failover scatter-recover: recovering '{}' due to {}", failover.name(), cause.getMessage());
            return payloadGather.recover(failover, args, clazz, cause);
        }
        return delegateT.recover(failover, args, clazz, cause);
    }

    /**
     * Triggers expiry cleanup on both {@code delegateT} and {@code delegateR}.
     *
     * <p>When both delegates are the same instance (the common auto-configured case),
     * {@code clean()} is called only once; distinct delegates are each cleaned.
     */
    @Override
    public void clean() {
        delegateT.clean();
        if(delegateR != delegateT) {
            delegateR.clean();
        }
    }
}
