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

package com.societegenerale.failover.dashboard.metrics;

/**
 * Wall-time latency of the failover store and recover paths, in milliseconds, derived from the
 * {@code failover.operation.duration} timer. Mean and max are always present; p95/p99 are present for the
 * {@code local} and {@code prometheus} sources (the timer publishes percentiles / histogram buckets) and
 * {@code 0} for {@code shared-store}, where per-instance percentiles cannot be merged (snapshots carry only
 * mean/max). A {@code 0} percentile therefore means "not available", not "zero latency".
 *
 * @param storeMeanMs   mean store-path latency (ms)
 * @param storeMaxMs    max store-path latency (ms)
 * @param recoverMeanMs mean recover-path latency (ms)
 * @param recoverMaxMs  max recover-path latency (ms)
 * @param storeP95Ms    95th-percentile store-path latency (ms); {@code 0} when unavailable
 * @param storeP99Ms    99th-percentile store-path latency (ms); {@code 0} when unavailable
 * @param recoverP95Ms  95th-percentile recover-path latency (ms); {@code 0} when unavailable
 * @param recoverP99Ms  99th-percentile recover-path latency (ms); {@code 0} when unavailable
 */
public record Latency(
        double storeMeanMs,
        double storeMaxMs,
        double recoverMeanMs,
        double recoverMaxMs,
        double storeP95Ms,
        double storeP99Ms,
        double recoverP95Ms,
        double recoverP99Ms) {

    /** Convenience for sources that expose only mean/max (e.g. shared-store): percentiles default to {@code 0}. */
    public Latency(double storeMeanMs, double storeMaxMs, double recoverMeanMs, double recoverMaxMs) {
        this(storeMeanMs, storeMaxMs, recoverMeanMs, recoverMaxMs, 0, 0, 0, 0);
    }
}
