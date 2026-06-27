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

package com.societegenerale.failover.dashboard.metrics.source.sharedstore;

import com.societegenerale.failover.core.observable.InstanceIdResolver;
import com.societegenerale.failover.dashboard.service.DashboardMetricsService;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.web.client.RestClient;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Peer side of {@code cluster.mode=shared-store}: periodically POSTs this instance's local {@link
 * DashboardMetricsService#metricsSummary() snapshot} to the dashboard's ingest endpoint. Active only when a
 * non-blank publish URL is configured; failures are logged and never propagate (observability must not disrupt
 * the app). The scheduler runs on a single daemon thread and is stopped on shutdown.
 *
 * @author Anand Manissery
 */
@Slf4j
public class ClusterSnapshotPublisher implements AutoCloseable {

    private final DashboardMetricsService metricsService;
    private final InstanceIdResolver instanceIdResolver;
    private final String publishUrl;
    private final RestClient client;
    private final ScheduledExecutorService scheduler;

    public ClusterSnapshotPublisher(DashboardMetricsService metricsService, InstanceIdResolver instanceIdResolver,
                                      String publishUrl, int intervalSeconds, RestClient client) {
        this.metricsService = metricsService;
        this.instanceIdResolver = instanceIdResolver;
        this.publishUrl = publishUrl;
        this.client = client;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "failover-snapshot-publisher");
            thread.setDaemon(true);
            return thread;
        });
        this.scheduler.scheduleWithFixedDelay(this::push, intervalSeconds, intervalSeconds, TimeUnit.SECONDS);
        log.info("Failover shared-store snapshot publisher active: -> '{}' every {}s.", publishUrl, intervalSeconds);
    }

    /** Pushes one snapshot; any failure is swallowed with a warning so the app is never affected. */
    void push() {
        try {
            client.post()
                    .uri(publishUrl)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body(new ClusterSnapshot(instanceIdResolver.resolve(), metricsService.metricsSummary()))
                    .retrieve()
                    .toBodilessEntity();
        } catch (Exception e) {
            log.warn("Failover shared-store snapshot push to '{}' failed: {}", publishUrl, e.toString());
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
