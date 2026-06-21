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

package com.societegenerale.failover.core.observable.publisher;

import com.societegenerale.failover.core.observable.Metrics;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Non-blocking {@link ObservablePublisher} decorator: it guarantees that publishing failover metrics
 * can never block or slow the caller's {@code @Failover} business call.
 *
 * <p>The {@code @Failover} path only ever does an O(1), allocation-light {@link BlockingQueue#offer}
 * onto a <strong>bounded</strong> queue and returns immediately. A single drain worker (a virtual
 * thread) takes metrics off the queue and forwards them to the wrapped {@code delegate}
 * (typically the {@link CompositeObservablePublisher}) off the hot path. Whatever the delegates do —
 * Micrometer writes, MDC logging, a remote snapshot push — happens on the drain thread, never the
 * caller's.
 *
 * <p><strong>Drop-on-full.</strong> When the queue is full the metric is dropped and {@link #dropped()}
 * is incremented; the caller is never back-pressured. This is deliberate: the whole point of this
 * decorator is that observability is invisible to the business call, so a "run on the caller thread"
 * fallback is intentionally <em>not</em> offered here. Loss is made visible via the drop counter
 * (bound by the Micrometer layer as {@code failover.metrics.dropped.total}) and a throttled {@code WARN}.
 *
 * <p>Metrics handed to {@link #publish} must not be mutated by the caller afterwards — the existing
 * emission sites build a fresh {@link Metrics} per event and publish it once, so this already holds.
 *
 * <p>Disable via {@code failover.observable.async.enabled=false} to publish synchronously on the caller
 * thread — used for deterministic assertions in integration tests, mirroring {@code failover.store.async=false}.
 *
 * @author Anand Manissery
 */
@Slf4j
public class AsyncObservablePublisher implements ObservablePublisher, AutoCloseable {

    /** How long the drain worker blocks waiting for the next metric before re-checking the running flag. */
    private static final long POLL_MILLIS = 200;

    /** Log a dropped-metric WARN on the 1st drop and then every Nth, to avoid log flooding under saturation. */
    private static final long DROP_LOG_INTERVAL = 1000;

    /** Grace period for the drain worker to flush the queue on {@link #close()}. */
    private static final long SHUTDOWN_AWAIT_SECONDS = 5;

    private final ObservablePublisher delegate;
    private final BlockingQueue<Metrics> queue;
    private final ExecutorService worker;
    private final AtomicLong dropped = new AtomicLong();
    private volatile boolean running = true;

    /**
     * @param delegate     the publisher that does the real fan-out (usually the composite); runs on the drain thread
     * @param queueCapacity bounded queue size; must be {@code > 0}. A full queue drops metrics rather than blocking
     */
    public AsyncObservablePublisher(ObservablePublisher delegate, int queueCapacity) {
        if (queueCapacity <= 0) {
            throw new IllegalArgumentException(
                    "queueCapacity must be > 0 to bound the async publisher, but was " + queueCapacity);
        }
        this.delegate = delegate;
        this.queue = new ArrayBlockingQueue<>(queueCapacity);
        this.worker = Executors.newSingleThreadExecutor(
                Thread.ofVirtual().name("failover-metrics-drain").factory());
        this.worker.execute(this::drainLoop);
        log.debug("AsyncObservablePublisher started (queueCapacity={}).", queueCapacity);
    }

    @Override
    public void publish(Metrics metrics) {
        if (!queue.offer(metrics)) {
            long n = dropped.incrementAndGet();
            if (n == 1 || n % DROP_LOG_INTERVAL == 0) {
                log.warn("Failover metrics queue is full — dropped {} metric(s) so far. Publishing is "
                        + "non-blocking by design; raise failover.observable.async.queue-capacity if this recurs.", n);
            }
        }
    }

    /** Total metrics dropped because the queue was full. Bound by the Micrometer layer for visibility. */
    public long dropped() {
        return dropped.get();
    }

    /** Current number of metrics waiting to be drained (for diagnostics / tests). */
    public int queueSize() {
        return queue.size();
    }

    private void drainLoop() {
        while (running) {
            try {
                Metrics metrics = queue.poll(POLL_MILLIS, TimeUnit.MILLISECONDS);
                if (metrics != null) {
                    dispatch(metrics);
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        // Flush whatever is left so a clean shutdown does not silently lose buffered metrics.
        Metrics remaining;
        while ((remaining = queue.poll()) != null) {
            dispatch(remaining);
        }
    }

    /** A failing delegate must never kill the drain loop or leak out of the worker thread. */
    private void dispatch(Metrics metrics) {
        try {
            delegate.publish(metrics);
        } catch (RuntimeException e) {
            log.warn("A failover metrics publisher threw while draining '{}' — skipping. Cause: {}",
                    metrics.getName(), e.toString());
        }
    }

    /** Stops the drain worker after a best-effort flush of the queue. Idempotent. */
    @Override
    public void close() {
        running = false;
        worker.shutdown();
        try {
            if (!worker.awaitTermination(SHUTDOWN_AWAIT_SECONDS, TimeUnit.SECONDS)) {
                worker.shutdownNow();
            }
        } catch (InterruptedException e) {
            worker.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }
}
