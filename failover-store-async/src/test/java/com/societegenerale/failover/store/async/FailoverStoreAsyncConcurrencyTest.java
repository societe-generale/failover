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
import com.societegenerale.failover.core.store.FailoverStoreException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Concurrency tests for the {@link FailoverStoreAsync} executor path (audit T-2).
 *
 * <p>Verifies that under many concurrent {@code store} submissions every write is eventually
 * applied to the delegate exactly once, and that an executor-side failure is reported through the
 * {@link ObservablePublisher} as the {@code store-async-failed} metric — the only visibility into a
 * silently-degraded async layer.
 *
 * <p>Draining is deterministic: after all work is submitted, the executor is shut down and joined
 * via {@code awaitTermination}, so no polling/await library is needed.
 *
 * @author Anand Manissery
 */
@DisplayName("FailoverStoreAsync — concurrency")
class FailoverStoreAsyncConcurrencyTest {

    private static final String NAME = "product-service";
    private static final Instant NOW = Instant.now();

    @Test
    @DisplayName("all concurrent store() submissions land in the delegate exactly once")
    void allConcurrentStoresLand() throws InterruptedException, ExecutionException {
        var delegate = new CountingStore();
        var executor = taskExecutor();
        var async = new FailoverStoreAsync<>(delegate, executor);

        int submissions = 500;
        var submitPool = Executors.newFixedThreadPool(16);
        List<Future<?>> futures = new ArrayList<>(submissions);
        for (int i = 0; i < submissions; i++) {
            int idx = i;
            futures.add(submitPool.submit(() -> async.store(payload("key-" + idx, "v-" + idx))));
        }
        for (Future<?> f : futures) {
            f.get(); // every store() call has returned → every task is queued on the async executor
        }
        shutdownAndJoin(submitPool);
        drain(executor);               // all executor tasks have now run

        assertThat(delegate.size()).isEqualTo(submissions);
        for (int i = 0; i < submissions; i++) {
            assertThat(delegate.find(NAME, "key-" + i)).isPresent();
        }
    }

    @Test
    @DisplayName("executor-side failure is reported as the store-async-failed metric")
    void asyncFailureIsReportedAsMetric() {
        FailoverStore<String> failing = new FailoverStore<>() {
            @Override public void store(ReferentialPayload<String> p) { throw new FailoverStoreException("boom"); }
            @Override public void delete(ReferentialPayload<String> p) { /* unused */ }
            @Override public Optional<ReferentialPayload<String>> find(String n, String k) { return Optional.empty(); }
            @Override public List<ReferentialPayload<String>> findAll(String n) { return List.of(); }
            @Override public void cleanByExpiry(Instant e) { /* unused */ }
        };
        var captured = new ConcurrentLinkedQueue<Metrics>();
        ObservablePublisher publisher = captured::add;
        var executor = taskExecutor();
        var async = new FailoverStoreAsync<>(failing, executor, publisher);

        async.store(payload("key-1", "value"));
        drain(executor);

        // Metrics namespaces every collected key with the "failover-" prefix (see Metrics#collect).
        assertThat(captured).anySatisfy(m ->
                assertThat(m.getInfo())
                        .containsEntry("failover-action", FailoverStoreAsync.ASYNC_FAILED_ACTION)
                        .containsEntry("failover-async-operation", "store"));
    }

    // ── helpers ──────────────────────────────────────────────────────────────────

    private static ThreadPoolTaskExecutor taskExecutor() {
        var executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(8);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(2000);
        // shutdown() must let queued tasks finish; the default (false) calls shutdownNow() and
        // discards still-queued writes, which would make the drain non-deterministic under load.
        executor.setWaitForTasksToCompleteOnShutdown(true);
        executor.setAwaitTerminationSeconds(30);
        executor.initialize();
        return executor;
    }

    private static void shutdownAndJoin(ExecutorService pool) throws InterruptedException {
        pool.shutdown();
        assertThat(pool.awaitTermination(30, TimeUnit.SECONDS)).as("submit pool drained").isTrue();
    }

    private static void drain(ThreadPoolTaskExecutor executor) {
        executor.shutdown(); // with waitForTasksToCompleteOnShutdown=true this runs all queued tasks before terminating
        try {
            assertThat(executor.getThreadPoolExecutor().awaitTermination(30, TimeUnit.SECONDS))
                    .as("executor drained").isTrue();
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private ReferentialPayload<String> payload(String key, String value) {
        return new ReferentialPayload<>(NAME, key, true, NOW, NOW.plusSeconds(3600), value);
    }

    /** Thread-safe delegate that records every stored entry exactly once. */
    private static final class CountingStore implements FailoverStore<String> {
        private final Map<String, ReferentialPayload<String>> data = new ConcurrentHashMap<>();
        private final AtomicInteger writes = new AtomicInteger();

        @Override public void store(ReferentialPayload<String> p) { data.put(p.getKey(), p); writes.incrementAndGet(); }
        @Override public void delete(ReferentialPayload<String> p) { data.remove(p.getKey()); }
        @Override public Optional<ReferentialPayload<String>> find(String n, String k) { return Optional.ofNullable(data.get(k)); }
        @Override public List<ReferentialPayload<String>> findAll(String n) { return List.copyOf(data.values()); }
        @Override public void cleanByExpiry(Instant e) { /* unused */ }

        int size() { return writes.get(); }
    }
}
