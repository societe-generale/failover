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

package com.societegenerale.failover.dashboard;

import com.societegenerale.failover.dashboard.dto.ApiHealth;
import com.societegenerale.failover.dashboard.dto.ApiKpis;
import com.societegenerale.failover.dashboard.dto.ExceptionStat;
import com.societegenerale.failover.dashboard.dto.MetricsSummary;
import com.societegenerale.failover.dashboard.dto.Rates;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.within;

class DashboardMetricsServiceTest {

    private static final double EPS = 1e-9;

    private final MeterRegistry registry = new SimpleMeterRegistry();
    private DashboardMetricsService service;

    @BeforeEach
    void setUp() {
        service = new DashboardMetricsService(registry, new DashboardProperties(true, "/failover-dashboard"));
    }

    // ── meter fixtures (mirror MicrometerObservablePublisher's contract) ──────

    private void store(String name, boolean stored, int times) {
        Counter.builder("failover.store.total")
                .tag("name", name).tag("stored", String.valueOf(stored))
                .register(registry).increment(times);
    }

    private void outcome(String name, String domain, String outcome, int times) {
        Counter.builder("failover.recovery.outcome.total")
                .tag("name", name).tag("domain", domain).tag("method", "m").tag("outcome", outcome)
                .register(registry).increment(times);
    }

    private void partial(String name, int times) {
        Counter.builder("failover.recovery.partial.total")
                .tag("name", name).tag("method", "m")
                .register(registry).increment(times);
    }

    private void asyncFailed(String name, int times) {
        Counter.builder("failover.store.async.failed")
                .tag("name", name).tag("operation", "store").tag("exception_type", "java.lang.RuntimeException")
                .register(registry).increment(times);
    }

    private void duration(String name, String action, double ms, int samples) {
        Timer t = Timer.builder("failover.operation.duration").tag("name", name).tag("action", action).register(registry);
        for (int i = 0; i < samples; i++) {
            t.record((long) (ms * 1_000_000), TimeUnit.NANOSECONDS);
        }
    }

    private void exception(String name, String type, int times) {
        Counter.builder("failover.exception.total")
                .tag("name", name).tag("exception_type", type).tag("cause_type", "none")
                .register(registry).increment(times);
    }

    // ── rate derivations ──────────────────────────────────────────────────────

    @Test
    @DisplayName("derives every rate; error folds into non-recovery but counts separately")
    void derivesRates() {
        store("country", true, 90);
        outcome("country", "geo", "recovered", 8);
        outcome("country", "geo", "not_recovered", 1);
        outcome("country", "geo", "error", 1);
        partial("country", 3);

        ApiKpis k = service.metricsSummary().perApi().get(0);

        assertThat(k.name()).isEqualTo("country");
        assertThat(k.domain()).isEqualTo("geo");
        assertThat(k.totalCalls()).isEqualTo(100);
        assertThat(k.upstreamSuccess()).isEqualTo(90);
        assertThat(k.failoverInvoked()).isEqualTo(10);
        assertThat(k.recovered()).isEqualTo(8);
        assertThat(k.notRecovered()).isEqualTo(1);
        assertThat(k.errors()).isEqualTo(1);
        assertThat(k.partial()).isEqualTo(3);

        Rates r = k.rates();
        assertThat(r.successRate()).isEqualTo(0.90, within(EPS));
        assertThat(r.failoverRate()).isEqualTo(0.10, within(EPS));
        assertThat(r.recoveryRate()).isEqualTo(0.80, within(EPS));
        assertThat(r.nonRecoveryRate()).isEqualTo(0.20, within(EPS));
        assertThat(r.healthyRate()).isEqualTo(0.98, within(EPS));
    }

    @Test
    @DisplayName("no calls ⇒ rates are 0, never NaN (divide-by-zero guard)")
    void divideByZeroYieldsZero() {
        MetricsSummary summary = service.metricsSummary();

        assertThat(summary.perApi()).isEmpty();
        Rates r = summary.overall().rates();
        assertThat(r.successRate()).isZero();
        assertThat(r.failoverRate()).isZero();
        assertThat(r.recoveryRate()).isZero();
        assertThat(r.nonRecoveryRate()).isZero();
        assertThat(r.healthyRate()).isZero();
    }

    @Test
    @DisplayName("failover with zero successes still avoids NaN in recovery/non-recovery")
    void allFailoverNoSuccess() {
        outcome("svc", "svc", "recovered", 3);
        outcome("svc", "svc", "not_recovered", 1);

        Rates r = service.metricsSummary().perApi().get(0).rates();

        assertThat(r.successRate()).isZero();
        assertThat(r.failoverRate()).isEqualTo(1.0, within(EPS));
        assertThat(r.recoveryRate()).isEqualTo(0.75, within(EPS));
        assertThat(r.nonRecoveryRate()).isEqualTo(0.25, within(EPS));
    }

    @Test
    @DisplayName("overall aggregates across all APIs")
    void overallAggregates() {
        store("a", true, 50);
        outcome("a", "a", "recovered", 5);
        store("b", true, 30);
        outcome("b", "b", "not_recovered", 5);

        ApiKpis overall = service.metricsSummary().overall();

        assertThat(overall.name()).isEqualTo("__overall__");
        assertThat(overall.upstreamSuccess()).isEqualTo(80);
        assertThat(overall.recovered()).isEqualTo(5);
        assertThat(overall.notRecovered()).isEqualTo(5);
        assertThat(overall.totalCalls()).isEqualTo(90);
        assertThat(service.metricsSummary().perApi()).extracting(ApiKpis::name).containsExactly("a", "b");
    }

    @Test
    @DisplayName("counters missing tags are handled: no domain ⇒ domain falls back to name; no name ⇒ skipped")
    void untaggedCountersHandledGracefully() {
        // outcome counter for 'x' but WITHOUT a domain tag → domain falls back to the name
        Counter.builder("failover.recovery.outcome.total")
                .tag("name", "x").tag("outcome", "recovered")
                .register(registry).increment();
        // a store counter with no 'name' tag at all → ignored during name discovery
        Counter.builder("failover.store.total").tag("stored", "true").register(registry).increment();

        MetricsSummary summary = service.metricsSummary();

        assertThat(summary.perApi()).extracting(ApiKpis::name).containsExactly("x");
        assertThat(summary.perApi().get(0).domain()).isEqualTo("x");
    }

    // ── async failures, latency, top exceptions ───────────────────────────────

    @Test
    @DisplayName("surfaces async failures, store/recover latency (ms) and top exception types")
    void surfacesAsyncLatencyAndExceptions() {
        store("country", true, 100);
        asyncFailed("country", 4);
        duration("country", "store", 2.0, 3);    // mean 2ms
        duration("country", "recover", 5.0, 2);  // mean 5ms, max 5ms
        exception("country", "java.net.SocketTimeoutException", 7);
        exception("country", "java.net.ConnectException", 3);

        MetricsSummary s = service.metricsSummary();
        ApiKpis k = s.perApi().get(0);

        assertThat(k.asyncFailed()).isEqualTo(4);
        assertThat(k.latency().storeMeanMs()).isEqualTo(2.0, within(0.5));
        assertThat(k.latency().recoverMeanMs()).isEqualTo(5.0, within(0.5));
        assertThat(k.latency().recoverMaxMs()).isGreaterThanOrEqualTo(5.0);

        assertThat(s.overall().asyncFailed()).isEqualTo(4);
        assertThat(s.topExceptions()).extracting(ExceptionStat::type)
                .containsExactly("java.net.SocketTimeoutException", "java.net.ConnectException");
        assertThat(s.topExceptions().get(0).count()).isEqualTo(7);
    }

    @Test
    @DisplayName("top exceptions report the innermost cause, not the aspect's ExecutionException wrapper")
    void topExceptionsUnwrapAspectWrapper() {
        // The aspect wraps every upstream throwable, so exception_type is always the wrapper. The real
        // exception is the innermost cause (final_cause_type); the dashboard must surface that, ahead of
        // the first-level cause_type and the wrapper.
        Counter.builder("failover.exception.total")
                .tag("name", "country")
                .tag("exception_type", "com.societegenerale.failover.aspect.ExecutionException")
                .tag("cause_type", "java.lang.IllegalStateException")
                .tag("final_cause_type", "java.net.SocketTimeoutException")
                .register(registry).increment(5);

        MetricsSummary s = service.metricsSummary();

        assertThat(s.topExceptions()).extracting(ExceptionStat::type)
                .containsExactly("java.net.SocketTimeoutException");
        assertThat(s.topExceptions().get(0).count()).isEqualTo(5);
    }

    @Test
    @DisplayName("top exceptions fall back to first-level cause, then wrapper, when inner tags are 'none'")
    void topExceptionsFallBackWhenNoFinalCause() {
        Counter.builder("failover.exception.total")
                .tag("name", "a").tag("exception_type", "Wrapper")
                .tag("cause_type", "java.io.IOException").tag("final_cause_type", "none")
                .register(registry).increment(3);
        Counter.builder("failover.exception.total")
                .tag("name", "b").tag("exception_type", "java.lang.RuntimeException")
                .tag("cause_type", "none").tag("final_cause_type", "none")
                .register(registry).increment(2);

        assertThat(service.metricsSummary().topExceptions()).extracting(ExceptionStat::type)
                .containsExactly("java.io.IOException", "java.lang.RuntimeException");
    }

    @Test
    @DisplayName("no timers / async / exceptions ⇒ zero latency, zero failures, empty exception list")
    void zeroWhenMetersAbsent() {
        store("svc", true, 10);

        MetricsSummary s = service.metricsSummary();
        ApiKpis k = s.perApi().get(0);

        assertThat(k.asyncFailed()).isZero();
        assertThat(k.latency().storeMeanMs()).isZero();
        assertThat(k.latency().recoverMeanMs()).isZero();
        assertThat(s.topExceptions()).isEmpty();
    }

    // ── health classification ─────────────────────────────────────────────────

    @Test
    @DisplayName("health classified HEALTHY / DEGRADED / UNHEALTHY against thresholds")
    void healthClassification() {
        store("healthy", true, 100);                 // healthyRate 1.0  -> HEALTHY
        store("degraded", true, 95);
        outcome("degraded", "degraded", "not_recovered", 5); // healthyRate 0.95 -> DEGRADED
        store("unhealthy", true, 50);
        outcome("unhealthy", "unhealthy", "not_recovered", 50); // healthyRate 0.5 -> UNHEALTHY

        var byName = service.health().stream()
                .collect(java.util.stream.Collectors.toMap(ApiHealth::name, ApiHealth::status));

        assertThat(byName).containsEntry("healthy", "HEALTHY")
                .containsEntry("degraded", "DEGRADED")
                .containsEntry("unhealthy", "UNHEALTHY");
    }

    @Test
    @DisplayName("custom thresholds shift the classification boundaries")
    void customThresholds() {
        DashboardProperties props = new DashboardProperties(true, "/failover-dashboard",
                new DashboardProperties.Health(0.80, 0.50));
        DashboardMetricsService custom = new DashboardMetricsService(registry, props);
        store("svc", true, 85);
        outcome("svc", "svc", "not_recovered", 15); // healthyRate 0.85 -> HEALTHY under 0.80 floor

        assertThat(custom.health().get(0).status()).isEqualTo("HEALTHY");
    }
}
