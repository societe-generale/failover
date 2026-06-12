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

package com.societegenerale.failover.core.payload.splitter;

import java.util.List;

/**
 * Strategy that splits a composite payload into individual slices for scatter/gather operations.
 *
 * <p>Implementations are Spring beans registered with a qualifier or bean name matching
 * {@link com.societegenerale.failover.annotations.Failover#payloadSplitter()}.
 *
 * @param <T> composite type — the type seen by the annotated method
 * @param <R> slice type — the type stored/recovered per individual entry
 * @author Anand Manissery
 * @see com.societegenerale.failover.core.ScatterGatherFailoverHandler
 */
public interface PayloadSplitter<T, R> {

    /**
     * Splits the composite {@link StoreContext} into per-slice store contexts.
     * Called once per failover store operation when a splitter is configured.
     *
     * @param context composite context carrying the full payload and original args
     * @return ordered list of per-slice contexts to store individually
     */
    List<StoreContext<R>> splitOnStore(StoreContext<T> context);

    /**
     * Splits the composite {@link RecoverContext} into per-slice recover contexts.
     * Called once per failover recover operation when a splitter is configured.
     *
     * <p><b>RecoverAll contract (findAll / no-ID-args scenario):</b> When the method has no ID
     * args, or {@link com.societegenerale.failover.annotations.Failover#recoverAll()} is
     * {@code true}, this method is called with the original (possibly empty) args.
     * Each returned context is forwarded to {@code FailoverHandler.recoverAll} on the slice
     * delegate — one store {@code findAll} call per context. Every context must set the correct
     * slice type {@code R} via {@link RecoverContext#clazz}.
     * Returning an empty list suppresses recovery and logs a warning.
     *
     * <p><b>Note:</b> When using the default {@code DefaultFailoverHandler} whose {@code recoverAll}
     * ignores args and returns all entries by name, return exactly one placeholder context to avoid
     * N-times duplication. Return multiple contexts only when the slice delegate's
     * {@code recoverAll} partitions results by the supplied args.
     *
     * @param context composite context carrying the aggregate args and cause
     * @return ordered list of per-slice contexts to recover individually; each context drives one
     *         {@code recoverAll} call on the slice delegate
     */
    List<RecoverContext<R>> splitOnRecover(RecoverContext<T> context);

    /**
     * Merges individually recovered slice contexts back into a single composite result.
     * The returned context's {@code payload} field is the final recovered value.
     *
     * <p><b>Deduplication:</b> When {@link #splitOnRecover} returns N contexts in recoverAll mode
     * and the slice delegate's {@code recoverAll} does not partition by args (e.g. the default
     * {@code DefaultFailoverHandler}), all N calls return the same full set of entries — resulting
     * in N-times duplicates in {@code contexts}. This method is the right place to deduplicate,
     * since the implementation has full domain knowledge of the slice type {@code R}:
     * <pre>{@code
     * List<R> deduped = contexts.stream()
     *     .map(RecoverContext::getPayload)
     *     .filter(Objects::nonNull)
     *     .collect(Collectors.toMap(R::getId, r -> r, (a, b) -> a))
     *     .values().stream().toList();
     * }</pre>
     *
     * @param contexts per-slice contexts after each slice's payload has been recovered;
     *                 may contain duplicates when multiple slices target the same store partition
     * @return composite context whose payload is the merged result
     */
    RecoverContext<T> merge(List<RecoverContext<R>> contexts);
}
