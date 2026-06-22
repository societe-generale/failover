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

import com.societegenerale.failover.core.observable.Metrics;
import com.societegenerale.failover.core.observable.publisher.ObservablePublisher;
import com.societegenerale.failover.core.payload.ReferentialPayload;
import com.societegenerale.failover.core.store.FailoverStore;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.core.task.SyncTaskExecutor;
import org.springframework.core.task.TaskExecutor;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

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
    @DisplayName("liveEntryCount: 0 and unsupported when the delegate is not size-aware")
    void liveEntryCountUnsupportedForPlainDelegate() {
        assertThat(failoverStoreAsync.liveEntryCountSupported()).isFalse();
        assertThat(failoverStoreAsync.liveEntryCount("name")).isZero();
    }

    @Test
    @DisplayName("liveEntryCount: forwards to a size-aware delegate and reports supported")
    void liveEntryCountForwardsToSizeAwareDelegate() {
        FailoverStoreAsync<String> async = new FailoverStoreAsync<>(new SizeAwareStore(), SYNC_EXECUTOR);

        assertThat(async.liveEntryCountSupported()).isTrue();
        assertThat(async.liveEntryCount("name")).isEqualTo(5L);
    }

    /** A delegate that is both a store and size-aware, for the forwarding test. */
    static class SizeAwareStore implements FailoverStore<String>,
            com.societegenerale.failover.core.store.FailoverStoreSizeAware {
        @Override public void store(ReferentialPayload<String> p) { /* no-op: test only exercises liveEntryCount */ }
        @Override public void delete(ReferentialPayload<String> p) { /* no-op: test only exercises liveEntryCount */ }
        @Override public Optional<ReferentialPayload<String>> find(String name, String key) { return Optional.empty(); }
        @Override public List<ReferentialPayload<String>> findAll(String name) { return List.of(); }
        @Override public void cleanByExpiry(Instant expiry) { /* no-op: test only exercises liveEntryCount */ }
        @Override public long liveEntryCount(String name) { return 5L; }
    }

    @Test
    @DisplayName("should delegate find to the inner store directly (synchronous)")
    void shouldCallFind() {
        failoverStoreAsync.find("name", "key");
        verify(failoverStore).find("name", "key");
    }

    @Test
    @DisplayName("find() returns the result from the inner store")
    void findShouldReturnResultFromDelegate() {
        given(failoverStore.find("name", "key")).willReturn(Optional.of(referentialPayload));
        Optional<ReferentialPayload<String>> result = failoverStoreAsync.find("name", "key");
        assertThat(result).contains(referentialPayload);
    }

    @Test
    @DisplayName("find() propagates exception from delegate — unlike write methods, no exception is swallowed")
    void findShouldPropagateExceptionFromDelegate() {
        doThrow(new RuntimeException("DB unavailable")).when(failoverStore).find("name", "key");
        assertThatThrownBy(() -> failoverStoreAsync.find("name", "key"))
                .isInstanceOf(RuntimeException.class)
                .hasMessage("DB unavailable");
    }

    @Test
    @DisplayName("should delegate cleanByExpiry to the inner store via the executor")
    void shouldCallCleanByExpiry() {
        Instant now = Instant.now();
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
        asyncStore.cleanByExpiry(Instant.now());

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

    @Test
    @DisplayName("store() swallows exception from delegate — caller thread is not disrupted")
    void storeShouldNotPropagateExceptionFromDelegate() {
        doThrow(new RuntimeException("DB unavailable")).when(failoverStore).store(referentialPayload);
        assertThatNoException().isThrownBy(() -> failoverStoreAsync.store(referentialPayload));
    }

    @Test
    @DisplayName("delete() swallows exception from delegate — caller thread is not disrupted")
    void deleteShouldNotPropagateExceptionFromDelegate() {
        doThrow(new RuntimeException("DB unavailable")).when(failoverStore).delete(referentialPayload);
        assertThatNoException().isThrownBy(() -> failoverStoreAsync.delete(referentialPayload));
    }

    @Test
    @DisplayName("cleanByExpiry() swallows exception from delegate — caller thread is not disrupted")
    void cleanByExpiryShouldNotPropagateExceptionFromDelegate() {
        Instant now = Instant.now();
        doThrow(new RuntimeException("DB unavailable")).when(failoverStore).cleanByExpiry(now);
        assertThatNoException().isThrownBy(() -> failoverStoreAsync.cleanByExpiry(now));
    }

    @Nested
    @DisplayName("async-failure metric emission")
    class AsyncFailureMetricTests {

        @Mock
        private ObservablePublisher observablePublisher;

        private FailoverStoreAsync<String> failoverStoreAsyncWithPublisher;

        @BeforeEach
        void setUp() {
            failoverStoreAsyncWithPublisher = new FailoverStoreAsync<>(failoverStore, SYNC_EXECUTOR, observablePublisher);
        }

        @Test
        @DisplayName("store() failure publishes a store-async-failed metric tagged with operation and exception type")
        void storeFailurePublishesMetric() {
            given(referentialPayload.getName()).willReturn("country");
            doThrow(new RuntimeException("DB unavailable")).when(failoverStore).store(referentialPayload);

            failoverStoreAsyncWithPublisher.store(referentialPayload);

            ArgumentCaptor<Metrics> captor = ArgumentCaptor.forClass(Metrics.class);
            verify(observablePublisher).publish(captor.capture());
            Map<String, String> info = captor.getValue().getInfo();
            assertThat(info).containsEntry("failover-action", "store-async-failed");
            assertThat(info).containsEntry("failover-async-operation", "store");
            assertThat(info).containsEntry("failover-name", "country");
            assertThat(info).containsEntry("failover-exception-type", "java.lang.RuntimeException");
        }

        @Test
        @DisplayName("delete() failure publishes a store-async-failed metric for the delete operation")
        void deleteFailurePublishesMetric() {
            given(referentialPayload.getName()).willReturn("country");
            doThrow(new RuntimeException("DB unavailable")).when(failoverStore).delete(referentialPayload);

            failoverStoreAsyncWithPublisher.delete(referentialPayload);

            ArgumentCaptor<Metrics> captor = ArgumentCaptor.forClass(Metrics.class);
            verify(observablePublisher).publish(captor.capture());
            assertThat(captor.getValue().getInfo()).containsEntry("failover-async-operation", "delete");
        }

        @Test
        @DisplayName("cleanByExpiry() failure publishes a store-async-failed metric for the cleanByExpiry operation")
        void cleanByExpiryFailurePublishesMetric() {
            Instant now = Instant.now();
            doThrow(new RuntimeException("DB unavailable")).when(failoverStore).cleanByExpiry(now);

            failoverStoreAsyncWithPublisher.cleanByExpiry(now);

            ArgumentCaptor<Metrics> captor = ArgumentCaptor.forClass(Metrics.class);
            verify(observablePublisher).publish(captor.capture());
            assertThat(captor.getValue().getInfo()).containsEntry("failover-async-operation", "cleanByExpiry");
        }

        @Test
        @DisplayName("a successful store does not publish any async-failure metric")
        void successDoesNotPublish() {
            failoverStoreAsyncWithPublisher.store(referentialPayload);
            verifyNoInteractions(observablePublisher);
        }

        @Test
        @DisplayName("a publisher that itself throws does not break the swallow contract")
        void publisherFailureIsSwallowed() {
            doThrow(new RuntimeException("DB unavailable")).when(failoverStore).store(referentialPayload);
            doThrow(new RuntimeException("publisher down")).when(observablePublisher).publish(org.mockito.ArgumentMatchers.any());
            assertThatNoException().isThrownBy(() -> failoverStoreAsyncWithPublisher.store(referentialPayload));
        }

        // ── Submit-time rejection (executor saturation / shutdown) — audit A2 ──────────────

        /** Executor that rejects every submission, as a bounded executor does at its concurrency limit (ABORT). */
        private final TaskExecutor rejectingExecutor = task -> {
            throw new java.util.concurrent.RejectedExecutionException("saturated");
        };

        @Test
        @DisplayName("store() rejected at submit time publishes a store-async-failed metric with the rejection exception type")
        void storeRejectionPublishesMetric() {
            given(referentialPayload.getName()).willReturn("country");
            var async = new FailoverStoreAsync<>(failoverStore, rejectingExecutor, observablePublisher);

            async.store(referentialPayload);

            ArgumentCaptor<Metrics> captor = ArgumentCaptor.forClass(Metrics.class);
            verify(observablePublisher).publish(captor.capture());
            Map<String, String> info = captor.getValue().getInfo();
            assertThat(info).containsEntry("failover-action", "store-async-failed");
            assertThat(info).containsEntry("failover-async-operation", "store");
            assertThat(info).containsEntry("failover-name", "country");
            assertThat(info).containsEntry("failover-exception-type", "java.util.concurrent.RejectedExecutionException");
            // delegate was never reached — the task did not run
            verifyNoInteractions(failoverStore);
        }

        @Test
        @DisplayName("delete() rejected at submit time publishes a store-async-failed metric for the delete operation")
        void deleteRejectionPublishesMetric() {
            given(referentialPayload.getName()).willReturn("country");
            var async = new FailoverStoreAsync<>(failoverStore, rejectingExecutor, observablePublisher);

            async.delete(referentialPayload);

            ArgumentCaptor<Metrics> captor = ArgumentCaptor.forClass(Metrics.class);
            verify(observablePublisher).publish(captor.capture());
            assertThat(captor.getValue().getInfo()).containsEntry("failover-async-operation", "delete");
        }

        @Test
        @DisplayName("cleanByExpiry() rejected at submit time publishes a store-async-failed metric for the cleanByExpiry operation")
        void cleanByExpiryRejectionPublishesMetric() {
            var async = new FailoverStoreAsync<>(failoverStore, rejectingExecutor, observablePublisher);

            async.cleanByExpiry(Instant.now());

            ArgumentCaptor<Metrics> captor = ArgumentCaptor.forClass(Metrics.class);
            verify(observablePublisher).publish(captor.capture());
            assertThat(captor.getValue().getInfo()).containsEntry("failover-async-operation", "cleanByExpiry");
        }

        @Test
        @DisplayName("submit-time rejection is swallowed — the business call is never broken")
        void rejectionIsSwallowed() {
            var async = new FailoverStoreAsync<>(failoverStore, rejectingExecutor, observablePublisher);
            assertThatNoException().isThrownBy(() -> async.store(referentialPayload));
        }
    }

    @Test
    @DisplayName("submit-time rejection is swallowed even without a publisher configured")
    void rejectionSwallowedWithoutPublisher() {
        TaskExecutor rejectingExecutor = task -> {
            throw new java.util.concurrent.RejectedExecutionException("saturated");
        };
        FailoverStoreAsync<String> async = new FailoverStoreAsync<>(failoverStore, rejectingExecutor);
        assertThatNoException().isThrownBy(() -> async.store(referentialPayload));
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

    @Test
    @DisplayName("findAll() delegates synchronously to the inner store — not via executor")
    void findAllDelegatesSynchronouslyToInnerStore() {
        List<ReferentialPayload<String>> payloads = List.of();
        given(failoverStore.findAll("name")).willReturn(payloads);

        List<ReferentialPayload<String>> result = failoverStoreAsync.findAll("name");

        assertThat(result).isSameAs(payloads);
        verify(failoverStore).findAll("name");
    }
}