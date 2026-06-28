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

import com.societegenerale.failover.core.observable.InstanceIdResolver;
import com.societegenerale.failover.observable.metrics.ClusterSnapshot;
import com.societegenerale.failover.observable.metrics.FailoverMetricsSnapshotService;
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Concrete {@link ThresholdSnapshotPublisher} that builds a {@link ClusterSnapshot} from the local
 * {@link FailoverMetricsSnapshotService} and delivers it via a {@link SnapshotPushClient}.
 *
 * <p>Inherits time-threshold gating from {@link ThresholdSnapshotPublisher} (at most one push per
 * {@code intervalSeconds}) and event-driven dispatch from {@link AbstractSnapshotPublisher} (async,
 * virtual-thread executor injected by autoconfiguration; no push when the app is idle).
 *
 * <p>Owns backoff state: on first failure a single WARN is emitted and {@link #push()} becomes a
 * no-op until {@code retryIntervalSeconds} elapses. On recovery an INFO is logged. Has no dependency
 * on any specific HTTP client — the transport is injected via {@link SnapshotPushClient}.
 *
 * @author Anand Manissery
 */
@Slf4j
public class ClusterSnapshotPublisher extends ThresholdSnapshotPublisher {

    private final FailoverMetricsSnapshotService metricsService;
    private final InstanceIdResolver instanceIdResolver;
    private final SnapshotPushClient pushClient;
    private final String publishUrl;
    private final long retryIntervalMs;

    private final AtomicBoolean failing = new AtomicBoolean(false);
    private volatile long nextRetryMs = 0;

    public ClusterSnapshotPublisher(FailoverMetricsSnapshotService metricsService,
                                    InstanceIdResolver instanceIdResolver,
                                    SnapshotPushClient pushClient,
                                    String publishUrl,
                                    int intervalSeconds,
                                    int retryIntervalSeconds,
                                    Executor executor) {
        super(executor, intervalSeconds);
        this.metricsService = metricsService;
        this.instanceIdResolver = instanceIdResolver;
        this.pushClient = pushClient;
        this.publishUrl = publishUrl;
        this.retryIntervalMs = retryIntervalSeconds * 1000L;
        log.info("Failover shared-store snapshot publisher active: -> '{}', throttle {}s, retry {}s.",
                publishUrl, intervalSeconds, retryIntervalSeconds);
    }

    @Override
    public void push() {
        if (failing.get() && System.currentTimeMillis() < nextRetryMs) {
            return;
        }
        try {
            pushClient.send(new ClusterSnapshot(instanceIdResolver.resolve(), metricsService.metricsSummary()));
            if (failing.getAndSet(false)) {
                log.info("Failover shared-store snapshot push to '{}' recovered.", publishUrl);
            }
        } catch (Exception e) {
            if (!failing.getAndSet(true)) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                log.warn("Failover shared-store snapshot push to '{}' failed: {} — backing off, retrying after {}min.",
                        publishUrl,
                        cause.getClass().getSimpleName() + (cause.getMessage() != null ? ": " + cause.getMessage() : ""),
                        retryIntervalMs / 60_000);
                log.debug("Snapshot push failure detail:", e);
            }
            nextRetryMs = System.currentTimeMillis() + retryIntervalMs;
        }
    }
}
