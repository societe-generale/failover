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

package com.societegenerale.failover.dashboard.dto;

/**
 * Absolute counts plus derived {@link Rates} for one failover point (or {@code "__overall__"} for the
 * aggregate). Counts are summed from the existing {@code failover.*} Micrometer counters — never any
 * payload, key, or argument data (design doc §9).
 *
 * @param name            failover name, or {@code "__overall__"} for the global aggregate
 * @param domain          failover domain (falls back to {@code name})
 * @param totalCalls      {@code upstreamSuccess + failoverInvoked}
 * @param upstreamSuccess {@code failover.store.total{stored=true}}
 * @param failoverInvoked {@code recovered + notRecovered + errors}
 * @param recovered       {@code failover.recovery.outcome.total{outcome=recovered}}
 * @param notRecovered    {@code failover.recovery.outcome.total{outcome=not_recovered}}
 * @param errors          {@code failover.recovery.outcome.total{outcome=error}}
 * @param partial         {@code failover.recovery.partial.total}
 * @param asyncFailed     {@code failover.store.async.failed} — async writes that failed in the executor
 * @param latency         store / recover path latency (ms), from {@code failover.operation.duration}
 * @param rates           derived rate KPIs
 */
public record ApiKpis(
        String name,
        String domain,
        long totalCalls,
        long upstreamSuccess,
        long failoverInvoked,
        long recovered,
        long notRecovered,
        long errors,
        long partial,
        long asyncFailed,
        Latency latency,
        Rates rates) {
}
