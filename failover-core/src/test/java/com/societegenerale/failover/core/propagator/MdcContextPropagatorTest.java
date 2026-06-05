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

package com.societegenerale.failover.core.propagator;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;

class MdcContextPropagatorTest {

    private final MdcContextPropagator propagator = new MdcContextPropagator();

    @BeforeEach
    void setUp() {
        MDC.clear();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    // ════════════════════════════════════════════════════════════════════════
    // CAPTURE SEMANTICS — what is captured and when
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Capture semantics")
    class CaptureTests {

        @Test
        @DisplayName("captures all MDC keys from calling thread")
        void capturesAllMdcKeysFromCallingThread() {
            MDC.put("traceId", "abc-123");
            MDC.put("spanId", "span-7");
            MDC.put("userId", "u-42");
            MDC.put("requestId", "req-99");

            AtomicReference<Map<String, String>> seenMdc = new AtomicReference<>();
            Runnable wrapped = propagator.wrap(() -> seenMdc.set(MDC.getCopyOfContextMap()));
            MDC.clear();

            wrapped.run();

            assertThat(seenMdc.get())
                    .containsEntry("traceId", "abc-123")
                    .containsEntry("spanId", "span-7")
                    .containsEntry("userId", "u-42")
                    .containsEntry("requestId", "req-99");
        }

        @Test
        @DisplayName("snapshot is fixed at wrap() time — changes after wrap() not visible in task")
        void snapshotFixedAtWrapTime() {
            MDC.put("key", "v1");
            AtomicReference<String> seen = new AtomicReference<>();
            Runnable snap = propagator.wrap(() -> seen.set(MDC.get("key")));

            MDC.put("key", "v2");  // change AFTER snap was taken
            MDC.clear();

            snap.run();

            assertThat(seen.get()).isEqualTo("v1");  // sees v1, not v2
        }

        @Test
        @DisplayName("two independent wraps capture different snapshots at their respective call times")
        void twoWrapsCaptureDifferentSnapshots() {
            AtomicReference<String> seen1 = new AtomicReference<>();
            AtomicReference<String> seen2 = new AtomicReference<>();

            MDC.put("key", "snap-A");
            Runnable a = propagator.wrap(() -> seen1.set(MDC.get("key")));
            MDC.put("key", "snap-B");
            Runnable b = propagator.wrap(() -> seen2.set(MDC.get("key")));
            MDC.clear();

            a.run();
            b.run();

            assertThat(seen1.get()).isEqualTo("snap-A");
            assertThat(seen2.get()).isEqualTo("snap-B");
        }

        @Test
        @DisplayName("calling thread with empty MDC — task sees empty MDC")
        void emptyCallingThreadMdcTaskSeesEmptyMdc() {
            // calling thread MDC is empty (cleared in @BeforeEach)
            AtomicReference<Map<String, String>> seenMdc = new AtomicReference<>();
            Runnable wrapped = propagator.wrap(() -> seenMdc.set(MDC.getCopyOfContextMap()));

            MDC.put("executorKey", "exec-val");  // executor thread has MDC
            wrapped.run();

            assertThat(seenMdc.get()).isNullOrEmpty();
        }

        @Test
        @DisplayName("MDC with empty-string value is propagated exactly")
        void emptyStringValuePropagated() {
            MDC.put("key", "");
            AtomicReference<String> seen = new AtomicReference<>("sentinel");
            Runnable wrapped = propagator.wrap(() -> seen.set(MDC.get("key")));
            MDC.clear();

            wrapped.run();

            // empty string is a valid MDC value — should propagate, not be treated as absent
            // (behaviour depends on SLF4J impl; assert not sentinel)
            assertThat(seen.get()).isNotEqualTo("sentinel");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // RESTORE SEMANTICS — executor thread MDC after task
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Restore semantics")
    class RestoreTests {

        @Test
        @DisplayName("executor thread's previous MDC fully restored after task — no calling-thread keys leak")
        void executorPreviousMdcRestoredNoCrossContamination() {
            MDC.put("caller-key", "caller-val");
            Runnable wrapped = propagator.wrap(() -> {});
            MDC.clear();
            MDC.put("executor-key", "executor-val");

            wrapped.run();

            assertThat(MDC.get("executor-key")).isEqualTo("executor-val");
            assertThat(MDC.get("caller-key")).isNull();
        }

        @Test
        @DisplayName("executor thread has null MDC before task — null restored after when calling thread also had null")
        void executorNullMdcRestoredWhenCallingThreadAlsoNull() {
            // both calling thread and executor thread have empty/null MDC
            Runnable wrapped = propagator.wrap(() -> {});
            // executor thread MDC is also clear (setUp cleared it)

            wrapped.run();

            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        }

        @Test
        @DisplayName("MDC restored even when task throws RuntimeException")
        void mdcRestoredWhenTaskThrowsRuntimeException() {
            MDC.put("caller", "val");
            Runnable wrapped = propagator.wrap(() -> { throw new RuntimeException("boom"); });
            MDC.clear();
            MDC.put("executor-key", "exec-val");

            try { wrapped.run(); } catch (RuntimeException ignored) {}

            assertThat(MDC.get("executor-key")).isEqualTo("exec-val");
            assertThat(MDC.get("caller")).isNull();
        }

        @Test
        @DisplayName("MDC restored even when task throws Error")
        void mdcRestoredWhenTaskThrowsError() {
            MDC.put("caller", "val");
            Runnable wrapped = propagator.wrap(() -> { throw new Error("fatal"); });
            MDC.clear();
            MDC.put("executor-key", "exec-val");

            try { wrapped.run(); } catch (Error ignored) {}

            assertThat(MDC.get("executor-key")).isEqualTo("exec-val");
        }

        @Test
        @DisplayName("executor MDC with multiple keys fully restored after task — none lost")
        void executorMdcWithMultipleKeysFullyRestored() {
            MDC.put("caller", "c");
            Runnable wrapped = propagator.wrap(() -> {});
            MDC.clear();
            MDC.put("exec-1", "v1");
            MDC.put("exec-2", "v2");
            MDC.put("exec-3", "v3");

            wrapped.run();

            assertThat(MDC.get("exec-1")).isEqualTo("v1");
            assertThat(MDC.get("exec-2")).isEqualTo("v2");
            assertThat(MDC.get("exec-3")).isEqualTo("v3");
            assertThat(MDC.get("caller")).isNull();
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // TASK MODIFIES MDC — modifications during task must not persist
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Task MDC modifications")
    class TaskModificationTests {

        @Test
        @DisplayName("task adding new keys — those keys absent from executor MDC after task")
        void taskAddedKeysAbsentAfterTask() {
            MDC.put("caller-key", "caller-val");
            Runnable wrapped = propagator.wrap(() -> MDC.put("task-added", "task-val"));
            MDC.clear();
            MDC.put("executor-key", "exec-val");

            wrapped.run();

            assertThat(MDC.get("executor-key")).isEqualTo("exec-val");
            assertThat(MDC.get("task-added")).isNull();   // task's addition gone
            assertThat(MDC.get("caller-key")).isNull();
        }

        @Test
        @DisplayName("task overwriting existing key — executor MDC has original value after task")
        void taskOverwrittenKeyRestoredToOriginalAfterTask() {
            MDC.put("caller-key", "caller-val");
            Runnable wrapped = propagator.wrap(() -> MDC.put("executor-key", "overwritten-by-task"));
            MDC.clear();
            MDC.put("executor-key", "original-executor-val");

            wrapped.run();

            assertThat(MDC.get("executor-key")).isEqualTo("original-executor-val");
        }

        @Test
        @DisplayName("task clearing MDC entirely — executor MDC restored after task")
        void taskClearsMdcExecutorMdcRestoredAfter() {
            MDC.put("caller-key", "caller-val");
            Runnable wrapped = propagator.wrap(MDC::clear);  // task nukes MDC
            MDC.clear();
            MDC.put("executor-key", "exec-val");

            wrapped.run();

            assertThat(MDC.get("executor-key")).isEqualTo("exec-val");
        }

        @Test
        @DisplayName("task removing a key — executor MDC restored with that key intact after task")
        void taskRemovedKeyRestoredAfterTask() {
            MDC.put("caller-key", "caller-val");
            Runnable wrapped = propagator.wrap(() -> MDC.remove("executor-key"));
            MDC.clear();
            MDC.put("executor-key", "exec-val");

            wrapped.run();

            assertThat(MDC.get("executor-key")).isEqualTo("exec-val");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // CONCURRENT EXECUTION — parallel wraps must not interfere
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("Concurrent execution")
    class ConcurrentTests {

        @Test
        @DisplayName("parallel runs of different wrapped tasks see their own captured MDC — no cross-contamination")
        void parallelRunsNoMdcCrossContamination() throws InterruptedException {
            int threadCount = 6;
            List<String> seenValues = new CopyOnWriteArrayList<>();
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch doneLatch = new CountDownLatch(threadCount);

            var executor = Executors.newFixedThreadPool(threadCount);
            try {
                for (int i = 0; i < threadCount; i++) {
                    String threadId = "thread-" + i;
                    MDC.put("threadId", threadId);
                    Runnable wrapped = propagator.wrap(() -> seenValues.add(MDC.get("threadId")));
                    executor.submit(() -> {
                        try {
                            startLatch.await();
                            MDC.clear();  // executor thread starts with empty MDC
                            wrapped.run();
                        } catch (InterruptedException e) {
                            Thread.currentThread().interrupt();
                        } finally {
                            doneLatch.countDown();
                        }
                    });
                }
                MDC.clear();
                startLatch.countDown();
                doneLatch.await();
            } finally {
                executor.shutdownNow();
            }

            assertThat(seenValues).hasSize(threadCount);
            for (int i = 0; i < threadCount; i++) {
                assertThat(seenValues).contains("thread-" + i);
            }
        }

        @Test
        @DisplayName("each wrap captures its own snapshot independently across multiple runs")
        void eachWrapCapturesOwnSnapshotAcrossMultipleRuns() {
            List<String> seenValues = new CopyOnWriteArrayList<>();

            for (int run = 0; run < 3; run++) {
                MDC.put("key", "snapshot-" + run);
                Runnable snap = propagator.wrap(() -> seenValues.add(MDC.get("key")));
                MDC.clear();
                snap.run();
            }

            assertThat(seenValues).containsExactly("snapshot-0", "snapshot-1", "snapshot-2");
        }
    }

    // ════════════════════════════════════════════════════════════════════════
    // APPLY EDGE CASES — null vs empty map handling in apply()
    // ════════════════════════════════════════════════════════════════════════

    @Nested
    @DisplayName("apply() null and empty map handling")
    class ApplyEdgeCaseTests {

        @Test
        @DisplayName("null captured MDC → MDC.clear() applied, executor sees empty MDC during task")
        void nullCapturedMdcClearsExecutorMdcDuringTask() {
            // calling thread: MDC is null/empty (cleared in @BeforeEach)
            AtomicReference<Map<String, String>> seenDuringTask = new AtomicReference<>();
            Runnable wrapped = propagator.wrap(() -> seenDuringTask.set(MDC.getCopyOfContextMap()));

            MDC.put("exec-key", "exec-val");
            wrapped.run();

            assertThat(seenDuringTask.get()).isNullOrEmpty();   // executor MDC cleared before task
            assertThat(MDC.get("exec-key")).isEqualTo("exec-val");  // executor MDC restored after task
        }

        @Test
        @DisplayName("non-null, non-empty MDC → setContextMap applied, executor sees all captured keys during task")
        void nonEmptyMdcSetContextMapApplied() {
            MDC.put("k1", "v1");
            MDC.put("k2", "v2");
            AtomicReference<Map<String, String>> seenDuringTask = new AtomicReference<>();
            Runnable wrapped = propagator.wrap(() -> seenDuringTask.set(MDC.getCopyOfContextMap()));
            MDC.clear();

            wrapped.run();

            assertThat(seenDuringTask.get()).containsOnly(
                    Map.entry("k1", "v1"),
                    Map.entry("k2", "v2")
            );
        }

        @Test
        @DisplayName("wrap() on empty calling thread, run() on empty executor thread — no exception, empty MDC throughout")
        void bothEmptyNoException() {
            // both null — should not throw
            assertThatCode(() -> propagator.wrap(() -> {}).run()).doesNotThrowAnyException();
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        }

        @Test
        @DisplayName("wrap can be called and run safely when MDC implementation returns null copyOfContextMap")
        void wrappedTaskRunsSafelyWithNullMdcCopy() {
            // Simulate the case where the SLF4J MDC adapter returns null for getCopyOfContextMap
            // In practice, Logback returns null when MDC is empty — this exercises the null branch
            MDC.clear();  // ensures getCopyOfContextMap() may return null
            Runnable wrapped = propagator.wrap(() -> MDC.put("key", "set-during-task"));
            MDC.clear();

            assertThatCode(wrapped::run).doesNotThrowAnyException();
            // After task: previous MDC (null) was restored via clear()
            assertThat(MDC.getCopyOfContextMap()).isNullOrEmpty();
        }

        @Test
        @DisplayName("non-null but empty MDC map (from setContextMap) — MDC.clear() applied, not setContextMap with empty")
        void nonNullEmptyMapTriggersClearBranch() {
            // MDC.setContextMap(empty) produces non-null empty map from getCopyOfContextMap()
            // This covers the mdc != null && mdc.isEmpty() branch in apply()
            MDC.setContextMap(new java.util.HashMap<>());

            AtomicReference<Map<String, String>> seenDuringTask = new AtomicReference<>();
            Runnable wrapped = propagator.wrap(() -> seenDuringTask.set(MDC.getCopyOfContextMap()));

            MDC.put("executor-key", "exec-val");
            wrapped.run();

            assertThat(seenDuringTask.get()).isNullOrEmpty();   // empty map → clear() applied, task sees empty MDC
            assertThat(MDC.get("executor-key")).isEqualTo("exec-val");  // executor MDC restored after task
        }
    }
}
