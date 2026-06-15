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
import com.societegenerale.failover.core.payload.splitter.RecoverContext;
import lombok.AllArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;

import java.util.Collection;
import java.util.List;

/**
 * Gather (recover) side of {@link ScatterGatherFailoverHandler}: splits the composite key into
 * per-slice keys, recovers each slice via {@code delegateR} (dispatched through
 * {@link SliceDispatcher}), then merges the slices back into the composite type via the
 * {@link PayloadSplitter}.
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
class PayloadGather<T, R> {

    private final FailoverHandler<R> delegateR;

    private final SplitterInvoker<T, R> splitterInvoker;

    private final SliceDispatcher<R> sliceDispatcher;

    @Nullable T recover(Failover failover, List<Object> args, Class<T> clazz, Throwable cause) {
        PayloadSplitter<T, R> splitter = splitterInvoker.lookup(failover);
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
        var finalCtx = splitterInvoker.merge(splitter, failover, recovered);
        log.info("Failover scatter-recover: gathered {} slices for '{}'", recovered.size(), failover.name());
        return finalCtx.getPayload();
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

    private @NonNull List<RecoverContext<R>> doRecover(PayloadSplitter<T, R> splitter, RecoverContext<T> compositeCtx) {
        List<RecoverContext<R>> slices = splitterInvoker.splitOnRecover(splitter, compositeCtx.getFailover(), compositeCtx);
        return sliceDispatcher.dispatchRecover(slices, this::recoverSlice, this::notRecovered);
    }

    private @NonNull List<RecoverContext<R>> doRecoverAll(PayloadSplitter<T, R> splitter, RecoverContext<T> compositeCtx) {
        List<RecoverContext<R>> slices = splitterInvoker.splitOnRecover(splitter, compositeCtx.getFailover(), compositeCtx);
        if (slices.isEmpty()) {
            log.warn("Failover scatter-recover-all: '{}' splitOnRecover returned empty — no template context to recover from", compositeCtx.getFailover().name());
            return List.of();
        }
        return sliceDispatcher.dispatchRecover(slices, this::recoverSliceForAll, ctx -> List.<RecoverContext<R>>of())
                .stream().flatMap(Collection::stream).toList();
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
}
