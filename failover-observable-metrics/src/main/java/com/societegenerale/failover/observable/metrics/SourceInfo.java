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

package com.societegenerale.failover.observable.metrics;

/**
 * Provenance of the metrics the dashboard is showing, so the UI can never silently misrepresent
 * single-instance numbers as a cluster aggregate (or vice-versa) — see the distributed-dashboard design.
 *
 * <p>In the default {@code local} mode the figures come from this instance's in-process
 * {@code MeterRegistry} only ({@code instancesReporting = 1}, {@code instancesExpected = -1} since the
 * cluster size is unknown), and the UI renders a "this instance only" badge. Cluster-aware modes
 * (Prometheus / shared store) populate the instance counts and freshness.
 *
 * @param mode               {@code local} | {@code prometheus} | {@code shared-store}
 * @param instancesReporting how many instances contributed to these figures ({@code 1} in local mode)
 * @param instancesExpected  best-known total instance count, or {@code -1} when unknown (local mode)
 * @param asOfEpochMs        freshness of the underlying data (epoch millis)
 * @param partial            {@code true} when some expected instances are missing or stale
 */
public record SourceInfo(
        String mode,
        int instancesReporting,
        int instancesExpected,
        long asOfEpochMs,
        boolean partial) {
}
