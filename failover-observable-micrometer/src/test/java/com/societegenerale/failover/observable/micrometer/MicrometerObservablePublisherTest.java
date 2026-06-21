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

package com.societegenerale.failover.observable.micrometer;

import com.societegenerale.failover.core.observable.Metrics;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * @author Anand Manissery
 */
class MicrometerObservablePublisherTest {

    private MeterRegistry registry;
    private MicrometerObservablePublisher publisher;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        publisher = new MicrometerObservablePublisher(registry);
    }

    // ── store events ──────────────────────────────────────────────────────────

    @Test
    @DisplayName("store success — increments failover.store.total with stored=true")
    void shouldIncrementStoreCounterOnSuccess() {
        publisher.publish(storeMetrics("my-failover", "true"));

        Counter counter = registry.get("failover.store.total")
            .tag("name", "my-failover")
            .tag("stored", "true")
            .counter();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("store failure — increments failover.store.total with stored=false")
    void shouldIncrementStoreCounterOnFailure() {
        publisher.publish(storeMetrics("my-failover", "false"));

        Counter counter = registry.get("failover.store.total")
            .tag("name", "my-failover")
            .tag("stored", "false")
            .counter();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("multiple store events — counter accumulates correctly")
    void shouldAccumulateStoreCounters() {
        publisher.publish(storeMetrics("fo", "true"));
        publisher.publish(storeMetrics("fo", "true"));
        publisher.publish(storeMetrics("fo", "false"));

        assertThat(registry.get("failover.store.total").tag("stored", "true").counter().count()).isEqualTo(2.0);
        assertThat(registry.get("failover.store.total").tag("stored", "false").counter().count()).isEqualTo(1.0);
    }

    // ── recover events ────────────────────────────────────────────────────────

    @Test
    @DisplayName("successful recovery — increments failover.recover.total with recovered=true")
    void shouldIncrementRecoverCounterOnSuccess() {
        publisher.publish(recoverMetrics("my-failover", "true", "false",
            "java.net.SocketException", "java.io.IOException"));

        Counter counter = registry.get("failover.recover.total")
            .tag("name", "my-failover")
            .tag("recovered", "true")
            .tag("recovery_failed", "false")
            .counter();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("failed recovery — increments failover.recover.total with recovered=false, recovery_failed=true")
    void shouldIncrementRecoverCounterOnFailure() {
        publisher.publish(recoverMetrics("my-failover", "false", "true",
            "java.net.SocketException", ""));

        Counter counter = registry.get("failover.recover.total")
            .tag("name", "my-failover")
            .tag("recovered", "false")
            .tag("recovery_failed", "true")
            .counter();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recover event — increments failover.exception.total with correct exception tags")
    void shouldIncrementExceptionCounterWithExceptionType() {
        publisher.publish(recoverMetrics("my-failover", "false", "false",
            "java.net.SocketException", "java.io.IOException"));

        Counter counter = registry.get("failover.exception.total")
            .tag("name", "my-failover")
            .tag("exception_type", "java.net.SocketException")
            .tag("cause_type", "java.io.IOException")
            .counter();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recover event with blank cause — cause_type defaults to 'none'")
    void shouldDefaultCauseTypeToNoneWhenBlank() {
        publisher.publish(recoverMetrics("my-failover", "false", "false",
            "java.lang.RuntimeException", ""));

        Counter counter = registry.get("failover.exception.total")
            .tag("cause_type", "none")
            .counter();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recover event — final_cause_type tag carries the innermost cause")
    void shouldTagFinalCauseType() {
        publisher.publish(Metrics.of("my-failover")
            .collect("action", "recover")
            .collect("exception-type", "com.societegenerale.failover.aspect.ExecutionException")
            .collect("exception-cause-type", "java.lang.IllegalStateException")
            .collect("exception-final-cause-type", "java.net.SocketTimeoutException")
            .collect("is-recovered", "false")
            .collect("is-recovery-failed", "false")
            .collect("recovery-failure-message", ""));

        Counter counter = registry.get("failover.exception.total")
            .tag("exception_type", "com.societegenerale.failover.aspect.ExecutionException")
            .tag("cause_type", "java.lang.IllegalStateException")
            .tag("final_cause_type", "java.net.SocketTimeoutException")
            .counter();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recover event with blank final cause — final_cause_type defaults to 'none'")
    void shouldDefaultFinalCauseTypeToNoneWhenBlank() {
        publisher.publish(recoverMetrics("my-failover", "false", "false",
            "java.lang.RuntimeException", "java.io.IOException"));

        Counter counter = registry.get("failover.exception.total")
            .tag("final_cause_type", "none")
            .counter();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ── timing ───────────────────────────────────────────────────────────────

    @Test
    @DisplayName("store with duration-ns — records Timer failover.operation.duration")
    void shouldRecordTimerForStoreWhenDurationPresent() {
        Metrics metrics = storeMetrics("fo", "true");
        metrics.collect("duration-ns", Long.toString(TimeUnit.MILLISECONDS.toNanos(100)));
        publisher.publish(metrics);

        Timer timer = registry.get("failover.operation.duration")
            .tag("name", "fo")
            .tag("action", "store")
            .timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.MILLISECONDS)).isCloseTo(100.0, org.assertj.core.data.Offset.offset(1.0));
    }

    @Test
    @DisplayName("recover with duration-ns — records Timer failover.operation.duration")
    void shouldRecordTimerForRecoverWhenDurationPresent() {
        Metrics metrics = recoverMetrics("fo", "true", "false", "java.lang.Exception", "");
        metrics.collect("duration-ns", Long.toString(TimeUnit.MILLISECONDS.toNanos(50)));
        publisher.publish(metrics);

        Timer timer = registry.get("failover.operation.duration")
            .tag("name", "fo")
            .tag("action", "recover")
            .timer();
        assertThat(timer.count()).isEqualTo(1);
    }

    @Test
    @DisplayName("no duration-ns — no Timer registered")
    void shouldNotRegisterTimerWhenNoDuration() {
        publisher.publish(storeMetrics("fo", "true"));
        assertThat(registry.find("failover.operation.duration").timer()).isNull();
    }

    @Test
    @DisplayName("malformed duration-ns — Timer skipped silently")
    void shouldIgnoreMalformedDurationNs() {
        Metrics metrics = storeMetrics("fo", "true");
        metrics.collect("duration-ns", "not-a-number");
        publisher.publish(metrics);
        assertThat(registry.find("failover.operation.duration").timer()).isNull();
    }

    // ── async-failure events ───────────────────────────────────────────────────

    @Test
    @DisplayName("async failure — increments failover.store.async.failed tagged with operation and exception type")
    void shouldIncrementAsyncFailedCounter() {
        publisher.publish(Metrics.of("country")
            .collect("action", "store-async-failed")
            .collect("async-operation", "store")
            .collect("exception-type", "java.lang.RuntimeException"));

        Counter counter = registry.get("failover.store.async.failed")
            .tag("name", "country")
            .tag("operation", "store")
            .tag("exception_type", "java.lang.RuntimeException")
            .counter();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("async failure with missing operation/exception tags — falls back to 'unknown'")
    void asyncFailedDefaultsMissingTags() {
        publisher.publish(Metrics.of("country").collect("action", "store-async-failed"));

        Counter counter = registry.get("failover.store.async.failed")
            .tag("operation", "unknown")
            .tag("exception_type", "unknown")
            .counter();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("unknown action — no meter registered")
    void shouldIgnoreUnknownAction() {
        publisher.publish(Metrics.of("fo").collect("action", "no-such-action"));

        assertThat(registry.find("failover.store.total").counter()).isNull();
        assertThat(registry.find("failover.recover.total").counter()).isNull();
        assertThat(registry.find("failover.store.async.failed").counter()).isNull();
    }

    @Test
    @DisplayName("recover with a non-blank exception cause type — cause_type tag carries the value")
    void recoverNonBlankCauseType() {
        publisher.publish(recoverMetrics("fo", "false", "false", "java.lang.Exception", "java.net.SocketTimeoutException"));

        Counter counter = registry.get("failover.exception.total")
            .tag("cause_type", "java.net.SocketTimeoutException")
            .counter();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    // ── recovery outcome (failover / recovery / non-recovery rates) ─────────────

    @Test
    @DisplayName("recovered within expiry — failover.recovery.outcome.total{outcome=recovered} with name, domain, method")
    void outcomeRecovered() {
        publisher.publish(outcomeMetrics("country-by-id", "country", "CountryService#getById", "true", "false"));

        Counter counter = registry.get("failover.recovery.outcome.total")
            .tag("name", "country-by-id")
            .tag("domain", "country")
            .tag("method", "CountryService#getById")
            .tag("outcome", "recovered")
            .counter();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("no stored value (not found / expired) — outcome=not_recovered (the user-impact signal)")
    void outcomeNotRecovered() {
        publisher.publish(outcomeMetrics("country-all", "country", "CountryService#findAll", "false", "false"));

        Counter counter = registry.get("failover.recovery.outcome.total")
            .tag("method", "CountryService#findAll")
            .tag("outcome", "not_recovered")
            .counter();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recover path threw — outcome=error (kept distinct from a clean miss)")
    void outcomeError() {
        publisher.publish(outcomeMetrics("country-all", "country", "CountryService#findAll", "false", "true"));

        Counter counter = registry.get("failover.recovery.outcome.total")
            .tag("method", "CountryService#findAll")
            .tag("outcome", "error")
            .counter();
        assertThat(counter.count()).isEqualTo(1.0);
        // not double-counted as not_recovered
        assertThat(registry.find("failover.recovery.outcome.total").tag("outcome", "not_recovered").counter()).isNull();
    }

    @Test
    @DisplayName("recovery_failed wins over recovered when both set — outcome=error")
    void outcomeErrorTakesPrecedence() {
        publisher.publish(outcomeMetrics("fo", "fo", "S#m", "true", "true"));

        assertThat(registry.get("failover.recovery.outcome.total").tag("outcome", "error").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("missing domain/method — fall back to name and 'unknown'")
    void outcomeDefaultsDomainAndMethod() {
        publisher.publish(recoverMetrics("solo", "true", "false", "java.lang.Exception", ""));

        Counter counter = registry.get("failover.recovery.outcome.total")
            .tag("name", "solo")
            .tag("domain", "solo")
            .tag("method", "unknown")
            .tag("outcome", "recovered")
            .counter();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("failover rate = sum across outcomes; outcomes accumulate independently per method")
    void outcomeAccumulatesPerMethod() {
        publisher.publish(outcomeMetrics("fo", "d", "S#findAll", "true", "false"));
        publisher.publish(outcomeMetrics("fo", "d", "S#findAll", "false", "false"));
        publisher.publish(outcomeMetrics("fo", "d", "S#findAll", "false", "false"));

        double recovered    = registry.get("failover.recovery.outcome.total").tag("method", "S#findAll").tag("outcome", "recovered").counter().count();
        double notRecovered = registry.get("failover.recovery.outcome.total").tag("method", "S#findAll").tag("outcome", "not_recovered").counter().count();
        assertThat(recovered).isEqualTo(1.0);
        assertThat(notRecovered).isEqualTo(2.0);
        assertThat(recovered + notRecovered).isEqualTo(3.0); // failover rate
    }

    // ── partial recovery ──────────────────────────────────────────────────────

    @Test
    @DisplayName("recover-partial — increments failover.recovery.partial.total{name, method}")
    void recoverPartialIncrementsCounter() {
        publisher.publish(Metrics.of("country-all")
                .collect("action", "recover-partial")
                .collect("method", "CountryService#findAll")
                .collect("missing", "2")
                .collect("total", "5"));

        Counter counter = registry.get("failover.recovery.partial.total")
            .tag("name", "country-all")
            .tag("method", "CountryService#findAll")
            .counter();
        assertThat(counter.count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recover-partial with missing method tag — falls back to 'unknown'")
    void recoverPartialDefaultsMethod() {
        publisher.publish(Metrics.of("fo").collect("action", "recover-partial"));

        assertThat(registry.get("failover.recovery.partial.total").tag("method", "unknown").counter().count()).isEqualTo(1.0);
    }

    // ── call volume & user impact (derived from store/recover) ─────────────────

    @Test
    @DisplayName("store event — failover.call.total{result=success} and user.impact{impact=unblocked}")
    void storeIncrementsCallSuccessAndUnblocked() {
        publisher.publish(storeMetrics("country", "true"));

        assertThat(registry.get("failover.call.total").tag("name", "country").tag("result", "success").counter().count())
                .isEqualTo(1.0);
        assertThat(registry.get("failover.user.impact.total").tag("name", "country").tag("impact", "unblocked").counter().count())
                .isEqualTo(1.0);
    }

    @Test
    @DisplayName("store that did not persist (stored=false) — caller still unblocked (upstream returned)")
    void storeNotPersistedStillUnblocked() {
        publisher.publish(storeMetrics("country", "false"));

        assertThat(registry.get("failover.call.total").tag("result", "success").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("failover.user.impact.total").tag("impact", "unblocked").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("recovered failover — failover.call.total{result=failover} and user.impact{impact=unblocked}")
    void recoverRecoveredIsFailoverAndUnblocked() {
        publisher.publish(recoverMetrics("country", "true", "false", "java.lang.Exception", ""));

        assertThat(registry.get("failover.call.total").tag("result", "failover").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("failover.user.impact.total").tag("impact", "unblocked").counter().count()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("non-recovered failover — user.impact{impact=blocked} (upstream failed, nothing to return)")
    void recoverNotRecoveredIsBlocked() {
        publisher.publish(recoverMetrics("country", "false", "false", "java.lang.Exception", ""));

        assertThat(registry.get("failover.call.total").tag("result", "failover").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("failover.user.impact.total").tag("impact", "blocked").counter().count()).isEqualTo(1.0);
        assertThat(registry.find("failover.user.impact.total").tag("impact", "unblocked").counter()).isNull();
    }

    @Test
    @DisplayName("call/impact carry the domain tag")
    void callAndImpactCarryDomain() {
        publisher.publish(storeMetrics("country-by-id", "true").collect("domain", "country"));

        assertThat(registry.get("failover.call.total").tag("domain", "country").counter().count()).isEqualTo(1.0);
        assertThat(registry.get("failover.user.impact.total").tag("domain", "country").counter().count()).isEqualTo(1.0);
    }

    // Note: operation.duration enables publishPercentileHistogram() for p95/p99. Percentile-histogram
    // buckets are materialised by the Prometheus registry (not SimpleMeterRegistry), so they are verified
    // against Prometheus rather than here; timer recording itself is covered by the timer tests above.

    // ── upstream duration ──────────────────────────────────────────────────────

    @Test
    @DisplayName("upstream success — records failover.upstream.duration{result=success}")
    void upstreamSuccessRecordsTimer() {
        publisher.publish(upstreamMetrics("country", "success", "2500000"));

        Timer timer = registry.get("failover.upstream.duration")
            .tag("name", "country").tag("result", "success").timer();
        assertThat(timer.count()).isEqualTo(1);
        assertThat(timer.totalTime(TimeUnit.NANOSECONDS)).isEqualTo(2500000.0);
    }

    @Test
    @DisplayName("upstream failure — records failover.upstream.duration{result=failure}")
    void upstreamFailureRecordsTimer() {
        publisher.publish(upstreamMetrics("country", "failure", "100"));

        assertThat(registry.get("failover.upstream.duration").tag("result", "failure").timer().count()).isEqualTo(1);
    }

    @Test
    @DisplayName("upstream without duration — no timer registered")
    void upstreamWithoutDurationNoTimer() {
        publisher.publish(Metrics.of("country").collect("action", "upstream").collect("upstream-result", "success"));

        assertThat(registry.find("failover.upstream.duration").timer()).isNull();
    }

    @Test
    @DisplayName("upstream event does not pollute failover.operation.duration")
    void upstreamDoesNotPolluteOperationDuration() {
        publisher.publish(upstreamMetrics("country", "success", "2500000"));

        assertThat(registry.find("failover.operation.duration").timer()).isNull();
    }

    // ── api health & stale gauges ──────────────────────────────────────────────

    @Test
    @DisplayName("api.health gauge reflects recent served/blocked mix; stale.served.ratio reflects stale share")
    void healthAndStaleGauges() {
        publisher.publish(storeMetrics("country", "true"));                                   // fresh
        publisher.publish(recoverMetrics("country", "true", "false", "java.lang.Exception", "")); // stale
        publisher.publish(recoverMetrics("country", "false", "false", "java.lang.Exception", "")); // blocked

        double health = registry.get("failover.api.health").tag("name", "country").gauge().value();
        double stale = registry.get("failover.stale.served.ratio").tag("name", "country").gauge().value();
        assertThat(health).isCloseTo(2.0 / 3, org.assertj.core.api.Assertions.within(1e-9));
        assertThat(stale).isCloseTo(1.0 / 3, org.assertj.core.api.Assertions.within(1e-9));
    }

    @Test
    @DisplayName("api.health gauge carries the domain tag and is registered once per name")
    void healthGaugeCarriesDomainAndRegistersOnce() {
        publisher.publish(storeMetrics("country-by-id", "true").collect("domain", "country"));
        publisher.publish(storeMetrics("country-by-id", "true").collect("domain", "country"));

        assertThat(registry.get("failover.api.health").tag("domain", "country").gauge().value()).isEqualTo(1.0);
        assertThat(registry.find("failover.api.health").tag("name", "country-by-id").gauges()).hasSize(1);
    }

    // ── startup/config events ─────────────────────────────────────────────────

    @Test
    @DisplayName("startup report event (no failover-action) — ignored silently")
    void shouldIgnoreStartupReportEvents() {
        Metrics startup = Metrics.of("startup-report")
            .collect("metrics-as-on", "2026-01-01T00:00:00Z")
            .collect("service-start-time", "2026-01-01T00:00:00Z");
        publisher.publish(startup);

        assertThat(registry.find("failover.store.total").counter()).isNull();
        assertThat(registry.find("failover.recover.total").counter()).isNull();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Metrics storeMetrics(String name, String stored) {
        return Metrics.of(name)
            .collect("action", "store")
            .collect("expiry-duration", "1")
            .collect("expiry-unit", "HOURS")
            .collect("is-stored", stored);
    }

    private static Metrics upstreamMetrics(String name, String result, String durationNs) {
        return Metrics.of(name)
            .collect("action", "upstream")
            .collect("upstream-result", result)
            .collect("upstream-duration-ns", durationNs);
    }

    private static Metrics recoverMetrics(String name, String recovered, String recoveryFailed,
                                          String exType, String causeType) {
        return Metrics.of(name)
            .collect("action", "recover")
            .collect("expiry-duration", "1")
            .collect("expiry-unit", "HOURS")
            .collect("exception-type", exType)
            .collect("exception-cause-type", causeType)
            .collect("is-recovered", recovered)
            .collect("is-recovery-failed", recoveryFailed)
            .collect("recovery-failure-message", "");
    }

    private static Metrics outcomeMetrics(String name, String domain, String method,
                                          String recovered, String recoveryFailed) {
        return recoverMetrics(name, recovered, recoveryFailed, "java.lang.Exception", "")
            .collect("domain", domain)
            .collect("method", method);
    }
}
