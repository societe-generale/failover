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

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@DisplayName("BoundedTaskExecutor — concurrency bound + rejection policy (audit R-2)")
class BoundedTaskExecutorTest {

    private SimpleAsyncTaskExecutor delegate;

    @BeforeEach
    void setUp() {
        delegate = new SimpleAsyncTaskExecutor("test-async-");
        delegate.setVirtualThreads(true);
    }

    @AfterEach
    void tearDown() {
        delegate.close();
    }

    @Test
    @DisplayName("rejects a non-positive concurrency limit")
    void rejectsNonPositiveLimit() {
        assertThatThrownBy(() -> new BoundedTaskExecutor(delegate, 0, RejectionPolicy.DISCARD, "x"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new BoundedTaskExecutor(delegate, -1, RejectionPolicy.DISCARD, "x"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("runs accepted tasks on the delegate")
    void runsAcceptedTasks() {
        var executor = new BoundedTaskExecutor(new SyncTaskExecutor(), 2, RejectionPolicy.DISCARD, "x");
        var ran = new AtomicBoolean(false);
        executor.execute(() -> ran.set(true));
        assertThat(ran).isTrue();
    }

    @Test
    @DisplayName("DISCARD — task submitted while saturated is dropped, not run")
    void discardsWhenSaturated() throws InterruptedException {
        var executor = new BoundedTaskExecutor(delegate, 1, RejectionPolicy.DISCARD, "x");
        var hold = new Saturator(executor);

        var secondRan = new AtomicBoolean(false);
        executor.execute(() -> secondRan.set(true));

        assertThat(secondRan).as("discarded task must not run").isFalse();
        hold.release();
    }

    @Test
    @DisplayName("CALLER_RUNS — task submitted while saturated runs on the calling thread")
    void callerRunsWhenSaturated() throws InterruptedException {
        var executor = new BoundedTaskExecutor(delegate, 1, RejectionPolicy.CALLER_RUNS, "x");
        var hold = new Saturator(executor);

        var runThread = new AtomicReference<Thread>();
        executor.execute(() -> runThread.set(Thread.currentThread()));

        assertThat(runThread.get()).as("caller-runs task runs inline").isSameAs(Thread.currentThread());
        hold.release();
    }

    @Test
    @DisplayName("ABORT — task submitted while saturated throws RejectedExecutionException")
    void abortsWhenSaturated() throws InterruptedException {
        var executor = new BoundedTaskExecutor(delegate, 1, RejectionPolicy.ABORT, "x");
        var hold = new Saturator(executor);

        assertThatThrownBy(() -> executor.execute(() -> { }))
                .isInstanceOf(RejectedExecutionException.class);
        hold.release();
    }

    @Test
    @DisplayName("delegate refusing a task releases the permit and propagates the exception")
    void releasesPermitWhenDelegateRefuses() {
        TaskExecutor refusing = task -> {
            throw new RejectedExecutionException("delegate full");
        };
        var executor = new BoundedTaskExecutor(refusing, 1, RejectionPolicy.ABORT, "x");

        assertThatThrownBy(() -> executor.execute(() -> { }))
                .isInstanceOf(RejectedExecutionException.class);
        // permit was released, so the next submit reaches the delegate again (not silently blocked/dropped)
        assertThatThrownBy(() -> executor.execute(() -> { }))
                .isInstanceOf(RejectedExecutionException.class);
    }

    @Test
    @DisplayName("permit is released once a task completes — capacity is reusable")
    void releasesPermitAfterCompletion() throws InterruptedException {
        var executor = new BoundedTaskExecutor(delegate, 1, RejectionPolicy.ABORT, "x");

        var firstDone = new CountDownLatch(1);
        executor.execute(firstDone::countDown);
        assertThat(firstDone.await(2, TimeUnit.SECONDS)).isTrue();

        var secondDone = new CountDownLatch(1);
        executor.execute(secondDone::countDown);
        assertThat(secondDone.await(2, TimeUnit.SECONDS)).as("freed permit allows a new task").isTrue();
    }

    /** Occupies the single permit with a task blocked until {@link #release()} is called. */
    private static final class Saturator {
        private final CountDownLatch release = new CountDownLatch(1);

        Saturator(TaskExecutor executor) throws InterruptedException {
            var started = new CountDownLatch(1);
            executor.execute(() -> {
                started.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            assertThat(started.await(2, TimeUnit.SECONDS)).as("blocking task started").isTrue();
        }

        void release() {
            release.countDown();
        }
    }
}
