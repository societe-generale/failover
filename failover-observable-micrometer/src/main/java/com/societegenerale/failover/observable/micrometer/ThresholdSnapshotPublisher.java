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

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Time-threshold extension of {@link AbstractSnapshotPublisher}: implements {@link #shouldPublish()}
 * to allow at most one push per {@code intervalSeconds}. A CAS on {@code lastPushTime} ensures only
 * one caller wins when multiple metric events arrive in the same window.
 *
 * <p>No push occurs when the app is idle (no metric events) — frequency is bounded by activity, not
 * by a polling clock. The {@link Executor} is injected by autoconfiguration (virtual-thread executor).
 *
 * <p>Subclasses provide the actual push implementation via {@link #push()}.
 *
 * @author Anand Manissery
 */
public abstract class ThresholdSnapshotPublisher extends AbstractSnapshotPublisher {

    private final long intervalMs;
    private final AtomicLong lastPushTime = new AtomicLong(0);

    protected ThresholdSnapshotPublisher(Executor executor, int intervalSeconds) {
        super(executor);
        this.intervalMs = intervalSeconds * 1000L;
    }

    @Override
    protected boolean shouldPublish() {
        long now = System.currentTimeMillis();
        long last = lastPushTime.get();
        if (now - last < intervalMs) {
            return false;
        }
        return lastPushTime.compareAndSet(last, now);
    }
}
