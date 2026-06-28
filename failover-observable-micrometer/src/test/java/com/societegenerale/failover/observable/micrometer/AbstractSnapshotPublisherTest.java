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

@DisplayName("AbstractSnapshotPublisher")
class AbstractSnapshotPublisherTest {

    static final Executor DIRECT = Runnable::run;

    @Test
    @DisplayName("onPublish dispatches push when shouldPublish returns true")
    void dispatchesWhenShouldPublish() {
        AtomicInteger pushed = new AtomicInteger();
        AbstractSnapshotPublisher publisher = new AbstractSnapshotPublisher(DIRECT) {
            @Override protected boolean shouldPublish() { return true; }
            @Override public void push() { pushed.incrementAndGet(); }
        };

        publisher.onPublish();

        assertThat(pushed.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("onPublish skips push when shouldPublish returns false")
    void skipsWhenShouldNotPublish() {
        AtomicInteger pushed = new AtomicInteger();
        AbstractSnapshotPublisher publisher = new AbstractSnapshotPublisher(DIRECT) {
            @Override protected boolean shouldPublish() { return false; }
            @Override public void push() { pushed.incrementAndGet(); }
        };

        publisher.onPublish();

        assertThat(pushed.get()).isEqualTo(0);
    }

    @Test
    @DisplayName("executor receives task only when shouldPublish is true")
    void executorOnlyCalledWhenPublishing() {
        AtomicInteger executorCalls = new AtomicInteger();
        Executor countingExecutor = task -> { executorCalls.incrementAndGet(); task.run(); };

        AbstractSnapshotPublisher yes = new AbstractSnapshotPublisher(countingExecutor) {
            @Override protected boolean shouldPublish() { return true; }
            @Override public void push() {}
        };
        AbstractSnapshotPublisher no = new AbstractSnapshotPublisher(countingExecutor) {
            @Override protected boolean shouldPublish() { return false; }
            @Override public void push() {}
        };

        yes.onPublish();
        no.onPublish();

        assertThat(executorCalls.get()).isEqualTo(1);
    }
}
