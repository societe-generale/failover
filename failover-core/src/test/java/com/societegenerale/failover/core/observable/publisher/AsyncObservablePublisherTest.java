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
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class AsyncObservablePublisherTest {

    @Test
    void shouldForwardPublishedMetricsToTheDelegateOffTheCallerThread() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(3);
        ObservablePublisher delegate = m -> {
            received.add(m.getName());
            latch.countDown();
        };

        try (AsyncObservablePublisher publisher = new AsyncObservablePublisher(delegate, 100)) {
            publisher.publish(Metrics.of("a"));
            publisher.publish(Metrics.of("b"));
            publisher.publish(Metrics.of("c"));

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received).containsExactlyInAnyOrder("a", "b", "c");
            assertThat(publisher.dropped()).isZero();
        }
    }

    @Test
    void shouldDropWithoutBlockingTheCallerWhenTheQueueIsFull() {
        CountDownLatch blockDelegate = new CountDownLatch(1);
        AtomicInteger delivered = new AtomicInteger();
        // Delegate blocks on the first metric, so the drain worker is stuck and the bounded queue fills up.
        ObservablePublisher slowDelegate = m -> {
            try {
                blockDelegate.await();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            delivered.incrementAndGet();
        };

        try (AsyncObservablePublisher publisher = new AsyncObservablePublisher(slowDelegate, 1)) {
            // 1 metric is taken by the worker (then blocks), 1 fills the queue; the rest must be dropped.
            for (int i = 0; i < 50; i++) {
                publisher.publish(Metrics.of("m" + i));
            }

            // Caller returned for all 50 publishes without blocking; the surplus was dropped.
            assertThat(publisher.dropped()).isPositive();
            assertThat(publisher.dropped()).isLessThanOrEqualTo(50L);

            blockDelegate.countDown(); // unblock so close() can drain cleanly
        }
    }

    @Test
    void shouldFlushBufferedMetricsOnClose() {
        AtomicInteger delivered = new AtomicInteger();
        CountDownLatch release = new CountDownLatch(1);
        ObservablePublisher delegate = m -> {
            try {
                release.await(); // hold the worker so metrics buffer in the queue before close()
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            delivered.incrementAndGet();
        };

        AsyncObservablePublisher publisher = new AsyncObservablePublisher(delegate, 100);
        for (int i = 0; i < 5; i++) {
            publisher.publish(Metrics.of("m" + i));
        }
        release.countDown();
        publisher.close();

        assertThat(delivered.get()).isEqualTo(5);
    }

    @Test
    void shouldKeepDrainingAfterADelegateThrows() throws Exception {
        List<String> received = new CopyOnWriteArrayList<>();
        CountDownLatch latch = new CountDownLatch(2);
        ObservablePublisher delegate = m -> {
            if ("boom".equals(m.getName())) {
                throw new IllegalStateException("publisher failure");
            }
            received.add(m.getName());
            latch.countDown();
        };

        try (AsyncObservablePublisher publisher = new AsyncObservablePublisher(delegate, 100)) {
            publisher.publish(Metrics.of("ok1"));
            publisher.publish(Metrics.of("boom"));
            publisher.publish(Metrics.of("ok2"));

            assertThat(latch.await(2, TimeUnit.SECONDS)).isTrue();
            assertThat(received).containsExactlyInAnyOrder("ok1", "ok2");
        }
    }

    @Test
    void shouldRejectANonPositiveQueueCapacity() {
        assertThatThrownBy(() -> new AsyncObservablePublisher(m -> { }, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("queueCapacity must be > 0");
    }
}
