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
     * @param context composite context carrying the aggregate args and cause
     * @return ordered list of per-slice contexts to recover individually
     */
    List<RecoverContext<R>> splitOnRecover(RecoverContext<T> context);

    /**
     * Merges individually recovered slice contexts back into a single composite result.
     * The returned context's {@code payload} field is the final recovered value.
     *
     * @param contexts per-slice contexts after each slice's payload has been recovered
     * @return composite context whose payload is the merged result
     */
    RecoverContext<T> merge(List<RecoverContext<R>> contexts);
}
