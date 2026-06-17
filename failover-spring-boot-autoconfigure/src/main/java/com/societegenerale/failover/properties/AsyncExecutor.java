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

package com.societegenerale.failover.properties;

import com.societegenerale.failover.store.async.RejectionPolicy;
import lombok.Data;

/**
 * Back-pressure settings for the async store executor (audit R-2), bound to
 * {@code failover.store.async-executor.*}.
 *
 * <p>By default the executor is unbounded (virtual-thread {@code SimpleAsyncTaskExecutor}). Set a
 * {@link #concurrencyLimit} to cap concurrently in-flight async writes; when the cap is reached, the
 * {@link #rejectionPolicy} decides what happens to further submissions.
 *
 * @author Anand Manissery
 */
@Data
public class AsyncExecutor {

    /**
     * Max concurrently in-flight async store operations. {@code 0} (or negative) = unbounded (default,
     * current behaviour). A positive value wraps the executor in a bounded guard, still running accepted
     * tasks on virtual threads.
     */
    private int concurrencyLimit = 0;

    /**
     * What to do when an async write is submitted while at the {@link #concurrencyLimit}. Only consulted
     * when the limit is positive. Default {@link RejectionPolicy#DISCARD} — stored data is a regenerable
     * cache, so dropping a write under saturation is preferable to back-pressuring the caller.
     */
    private RejectionPolicy rejectionPolicy = RejectionPolicy.DISCARD;
}
