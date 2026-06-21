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

package com.societegenerale.failover.dashboard.metrics.source;

import com.societegenerale.failover.dashboard.config.DashboardProperties;

import com.societegenerale.failover.dashboard.metrics.ApiHealth;
import com.societegenerale.failover.dashboard.metrics.ApiKpis;
import com.societegenerale.failover.dashboard.metrics.Latency;
import com.societegenerale.failover.dashboard.metrics.Rates;

import java.util.List;

/**
 * Pure KPI math shared by every {@link MetricsSource}: turning raw per-API counts into {@link ApiKpis}
 * (with derived {@link Rates}), summing them into the overall aggregate, and classifying health. Kept
 * source-agnostic so the local-registry and cluster (Prometheus / shared-store) sources produce identical
 * shapes from the same formulas (design doc §4.4).
 *
 * <p>Rate denominators that are zero yield {@code 0.0} (never {@code NaN}).
 *
 * @author Anand Manissery
 */
public final class DashboardKpis {

    /** Name used for the global aggregate row. */
    public static final String OVERALL = "__overall__";

    private DashboardKpis() {
    }

    /** Builds one failover point's KPIs (with derived rates) from its raw counts and latency. */
    public static ApiKpis build(String name, String domain, long success, long recovered, long notRecovered,
                         long errors, long partial, long asyncFailed, Latency latency) {
        long failover = recovered + notRecovered + errors;
        long total = success + failover;
        Rates rates = new Rates(
                ratio(success, total),
                ratio(failover, total),
                ratio(recovered, failover),
                ratio(notRecovered + errors, failover),
                ratio(success + recovered, total));
        return new ApiKpis(name, domain, total, success, failover, recovered, notRecovered, errors,
                partial, asyncFailed, latency, rates);
    }

    /** Sums per-API KPIs into the global {@link #OVERALL} aggregate, with the supplied overall latency. */
    public static ApiKpis overall(List<ApiKpis> perApi, Latency latency) {
        long success = perApi.stream().mapToLong(ApiKpis::upstreamSuccess).sum();
        long recovered = perApi.stream().mapToLong(ApiKpis::recovered).sum();
        long notRecovered = perApi.stream().mapToLong(ApiKpis::notRecovered).sum();
        long errors = perApi.stream().mapToLong(ApiKpis::errors).sum();
        long partial = perApi.stream().mapToLong(ApiKpis::partial).sum();
        long asyncFailed = perApi.stream().mapToLong(ApiKpis::asyncFailed).sum();
        return build(OVERALL, OVERALL, success, recovered, notRecovered, errors, partial, asyncFailed, latency);
    }

    /** Classifies a healthy-rate into {@code HEALTHY} / {@code DEGRADED} / {@code UNHEALTHY}. */
    public static ApiHealth classify(String name, double healthyRate, DashboardProperties.Health thresholds) {
        ApiHealth.Status status = healthyRate >= thresholds.degradedThreshold() ? ApiHealth.Status.HEALTHY
                : healthyRate >= thresholds.unhealthyThreshold() ? ApiHealth.Status.DEGRADED
                : ApiHealth.Status.UNHEALTHY;
        return new ApiHealth(name, status.name(), healthyRate);
    }

    static double ratio(long numerator, long denominator) {
        return denominator == 0 ? 0.0 : (double) numerator / denominator;
    }
}
