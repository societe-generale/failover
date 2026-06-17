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

import lombok.extern.slf4j.Slf4j;
import org.springframework.core.task.TaskExecutor;

import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.Semaphore;

/**
 * {@link TaskExecutor} decorator that bounds the number of concurrently in-flight tasks and applies a
 * {@link RejectionPolicy} when that bound is reached — a back-pressure / overload guard (audit R-2).
 *
 * <p>The bound is enforced with a {@link Semaphore}; an accepted task is still run by the wrapped
 * {@code delegate}, so the delegate's threading model is preserved (in particular a virtual-thread
 * {@code SimpleAsyncTaskExecutor} keeps running tasks on virtual threads). This decorator adds only an
 * admission counter, never its own thread pool.
 *
 * <p>Without it, an unbounded virtual-thread executor under a failure storm (every failed upstream call
 * enqueues an async store write) can spawn unbounded tasks, each potentially holding a pooled JDBC
 * connection — exhausting the pool and the heap. Bounding the in-flight count caps that blast radius.
 *
 * @author Anand Manissery
 */
@Slf4j
public class BoundedTaskExecutor implements TaskExecutor {

    private final TaskExecutor delegate;
    private final Semaphore permits;
    private final RejectionPolicy rejectionPolicy;
    private final String name;

    /**
     * @param delegate         the executor that actually runs accepted tasks (its threading model is kept)
     * @param concurrencyLimit max number of concurrently in-flight tasks; must be {@code > 0}
     * @param rejectionPolicy  what to do with a task submitted while at the limit
     * @param name             short name used in log messages (e.g. {@code "failover-async"})
     */
    public BoundedTaskExecutor(TaskExecutor delegate, int concurrencyLimit, RejectionPolicy rejectionPolicy, String name) {
        if (concurrencyLimit <= 0) {
            throw new IllegalArgumentException("concurrencyLimit must be > 0 to bound the executor, but was " + concurrencyLimit);
        }
        this.delegate = delegate;
        this.permits = new Semaphore(concurrencyLimit);
        this.rejectionPolicy = rejectionPolicy;
        this.name = name;
    }

    @Override
    public void execute(Runnable task) {
        if (!permits.tryAcquire()) {
            reject(task);
            return;
        }
        try {
            delegate.execute(() -> {
                try {
                    task.run();
                } finally {
                    permits.release();
                }
            });
        } catch (RuntimeException e) {
            // delegate refused to accept the task — release the permit so it is not leaked
            permits.release();
            throw e;
        }
    }

    private void reject(Runnable task) {
        switch (rejectionPolicy) {
            case DISCARD -> log.warn("Failover async executor '{}' is saturated (concurrency limit reached) — discarding "
                    + "task. Stored data is a regenerable cache; raise the concurrency-limit if this recurs.", name);
            case CALLER_RUNS -> {
                log.warn("Failover async executor '{}' is saturated — running the task on the calling thread "
                        + "(back-pressure).", name);
                task.run();
            }
            case ABORT -> throw new RejectedExecutionException(
                    "Failover async executor '" + name + "' is saturated (concurrency limit reached).");
        }
    }
}
