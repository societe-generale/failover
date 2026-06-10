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
import com.societegenerale.failover.core.payload.splitter.*;
import com.societegenerale.failover.core.propagator.ContextPropagator;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
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

    private final PayloadSplitterLookup<T, R> payloadSplitterLookup;

    /** Non-null enables parallel scatter; null means sequential (default). */
    @Nullable
    private final Executor executor;

    private final ContextPropagator contextPropagator;

    /**
     * Full constructor — use when parallel scatter and/or context propagator is required.
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
        this.delegateT = delegateT;
        this.delegateR = delegateR;
        this.payloadSplitterLookup = payloadSplitterLookup;
        this.executor = executor;
        this.contextPropagator = contextPropagator;
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
        this(delegateT, delegateR, payloadSplitterLookup, null, ContextPropagator.noOp());
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
            return scatterStore(failover, args, payload);
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
            return scatterRecover(failover, args, clazz, cause);
        }
        return delegateT.recover(failover, args, clazz, cause);
    }

    /**
     * Triggers expiry cleanup on both {@code delegateT} and {@code delegateR}.
     *
     * <p>When both delegates are the same instance (the common auto-configured case),
     * {@code clean()} is called twice — once per role — so each delegate's cleanup
     * semantics are honoured independently of object identity.
     */
    @Override
    public void clean() {
        delegateT.clean();
        if(delegateR != delegateT) {
            delegateR.clean();
        }
    }

    // ── Scatter / Gather ──────────────────────────────────────────────────────

    private T scatterStore(Failover failover, List<Object> args, T payload) {
        PayloadSplitter<T, R> splitter = lookupSplitter(failover);
        StoreContext<T> compositeCtx = StoreContext.<T>builder().failover(failover).args(args).payload(payload).build();
        List<StoreContext<R>> slices = splitter.splitOnStore(compositeCtx);

        if (executor != null) {
            List<CompletableFuture<Void>> futures = slices.stream()
                    .map(ctx -> CompletableFuture.runAsync(
                            contextPropagator.wrap(() -> storeSlice(ctx)), executor))
                    .toList();
            CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new)).join();
        } else {
            slices.forEach(this::storeSlice);
        }

        log.info("Failover scatter-store: stored {} slices for '{}'", slices.size(), failover.name());
        return payload;
    }

    private @Nullable T scatterRecover(Failover failover, List<Object> args, Class<T> clazz, Throwable cause) {
        PayloadSplitter<T, R> splitter = lookupSplitter(failover);
        RecoverContext<T> compositeCtx = RecoverContext.<T>builder().failover(failover).args(args).clazz(clazz).cause(cause).build();
        List<RecoverContext<R>> recovered;
        if(args==null || args.isEmpty() || failover.recoverAll()) {
            recovered = doRecoverAll(splitter, compositeCtx);
        } else {
            recovered = doRecover(splitter, compositeCtx);
        }
        var finalCtx = splitter.merge(recovered);
        log.info("Failover scatter-recover: gathered {} slices for '{}'", recovered.size(), failover.name());
        return finalCtx.getPayload();
    }

    private @NonNull List<RecoverContext<R>> doRecover(PayloadSplitter<T, R> splitter, RecoverContext<T> compositeCtx) {
        List<RecoverContext<R>> slices = splitter.splitOnRecover(compositeCtx);
        List<RecoverContext<R>> recovered;
        if (executor != null) {
            List<CompletableFuture<RecoverContext<R>>> futures = slices.stream()
                    .map(
                            ctx -> CompletableFuture.supplyAsync(contextPropagator.wrapSupplier(() -> recoverSlice(ctx)), executor)
                    )
                    .toList();
            recovered = futures.stream().map(CompletableFuture::join).toList();
        } else {
            recovered = slices.stream().map(this::recoverSlice).toList();
        }
        return recovered;
    }
    private @NonNull List<RecoverContext<R>> doRecoverAll(PayloadSplitter<T, R> splitter, RecoverContext<T> compositeCtx) {
        List<RecoverContext<R>> slices = splitter.splitOnRecover(compositeCtx);
        List<RecoverContext<R>> recovered;
        if (executor != null) {
            List<CompletableFuture<List<RecoverContext<R>>>> futures = slices.stream()
                    .map(
                            ctx -> CompletableFuture.supplyAsync(contextPropagator.wrapSupplier(() -> recoverSliceForAll(ctx)), executor)
                    )
                    .toList();
            recovered = futures.stream().map(CompletableFuture::join).flatMap(Collection::stream).toList();
        } else {
            recovered = slices.stream().map(this::recoverSliceForAll).flatMap(Collection::stream).toList();
        }
        return recovered;
    }

    private void storeSlice(StoreContext<R> ctx) {
        log.debug("Failover scatter-store: storing slice {} for '{}'", ctx, ctx.getFailover().name());
        delegateR.store(ctx.getFailover(), ctx.getArgs(), ctx.getPayload());
    }

    private RecoverContext<R> recoverSlice(RecoverContext<R> ctx) {
        log.debug("Failover scatter-recover: recovering slice {} for '{}'", ctx, ctx.getFailover().name());
        R payload = delegateR.recover(ctx.getFailover(), ctx.getArgs(), ctx.getClazz(), ctx.getCause());
        log.debug("Failover scatter-recover: recovered slice {} for '{}' with recoveredPayload '{}'", ctx, ctx.getFailover().name(), payload);
        return RecoverContext.<R>builder()
                .failover(ctx.getFailover())
                .args(ctx.getArgs())
                .clazz(ctx.getClazz())
                .cause(ctx.getCause())
                .payload(payload)
                .build();
    }

    private List<RecoverContext<R>> recoverSliceForAll(RecoverContext<R> ctx) {
        log.debug("Failover scatter-recover-all: recovering slice-for-all {} for '{}'", ctx, ctx.getFailover().name());
        List<R> payloads = delegateR.recoverAll(ctx.getFailover(), ctx.getArgs(), ctx.getClazz(), ctx.getCause());
        log.debug("Failover scatter-recover-all: recovered slice-for-all {} for '{}' with recoveredPayloads '{}'", ctx, ctx.getFailover().name(), payloads);
        return payloads.stream().map(payload -> RecoverContext.<R>builder()
                .failover(ctx.getFailover())
                .args(ctx.getArgs())
                .clazz(ctx.getClazz())
                .cause(ctx.getCause())
                .payload(payload)
                .build()).toList();
    }

    private PayloadSplitter<T, R> lookupSplitter(Failover failover) {
        PayloadSplitter<T, R> splitter = payloadSplitterLookup.lookup(failover.payloadSplitter());
        if (splitter == null) {
            throw new PayloadSplitterNotFoundException(
                    "No matching PayloadSplitter bean found for failover '%s' with payload splitter name '%s'. Neither qualifier match nor bean name match!"
                            .formatted(failover.name(), failover.payloadSplitter()));
        }
        return splitter;
    }
}