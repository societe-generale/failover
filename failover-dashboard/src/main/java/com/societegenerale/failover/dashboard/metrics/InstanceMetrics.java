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
 * One cluster member's metrics, for the dashboard's per-instance view. Lets the UI answer "is it one bad
 * node or all of them?" — the cluster aggregate alone cannot. Carries that instance's own {@link MetricsSummary}
 * (not the cluster sum) plus when it was last seen, so the UI can flag silent peers.
 *
 * <p>Populated only by sources that retain a per-instance dimension — {@code shared-store} (the snapshot store
 * holds one entry per instance) and {@code prometheus} (the {@code instance} label). {@code local} reports a
 * single entry for this instance.
 *
 * @param instanceId      the emitting instance's identifier (e.g. {@code orders-svc:host-1})
 * @param lastSeenEpochMs epoch millis of that instance's most recent data point ({@code 0} when unknown)
 * @param summary         that instance's own KPI summary (its overall + per-API), not the cluster aggregate
 * @author Anand Manissery
 */
public record InstanceMetrics(String instanceId, long lastSeenEpochMs, MetricsSummary summary) {
}
