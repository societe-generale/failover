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

package com.societegenerale.failover.dashboard.metrics.source.prometheus;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.TreeSet;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Drift guard: locks the Prometheus metric names referenced by every PromQL constant in
 * {@link PrometheusMetricsSource} to the set the failover framework actually exports. If a meter is renamed
 * in {@code MicrometerObservablePublisher} / {@code FailoverMeterBinder}, or a query gains a typo'd or new
 * metric, this test fails and forces the two sides to be reconciled.
 *
 * <p><strong>Keep {@link #EXPORTED_METRICS} in sync with the meters emitted by
 * {@code failover-observable-micrometer}</strong> (Micrometer Prometheus naming: dots→underscores, counters
 * keep their {@code _total}, the timer is exported in seconds as {@code _sum}/{@code _count}/{@code _max}/{@code _bucket}).
 *
 * <p>Not a substitute for a live Prometheus integration smoke (which needs Docker) — it catches name drift
 * statically and runs in normal CI.
 *
 * @author Anand Manissery
 */
class PrometheusQueryDriftTest {

    /** Base Prometheus metric names the framework exports (suffixes like _total/_sum/_bucket stripped). */
    private static final Set<String> EXPORTED_METRICS = Set.of(
            "failover_store",                       // failover.store.total
            "failover_recovery_outcome",            // failover.recovery.outcome.total
            "failover_recovery_partial",            // failover.recovery.partial.total
            "failover_store_async_failed",          // failover.store.async.failed(.total)
            "failover_exception",                   // failover.exception.total
            "failover_operation_duration_seconds"   // failover.operation.duration timer (seconds)
    );

    /** Matches a Prometheus metric token inside a PromQL string. */
    private static final Pattern METRIC = Pattern.compile("failover_[a-z0-9_]+");
    /** Counter/timer export suffixes to strip down to the base name. */
    private static final Pattern SUFFIX = Pattern.compile("(_total|_sum|_count|_max|_bucket)$");

    @Test
    @DisplayName("every PromQL constant references only metrics the framework exports (no drift)")
    void queriesReferenceOnlyExportedMetrics() {
        Set<String> referenced = new TreeSet<>();
        for (String promql : PrometheusMetricsSource.promQlConstants()) {
            Matcher m = METRIC.matcher(promql);
            while (m.find()) {
                referenced.add(stripSuffix(m.group()));
            }
        }

        assertThat(referenced)
                .as("PromQL references a metric the framework does not export — rename drift between "
                        + "PrometheusMetricsSource and MicrometerObservablePublisher/FailoverMeterBinder")
                .isNotEmpty()
                .allMatch(EXPORTED_METRICS::contains);
    }

    private static String stripSuffix(String metric) {
        String base = metric;
        // strip one trailing export suffix; '_seconds_bucket' etc. handled by also keeping the seconds base
        Matcher m = SUFFIX.matcher(base);
        if (m.find()) {
            base = base.substring(0, m.start());
        }
        return base;
    }
}
