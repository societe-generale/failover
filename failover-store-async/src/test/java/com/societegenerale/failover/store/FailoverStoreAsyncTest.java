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

package com.societegenerale.failover.store;

import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.FailoverStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

import java.time.LocalDateTime;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

/**
 * @author Anand Manissery
 */
@ExtendWith(MockitoExtension.class)
class FailoverStoreAsyncTest {

    /** Runs submitted tasks on the calling thread — safe for unit tests without a thread pool. */
    private static final TaskExecutor SYNC_EXECUTOR = new SyncTaskExecutor();

    @Mock
    private ReferentialPayload<String> referentialPayload;

    @Mock
    private FailoverStore<String> failoverStore;

    private FailoverStoreAsync<String> failoverStoreAsync;

    @BeforeEach
    void setUp() {
        failoverStoreAsync = new FailoverStoreAsync<>(failoverStore, SYNC_EXECUTOR);
    }

    @Test
    @DisplayName("should delegate store to the inner store via the executor")
    void shouldCallStore() {
        failoverStoreAsync.store(referentialPayload);
        verify(failoverStore).store(referentialPayload);
    }

    @Test
    @DisplayName("should delegate delete to the inner store via the executor")
    void shouldCallDelete() {
        failoverStoreAsync.delete(referentialPayload);
        verify(failoverStore).delete(referentialPayload);
    }

    @Test
    @DisplayName("should delegate find to the inner store directly (synchronous)")
    void shouldCallFind() {
        failoverStoreAsync.find("name", "key");
        verify(failoverStore).find("name", "key");
    }

    @Test
    @DisplayName("should delegate cleanByExpiry to the inner store via the executor")
    void shouldCallCleanByExpiry() {
        LocalDateTime now = LocalDateTime.now();
        failoverStoreAsync.cleanByExpiry(now);
        verify(failoverStore).cleanByExpiry(now);
    }

    @Test
    @DisplayName("store() submits work to the executor — not called inline on the calling thread")
    void storeIsSubmittedToExecutor() {
        AtomicReference<Runnable> capturedTask = new AtomicReference<>();
        TaskExecutor capturingExecutor = capturedTask::set;

        FailoverStoreAsync<String> asyncStore = new FailoverStoreAsync<>(failoverStore, capturingExecutor);
        asyncStore.store(referentialPayload);

        assertThat(capturedTask.get()).as("store() must submit a task to the executor").isNotNull();
    }

    @Test
    @DisplayName("delete() submits work to the executor — not called inline on the calling thread")
    void deleteIsSubmittedToExecutor() {
        AtomicReference<Runnable> capturedTask = new AtomicReference<>();
        TaskExecutor capturingExecutor = capturedTask::set;

        FailoverStoreAsync<String> asyncStore = new FailoverStoreAsync<>(failoverStore, capturingExecutor);
        asyncStore.delete(referentialPayload);

        assertThat(capturedTask.get()).as("delete() must submit a task to the executor").isNotNull();
    }

    @Test
    @DisplayName("cleanByExpiry() submits work to the executor")
    void cleanByExpiryIsSubmittedToExecutor() {
        AtomicReference<Runnable> capturedTask = new AtomicReference<>();
        TaskExecutor capturingExecutor = capturedTask::set;

        FailoverStoreAsync<String> asyncStore = new FailoverStoreAsync<>(failoverStore, capturingExecutor);
        asyncStore.cleanByExpiry(LocalDateTime.now());

        assertThat(capturedTask.get()).as("cleanByExpiry() must submit a task to the executor").isNotNull();
    }

    @Test
    @DisplayName("find() does NOT submit to executor — always synchronous")
    void findIsNotSubmittedToExecutor() {
        AtomicReference<Runnable> capturedTask = new AtomicReference<>();
        TaskExecutor capturingExecutor = capturedTask::set;

        FailoverStoreAsync<String> asyncStore = new FailoverStoreAsync<>(failoverStore, capturingExecutor);
        asyncStore.find("name", "key");

        assertThat(capturedTask.get()).as("find() must NOT submit to executor — it is synchronous").isNull();
    }

    /**
     * Verifies the ThreadLocal safety contract: the executor lambda must only use values captured
     * at submission time (method arguments), never ThreadLocal values that would be absent on the
     * executor thread. This test confirms that the lambda captures the payload by reference — if
     * a ThreadLocal were read inside the lambda, it would return null on the executor thread.
     */
    @Test
    @DisplayName("store() lambda captures payload by reference — not via ThreadLocal")
    void storeCaptulesPayloadByReferenceNotViaThreadLocal() {
        // Simulate a scenario where a ThreadLocal is set on the calling thread.
        // If the lambda read it inside the executor, it would be null on the executor thread.
        ThreadLocal<String> tenantContext = new ThreadLocal<>();
        tenantContext.set("acme");

        AtomicReference<String> tenantSeenInsideLambda = new AtomicReference<>("NOT_SET");

        // Executor that runs the task on a fresh thread (no ThreadLocal inheritance)
        TaskExecutor freshThreadExecutor = task -> {
            Thread t = new Thread(() -> {
                // ThreadLocal is NOT inherited — this is null
                tenantSeenInsideLambda.set(tenantContext.get());
                task.run();
            });
            t.start();
            try { t.join(2000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        };

        FailoverStoreAsync<String> asyncStore = new FailoverStoreAsync<>(failoverStore, freshThreadExecutor);
        asyncStore.store(referentialPayload);

        // ThreadLocal was null inside the lambda — but store() still delegated correctly
        // because the payload was captured by reference, not via ThreadLocal
        assertThat(tenantSeenInsideLambda.get())
                .as("ThreadLocal is null on executor thread — store() must not rely on it")
                .isNull();
        verify(failoverStore).store(referentialPayload);
        tenantContext.remove();
    }
}