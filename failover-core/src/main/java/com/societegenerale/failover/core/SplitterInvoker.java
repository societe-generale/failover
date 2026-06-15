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
import lombok.AllArgsConstructor;

import java.util.List;

/**
 * Resolves the named {@link PayloadSplitter} for a {@link Failover} and invokes its
 * {@code splitOnStore} / {@code splitOnRecover} / {@code merge} operations, wrapping any
 * user-thrown exception in a {@link PayloadSplitterExecutionException} that carries the splitter
 * name and operation for diagnostics (ADR 32).
 *
 * <p>Collaborator of {@link ScatterGatherFailoverHandler}; package-private and shared by the
 * scatter ({@link PayloadScatter}) and gather ({@link PayloadGather}) sides.
 *
 * @param <T> composite payload type
 * @param <R> slice payload type
 * @author Anand Manissery
 */
@AllArgsConstructor
class SplitterInvoker<T, R> {

    private final PayloadSplitterLookup<T, R> payloadSplitterLookup;

    PayloadSplitter<T, R> lookup(Failover failover) {
        PayloadSplitter<T, R> splitter = payloadSplitterLookup.lookup(failover.payloadSplitter());
        if (splitter == null) {
            throw new PayloadSplitterNotFoundException(
                    "No matching PayloadSplitter bean found for failover '%s' with payload splitter name '%s'. Neither qualifier match nor bean name match!"
                            .formatted(failover.name(), failover.payloadSplitter()));
        }
        return splitter;
    }

    List<StoreContext<R>> splitOnStore(PayloadSplitter<T, R> splitter, Failover failover, StoreContext<T> ctx) {
        try {
            return splitter.splitOnStore(ctx);
        } catch (Exception e) {
            throw new PayloadSplitterExecutionException("splitOnStore", failover.payloadSplitter(), failover, e);
        }
    }

    List<RecoverContext<R>> splitOnRecover(PayloadSplitter<T, R> splitter, Failover failover, RecoverContext<T> ctx) {
        try {
            return splitter.splitOnRecover(ctx);
        } catch (Exception e) {
            throw new PayloadSplitterExecutionException("splitOnRecover", failover.payloadSplitter(), failover, e);
        }
    }

    RecoverContext<T> merge(PayloadSplitter<T, R> splitter, Failover failover, List<RecoverContext<R>> contexts) {
        try {
            return splitter.merge(contexts);
        } catch (Exception e) {
            throw new PayloadSplitterExecutionException("merge", failover.payloadSplitter(), failover, e);
        }
    }
}
