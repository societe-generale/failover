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

import java.util.List;

/**
 * The {@code /api/metrics} response: the global aggregate plus per-API KPIs and a capture timestamp.
 *
 * @param overall       aggregate KPIs across every failover point
 * @param perApi        per-failover KPIs, sorted by name
 * @param topExceptions most frequent root exception types triggering failover (descending by count)
 * @param timestamp     epoch millis when the snapshot was taken
 */
public record MetricsSummary(
        ApiKpis overall,
        List<ApiKpis> perApi,
        List<ExceptionStat> topExceptions,
        long timestamp) {
}
