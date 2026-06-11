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
}
