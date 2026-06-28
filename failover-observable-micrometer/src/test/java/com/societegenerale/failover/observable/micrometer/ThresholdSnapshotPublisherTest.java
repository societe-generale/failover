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

package com.societegenerale.failover.observable.micrometer;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

@DisplayName("ThresholdSnapshotPublisher")
class ThresholdSnapshotPublisherTest {

    static final Executor DIRECT = Runnable::run;

    @Test
    @DisplayName("first onPublish always dispatches (lastPushTime=0)")
    void firstEventAlwaysDispatches() {
        AtomicInteger pushed = new AtomicInteger();
        ThresholdSnapshotPublisher publisher = publisherWithPushCount(pushed, 60);

        publisher.onPublish();

        assertThat(pushed.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("second onPublish within interval is suppressed")
    void secondEventWithinIntervalSuppressed() {
        AtomicInteger pushed = new AtomicInteger();
        ThresholdSnapshotPublisher publisher = publisherWithPushCount(pushed, 60); // 60s interval

        publisher.onPublish();
        publisher.onPublish(); // interval not elapsed

        assertThat(pushed.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("onPublish with 0-second interval always dispatches")
    void zeroIntervalAlwaysDispatches() {
        AtomicInteger pushed = new AtomicInteger();
        ThresholdSnapshotPublisher publisher = publisherWithPushCount(pushed, 0);

        publisher.onPublish();
        publisher.onPublish();
        publisher.onPublish();

        assertThat(pushed.get()).isEqualTo(3);
    }

    @Test
    @DisplayName("multiple concurrent callers — CAS ensures only one wins per interval")
    void casEnsuresOnlyOneWins() throws InterruptedException {
        AtomicInteger pushed = new AtomicInteger();
        ThresholdSnapshotPublisher publisher = publisherWithPushCount(pushed, 60);

        int threads = 10;
        Thread[] workers = new Thread[threads];
        for (int i = 0; i < threads; i++) {
            workers[i] = new Thread(publisher::onPublish);
        }
        for (Thread t : workers) t.start();
        for (Thread t : workers) t.join();

        assertThat(pushed.get()).isEqualTo(1);
    }

    private ThresholdSnapshotPublisher publisherWithPushCount(AtomicInteger counter, int intervalSeconds) {
        return new ThresholdSnapshotPublisher(DIRECT, intervalSeconds) {
            @Override public void push() { counter.incrementAndGet(); }
        };
    }
}
