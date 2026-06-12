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

/**
 * Base {@link ObservablePublisher} that delegates publishing to {@link #doPublish}.
 *
 * <p>The publish timestamp is stamped once by {@link CompositeObservablePublisher} before
 * fan-out, so individual publishers receive an already-timestamped {@link Metrics} object.
 *
 * @author Anand Manissery
 */
public abstract class AbstractObservablePublisher implements ObservablePublisher {

    @Override
    public void publish(Metrics metrics) {
        doPublish(metrics);
    }

    /**
     * Performs the actual publishing of the timestamped metrics.
     *
     * @param metrics the metrics object to publish, already stamped with publish timestamp
     */
    public abstract void doPublish(Metrics metrics);
}