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
import com.societegenerale.failover.core.payload.splitter.PayloadSplitter;
import com.societegenerale.failover.core.payload.splitter.StoreContext;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;

import java.lang.reflect.Method;
import java.util.List;

/**
 * Scatter (store) side of {@link ScatterGatherFailoverHandler}: splits a composite payload into
 * per-entity slices and stores each slice via {@code delegateR}, dispatched through
 * {@link SliceDispatcher} (sequential or parallel).
 *
 * <p>Package-private collaborator; the facade owns the pass-through decision and only delegates
 * here when {@link Failover#payloadSplitter()} is configured.
 *
 * @param <T> composite payload type
 * @param <R> slice payload type
 * @author Anand Manissery
 */
@Slf4j
@AllArgsConstructor
class PayloadScatter<T, R> {

    private final FailoverHandler<R> delegateR;

    private final SplitterInvoker<T, R> splitterInvoker;

    private final SliceDispatcher<R> sliceDispatcher;

    T store(@NonNull Failover failover, @NonNull Method method, List<Object> args, T payload) {
        PayloadSplitter<T, R> splitter = splitterInvoker.lookup(failover);
        StoreContext<T> compositeCtx = StoreContext.<T>builder().failover(failover).args(args).payload(payload).build();
        List<StoreContext<R>> slices = splitterInvoker.splitOnStore(splitter, failover, compositeCtx);

        sliceDispatcher.dispatchStore(slices, ctx -> storeSlice(method, ctx));

        log.debug("Failover scatter-store: stored {} slices for '{}'", slices.size(), failover.name());
        return payload;
    }

    private void storeSlice(@NonNull Method method, StoreContext<R> ctx) {
        log.debug("Failover scatter-store: storing slice {} for '{}'", ctx, ctx.getFailover().name());
        delegateR.store(ctx.getFailover(), method, ctx.getArgs(), ctx.getPayload());
    }
}
