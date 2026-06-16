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
 * One sample of the global cumulative counters, captured by the server-side history sampler
 * (design doc §4.2 / §8 option B). Values are the totals at {@code timestamp}; the trend chart deltas
 * consecutive points. Reload-surviving (process-local) trend without depending on the open browser tab.
 *
 * @param timestamp    epoch millis when the sample was taken
 * @param calls        {@code overall.totalCalls}
 * @param failover     {@code overall.failoverInvoked}
 * @param recovered    {@code overall.recovered}
 * @param notRecovered {@code overall.notRecovered}
 * @param store        {@code overall.upstreamSuccess} (values stored after an upstream success)
 * @param recover      {@code overall.failoverInvoked} (recover-path invocations)
 */
public record SeriesPoint(
        long timestamp,
        long calls,
        long failover,
        long recovered,
        long notRecovered,
        long store,
        long recover) {
}
