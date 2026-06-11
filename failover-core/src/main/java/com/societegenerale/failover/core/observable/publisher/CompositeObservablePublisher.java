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

import com.societegenerale.failover.core.clock.FailoverClock;
import com.societegenerale.failover.core.observable.Metrics;

import java.util.List;

/**
 * {@link ObservablePublisher} that stamps the publish timestamp once and fans metrics out
 * to a list of delegate publishers.
 *
 * <p>The timestamp is collected on the shared {@link Metrics} object exactly once here,
 * before fan-out, so every delegate sees the same value regardless of how many publishers
 * are registered.
 *
 * @author Anand Manissery
 */
public class CompositeObservablePublisher implements ObservablePublisher {

    private final List<ObservablePublisher> delegates;

    private final FailoverClock clock;

    /**
     * Creates a composite publisher that forwards to each delegate in order.
     *
     * @param delegates the list of publishers to fan out to
     * @param clock     clock used to stamp the single publish timestamp on the metrics object
     */
    public CompositeObservablePublisher(List<ObservablePublisher> delegates, FailoverClock clock) {
        this.delegates = delegates;
        this.clock = clock;
    }

    @Override
    public void publish(Metrics metrics) {
        metrics.collect("report-publish-on", clock.now().toString());
        delegates.forEach(delegate -> delegate.publish(metrics));
    }
}