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

import com.societegenerale.failover.core.payload.splitter.RecoverContext;
import com.societegenerale.failover.core.payload.splitter.StoreContext;
import com.societegenerale.failover.core.propagator.ContextPropagator;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;
import java.util.function.Function;

import static java.util.concurrent.TimeUnit.MILLISECONDS;

/**
 * Owns the parallel-vs-sequential dispatch of scatter/gather slices and the per-slice timeout
 * policy (ADR 24, ADR 25, ADR 38). Collaborator of {@link ScatterGatherFailoverHandler}; the
 * scatter ({@link PayloadScatter}) and gather ({@link PayloadGather}) sides supply per-slice
 * actions and this class decides how they run.
 *
 * <p>When {@code executor} is {@code null} every slice runs sequentially on the calling thread.
 * When non-null, slices are dispatched via {@link CompletableFuture} on the executor with the
 * {@link ContextPropagator} restoring thread-bound context on each executor thread, and the
 * configured {@code timeout} bounds the join.
 *
 * @param <R> slice payload type
 * @author Anand Manissery
 */
@Slf4j
@AllArgsConstructor
class SliceDispatcher<R> {

    /** Non-null enables parallel dispatch; null means sequential (default). */
    @Nullable
    private final Executor executor;

    private final ContextPropagator contextPropagator;

    /** Per-slice timeout for the parallel path; {@code null} means wait indefinitely. */
    @Nullable
    private final Duration timeout;

    /**
     * Runs {@code action} for every store slice. Parallel path dispatches each slice via
     * {@code runAsync}, joins with {@code allOf} and applies {@link #timeout}; any slice failure
     * propagates to the caller. Sequential path runs the slices in order.
     */
    void dispatchStore(List<StoreContext<R>> slices, Consumer<StoreContext<R>> action) {
        if (executor == null) {
            slices.forEach(action);
            return;
        }
        List<CompletableFuture<Void>> futures = slices.stream()
                .map(ctx -> CompletableFuture.runAsync(contextPropagator.wrap(() -> action.accept(ctx)), executor))
                .toList();
        withTimeout(CompletableFuture.allOf(futures.toArray(CompletableFuture[]::new))).join();
    }

    /**
     * Maps every recover slice through {@code mapper}. Parallel path dispatches each slice via
     * {@code supplyAsync} and joins each future with the per-slice {@link #timeout}; a timed-out
     * slice yields {@code onTimeout.apply(slice)} (the not-recovered fallback) instead of hanging
     * the caller, while any other failure propagates. Sequential path maps the slices in order.
     */
    <X> List<X> dispatchRecover(List<RecoverContext<R>> slices, Function<RecoverContext<R>, X> mapper, Function<RecoverContext<R>, X> onTimeout) {
        if (executor == null) {
            return slices.stream().map(mapper).toList();
        }
        List<CompletableFuture<X>> futures = slices.stream()
                .map(ctx -> CompletableFuture.supplyAsync(contextPropagator.wrapSupplier(() -> mapper.apply(ctx)), executor))
                .toList();
        return zip(slices, futures, onTimeout);
    }

    /** Applies the configured {@link #timeout} to a future; no-op when {@code timeout} is null. */
    private <X> CompletableFuture<X> withTimeout(CompletableFuture<X> future) {
        return timeout == null ? future : future.orTimeout(timeout.toMillis(), MILLISECONDS);
    }

    /**
     * Joins each slice future in order, applying the configured per-slice {@link #timeout}.
     * A timed-out slice yields {@code onTimeout.apply(slice)}; any other completion failure propagates.
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
}
