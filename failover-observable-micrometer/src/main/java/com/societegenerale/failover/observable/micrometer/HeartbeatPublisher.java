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
import lombok.extern.slf4j.Slf4j;

import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Periodic lightweight heartbeat publisher. Unlike the event-driven {@link ClusterSnapshotPublisher},
 * this uses a true polling schedule — heartbeats are inherently periodic and must arrive even when no
 * metric events fire (the whole point is to detect a silent instance).
 *
 * <p>Sends only the instance id (no metric payload) to the dashboard's
 * {@code POST /api/cluster/heartbeat} endpoint at a fixed interval. The dashboard uses heartbeat age
 * to classify instances as {@code LIVE} or {@code DOWN} when liveness tracking is enabled.
 *
 * <p>Activated only when {@code failover.dashboard.cluster.snapshot.heartbeat.enabled=true} (default
 * {@code false}) — zero overhead when not needed.
 *
 * @author Anand Manissery
 */
@Slf4j
public class HeartbeatPublisher implements AutoCloseable {

    private final InstanceIdResolver instanceIdResolver;
    private final HeartbeatPushClient pushClient;
    private final String heartbeatUrl;
    private final ScheduledExecutorService scheduler;
    private final AtomicBoolean failing = new AtomicBoolean(false);

    public HeartbeatPublisher(InstanceIdResolver instanceIdResolver,
                              HeartbeatPushClient pushClient,
                              String heartbeatUrl,
                              int intervalSeconds) {
        this.instanceIdResolver = instanceIdResolver;
        this.pushClient = pushClient;
        this.heartbeatUrl = heartbeatUrl;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = Thread.ofVirtual().unstarted(r);
            t.setName("failover-heartbeat-publisher");
            return t;
        });
        scheduler.scheduleAtFixedRate(this::beat, 0, intervalSeconds, TimeUnit.SECONDS);
        log.info("Failover heartbeat publisher active: -> '{}', interval {}s.", heartbeatUrl, intervalSeconds);
    }

    private void beat() {
        try {
            pushClient.send(instanceIdResolver.resolve());
            if (failing.getAndSet(false)) {
                log.info("Failover heartbeat to '{}' recovered.", heartbeatUrl);
            }
        } catch (Exception e) {
            if (!failing.getAndSet(true)) {
                log.warn("Failover heartbeat to '{}' failed: {}.", heartbeatUrl,
                        e.getClass().getSimpleName() + (e.getMessage() != null ? ": " + e.getMessage() : ""));
                log.debug("Heartbeat failure detail:", e);
            }
        }
    }

    @Override
    public void close() {
        scheduler.shutdownNow();
    }
}
