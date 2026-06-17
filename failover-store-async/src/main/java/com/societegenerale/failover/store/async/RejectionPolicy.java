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

package com.societegenerale.failover.store.async;

/**
 * What a {@link BoundedTaskExecutor} does with a task submitted while it is at its concurrency limit.
 *
 * @author Anand Manissery
 */
public enum RejectionPolicy {

    /**
     * Drop the task and log a {@code WARN}. Non-blocking — keeps the virtual-thread async benefit.
     * The default for store writes: stored data is a regenerable cache, so losing a write under
     * extreme saturation is preferable to back-pressuring the calling thread.
     */
    DISCARD,

    /**
     * Run the task on the submitting (caller) thread. Never loses the task, but injects latency and
     * back-pressure into the caller's path under saturation. Note: a caller-run task does <em>not</em>
     * execute on a virtual thread.
     */
    CALLER_RUNS,

    /**
     * Throw {@link java.util.concurrent.RejectedExecutionException}. Surfaces overload loudly; the
     * caller must handle it.
     */
    ABORT
}
