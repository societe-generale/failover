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

package com.societegenerale.failover.properties;

import lombok.Data;

/**
 * Observability configuration for the failover framework, bound under {@code failover.observable}.
 *
 * @author Anand Manissery
 */
@Data
public class Observable {

    private Async async = new Async();

    private Instance instance = new Instance();

    private Cardinality cardinality = new Cardinality();

    /**
     * Non-blocking metric publishing. When {@link #enabled} (default), failover metrics are handed off to a
     * bounded queue and drained by a virtual-thread worker, so emitting metrics can never block or slow the
     * caller's {@code @Failover} business call. A full queue drops metrics (counted as
     * {@code failover.metrics.dropped.total}) rather than back-pressuring the caller.
     *
     * <p>Set {@code failover.observable.async.enabled=false} to publish synchronously on the caller thread —
     * used for deterministic assertions in integration tests, mirroring {@code failover.store.async=false}.
     */
    @Data
    public static class Async {

        /** Whether metric publishing is off-loaded to the async drain worker (default {@code true}). */
        private boolean enabled = true;

        /**
         * Bounded queue capacity. A full queue drops metrics (non-blocking by design) rather than
         * back-pressuring the caller. Raise it for very high failover throughput. Must be {@code > 0}.
         */
        private int queueCapacity = 10_000;
    }

    /**
     * Controls the {@code instance} tag on {@code failover.*} meters, which makes figures attributable to the
     * emitting instance in a cluster. Who supplies that tag depends on the backend, so the default is
     * <strong>{@code auto}</strong>: tag every registry <em>except</em> a Prometheus one (Prometheus attaches an
     * {@code instance} label at scrape time, and a duplicate would be relabelled to {@code exported_instance}).
     * Push backends (OTLP / Elastic / Datadog) have no scrape-time label, so {@code auto} tags them — no config
     * needed. Use {@code always} to force the tag on every registry (incl. Prometheus), {@code never} to disable.
     */
    @Data
    public static class Instance {

        /** Tagging mode: {@code auto} (default — tag all registries except Prometheus) | {@code always} | {@code never}. */
        private Mode mode = Mode.AUTO;

        /**
         * Instance identifier used as the tag value. When blank it is resolved at startup from
         * {@code spring.application.name} and the host name (e.g. {@code orders-service:host-42}); on
         * Kubernetes/Docker set it to {@code ${HOSTNAME}} or the pod name for a reliable, readable identity.
         */
        private String id = "";

        /** {@code instance}-tag strategy. */
        public enum Mode {
            /** Tag every registry except a Prometheus one (which adds {@code instance} itself at scrape). The default. */
            AUTO,
            /** Tag every registry, including Prometheus (yields {@code exported_instance} there). */
            ALWAYS,
            /** Never add the {@code instance} tag. */
            NEVER
        }
    }

    /**
     * Bounds meter cardinality on the {@code failover.*} meters as a safety guard: caps the number of
     * distinct {@code name} tag values, denying further new series once the cap is hit (so a misconfigured
     * high-cardinality failover name can never explode the registry). On by default.
     */
    @Data
    public static class Cardinality {

        /** Whether the cardinality guard is active (default {@code true}). */
        private boolean enabled = true;

        /** Maximum distinct {@code name} tag values allowed across {@code failover.*} meters. Must be {@code > 0}. */
        private int maxApis = 1_000;
    }
}
