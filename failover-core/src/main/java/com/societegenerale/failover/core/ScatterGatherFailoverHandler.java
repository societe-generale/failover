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
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

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

    /** Per-slice timeout for parallel dispatch; {@code null} means no timeout (wait indefinitely). */
    @Nullable
    private final Duration timeout;

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
        this.payloadSplitterLookup = payloadSplitterLookup;
        this.executor = executor;
        this.contextPropagator = contextPropagator;
        this.timeout = timeout;
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
     * {@code clean()} is called only once; distinct delegates are each cleaned.
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
        List<StoreContext<R>> slices = invokeSplitOnStore(splitter, failover, compositeCtx);

        if (executor != null) {
            List<CompletableFuture<Void>> futures = slices.stream()
                    .map(ctx -> CompletableFuture.runAsync(
                            contextPropagator.wrap(() -> storeSlice(ctx)), executor))
                    .toList();
            withTimeout(CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))).join();
        } else {
            slices.forEach(this::storeSlice);
        }

        log.info("Failover scatter-store: stored {} slices for '{}'", slices.size(), failover.name());
        return payload;
    }

    /**
     * Single decision point for recover-all vs per-key recover.
     *
     * <p>Recover-all applies when <em>either</em> there are no ID arguments
     * ({@code args} is {@code null} or empty) <em>or</em> {@link Failover#recoverAll()} forces it.
     * The explicit flag is therefore only needed for a method that <em>does</em> receive arguments
     * but should still recover the whole referential; with no arguments, recover-all already applies.
     *
     * @param failover the failover annotation
     * @param args     the method arguments
     * @return {@code true} to recover the whole referential, {@code false} to recover by key
     */
    private boolean shouldRecoverAll(Failover failover, List<Object> args) {
        return args == null || args.isEmpty() || failover.recoverAll();
    }

    private @Nullable T scatterRecover(Failover failover, List<Object> args, Class<T> clazz, Throwable cause) {
        PayloadSplitter<T, R> splitter = lookupSplitter(failover);
        RecoverContext<T> compositeCtx = RecoverContext.<T>builder().failover(failover).args(args).clazz(clazz).cause(cause).build();
        List<RecoverContext<R>> recovered = shouldRecoverAll(failover, args)
                ? doRecoverAll(splitter, compositeCtx)
                : doRecover(splitter, compositeCtx);
        if (recovered.isEmpty()) {
            log.warn("Failover scatter-recover: '{}' — no slices recovered, returning null", failover.name());
            return null;
        }
        // Contexts may carry null payloads — per-key cache misses (positional nulls), expired/missing
        // entries on recover-all, and timed-out slices. The PayloadSplitter owns the null policy
        // (keep positionally vs. drop/deduplicate); see PayloadSplitter#merge.
        var finalCtx = invokeMerge(splitter, failover, recovered);
        log.info("Failover scatter-recover: gathered {} slices for '{}'", recovered.size(), failover.name());
        return finalCtx.getPayload();
    }

    private @NonNull List<RecoverContext<R>> doRecover(PayloadSplitter<T, R> splitter, RecoverContext<T> compositeCtx) {
        List<RecoverContext<R>> slices = invokeSplitOnRecover(splitter, compositeCtx.getFailover(), compositeCtx);
        List<RecoverContext<R>> recovered;
        if (executor != null) {
            List<CompletableFuture<RecoverContext<R>>> futures = slices.stream()
                    .map(
                            ctx -> CompletableFuture.supplyAsync(contextPropagator.wrapSupplier(() -> recoverSlice(ctx)), executor)
                    )
                    .toList();
            recovered = zip(slices, futures, this::notRecovered);
        } else {
            recovered = slices.stream().map(this::recoverSlice).toList();
        }
        return recovered;
    }
    private @NonNull List<RecoverContext<R>> doRecoverAll(PayloadSplitter<T, R> splitter, RecoverContext<T> compositeCtx) {
        List<RecoverContext<R>> slices = invokeSplitOnRecover(splitter, compositeCtx.getFailover(), compositeCtx);
        if (slices.isEmpty()) {
            log.warn("Failover scatter-recover-all: '{}' splitOnRecover returned empty — no template context to recover from", compositeCtx.getFailover().name());
            return List.of();
        }
        if (executor != null) {
            List<CompletableFuture<List<RecoverContext<R>>>> futures = slices.stream()
                    .map(
                            ctx -> CompletableFuture.supplyAsync(contextPropagator.wrapSupplier(() -> recoverSliceForAll(ctx)), executor)
                    )
                    .toList();
            return zip(slices, futures, ctx -> List.of()).stream().flatMap(Collection::stream).toList();
        } else {
            return slices.stream().map(this::recoverSliceForAll).flatMap(Collection::stream).toList();
        }
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

    // ── Timeout handling for the parallel path ─────────────────────────────────────────────────

    /** Applies the configured {@link #timeout} to a future; no-op when {@code timeout} is null. */
    private <X> CompletableFuture<X> withTimeout(CompletableFuture<X> future) {
        return timeout == null ? future : future.orTimeout(timeout.toMillis(), MILLISECONDS);
    }

    /**
     * Joins each slice future in order, applying the configured per-slice {@link #timeout}.
     * A timed-out slice yields {@code onTimeout.apply(slice)} (the not-recovered fallback) instead
     * of hanging the caller; any other completion failure propagates.
     */
    private <X> List<X> zip(List<RecoverContext<R>> slices, List<CompletableFuture<X>> futures, Function<RecoverContext<R>, X> onTimeout) {
        List<X> result = new ArrayList<>(futures.size());
        for (int i = 0; i < futures.size(); i++) {
            RecoverContext<R> ctx = slices.get(i);
            try {
                result.add(withTimeout(futures.get(i)).join());
            } catch (CompletionException e) {
                if (e.getCause() instanceof TimeoutException) {
                    log.warn("Failover scatter-recover: slice {} timed out after {} for '{}' — treating slice as not recovered", ctx, timeout, ctx.getFailover().name());
                    result.add(onTimeout.apply(ctx));
                } else {
                    throw e;
                }
            }
        }
        return result;
    }

    /** A recover context carrying a null payload — the "slice not recovered" marker used on timeout. */
    private RecoverContext<R> notRecovered(RecoverContext<R> ctx) {
        return RecoverContext.<R>builder()
                .failover(ctx.getFailover())
                .args(ctx.getArgs())
                .clazz(ctx.getClazz())
                .cause(ctx.getCause())
                .payload(null)
                .build();
    }

    // ── Splitter invocation wrappers (catch user exceptions and re-throw with context) ──────────

    private List<StoreContext<R>> invokeSplitOnStore(PayloadSplitter<T, R> splitter, Failover failover, StoreContext<T> ctx) {
        try {
            return splitter.splitOnStore(ctx);
        } catch (Exception e) {
            throw new PayloadSplitterExecutionException("splitOnStore", failover.payloadSplitter(), failover, e);
        }
    }

    private List<RecoverContext<R>> invokeSplitOnRecover(PayloadSplitter<T, R> splitter, Failover failover, RecoverContext<T> ctx) {
        try {
            return splitter.splitOnRecover(ctx);
        } catch (Exception e) {
            throw new PayloadSplitterExecutionException("splitOnRecover", failover.payloadSplitter(), failover, e);
        }
    }

    private RecoverContext<T> invokeMerge(PayloadSplitter<T, R> splitter, Failover failover, List<RecoverContext<R>> contexts) {
        try {
            return splitter.merge(contexts);
        } catch (Exception e) {
            throw new PayloadSplitterExecutionException("merge", failover.payloadSplitter(), failover, e);
        }
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