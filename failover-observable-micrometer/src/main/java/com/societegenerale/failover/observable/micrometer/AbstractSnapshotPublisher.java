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

/**
 * Base for event-driven snapshot publishers. Receives an {@link Executor} (injected by
 * autoconfiguration — typically a virtual-thread executor) and exposes {@link #onPublish()} as
 * the entry point called by {@link MicrometerObservablePublisher} on every metric event.
 *
 * <p>Subclasses implement {@link #shouldPublish()} to control when {@link #push()} is dispatched,
 * and implement {@link #push()} with the actual snapshot delivery logic.
 *
 * @author Anand Manissery
 */
public abstract class AbstractSnapshotPublisher implements SnapshotPublisher {

    private final Executor executor;

    protected AbstractSnapshotPublisher(Executor executor) {
        this.executor = executor;
    }

    /**
     * Called on each metric event by {@link MicrometerObservablePublisher}. Dispatches an async
     * {@link #push()} when {@link #shouldPublish()} returns {@code true}. Never blocks the caller.
     */
    public void onPublish() {
        if (shouldPublish()) {
            executor.execute(this::push);
        }
    }

    /**
     * Decides whether a snapshot push should be dispatched on this event.
     * Must be fast and non-blocking — called on the metric event thread.
     *
     * @return {@code true} to dispatch a push, {@code false} to skip
     */
    protected abstract boolean shouldPublish();
}
