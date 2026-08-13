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

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link FailoverConfigSnapshotService} rebuilds {@link ConfigEntry} purely from the
 * {@code failover.config.expiry.seconds} / {@code failover.config.global} gauge tags — the same gauges
 * {@code FailoverMeterBinder} registers in {@code failover-observable-micrometer}. Registering them
 * directly here keeps this module's tests free of any {@code FailoverScanner} dependency.
 */
class FailoverConfigSnapshotServiceTest {

    private static void registerExpiryGauge(MeterRegistry registry, String name, String domain, String unit,
                                            long duration, boolean recoverAll, String payloadSplitter,
                                            String keyGenerator, String expiryPolicy) {
        Gauge.builder("failover.config.expiry.seconds", () -> 1)
                .tag("name", name)
                .tag("domain", domain)
                .tag("unit", unit)
                .tag("duration", String.valueOf(duration))
                .tag("recoverAll", String.valueOf(recoverAll))
                .tag("payloadSplitter", payloadSplitter)
                .tag("keyGenerator", keyGenerator)
                .tag("expiryPolicy", expiryPolicy)
                .register(registry);
    }

    private static void registerGlobalGauge(MeterRegistry registry, String storeType, String executionType,
                                            String exceptionPolicy, boolean asyncStore) {
        Gauge.builder("failover.config.global", () -> 1)
                .tag("storeType", storeType)
                .tag("executionType", executionType)
                .tag("exceptionPolicy", exceptionPolicy)
                .tag("asyncStore", String.valueOf(asyncStore))
                .register(registry);
    }

    @Test
    @DisplayName("maps gauge tags to a ConfigEntry, sorted by name, echoing the global gauge")
    void mapsGaugeTagsAndGlobals() {
        MeterRegistry registry = new SimpleMeterRegistry();
        registerExpiryGauge(registry, "country-by-code", "country", "HOURS", 24, true,
                "mySplitter", "myKeyGen", "myExpiryPolicy");
        registerGlobalGauge(registry, "jdbc", "resilience", "never_throw", false);

        ConfigEntry e = new FailoverConfigSnapshotService(registry).configEntries().getFirst();

        assertThat(e.name()).isEqualTo("country-by-code");
        assertThat(e.domain()).isEqualTo("country");
        assertThat(e.expiryDuration()).isEqualTo(24L);
        assertThat(e.expiryUnit()).isEqualTo("HOURS");
        assertThat(e.recoverAll()).isTrue();
        assertThat(e.payloadSplitter()).isEqualTo("mySplitter");
        assertThat(e.keyGenerator()).isEqualTo("myKeyGen");
        assertThat(e.expiryPolicy()).isEqualTo("myExpiryPolicy");
        assertThat(e.storeType()).isEqualTo("jdbc");
        assertThat(e.executionType()).isEqualTo("resilience");
        assertThat(e.exceptionPolicy()).isEqualTo("never_throw");
        assertThat(e.asyncStore()).isFalse();
    }

    @Test
    @DisplayName("empty per-annotation override tags render as 'default'")
    void emptyOverridesBecomeDefault() {
        MeterRegistry registry = new SimpleMeterRegistry();
        registerExpiryGauge(registry, "alpha", "alpha", "HOURS", 1, false, "", "", "");

        ConfigEntry e = new FailoverConfigSnapshotService(registry).configEntries().getFirst();

        assertThat(e.payloadSplitter()).isEqualTo("default");
        assertThat(e.keyGenerator()).isEqualTo("default");
        assertThat(e.expiryPolicy()).isEqualTo("default");
    }

    @Test
    @DisplayName("global defaults applied when no failover.config.global gauge is registered")
    void globalDefaultsWhenGlobalGaugeAbsent() {
        MeterRegistry registry = new SimpleMeterRegistry();
        registerExpiryGauge(registry, "alpha", "alpha", "HOURS", 1, false, "", "", "");

        ConfigEntry e = new FailoverConfigSnapshotService(registry).configEntries().getFirst();

        assertThat(e.storeType()).isEqualTo("inmemory");
        assertThat(e.executionType()).isEqualTo("basic");
        assertThat(e.exceptionPolicy()).isEqualTo("rethrow");
        assertThat(e.asyncStore()).isTrue();
    }

    @Test
    @DisplayName("entries are sorted by name")
    void sortedByName() {
        MeterRegistry registry = new SimpleMeterRegistry();
        registerExpiryGauge(registry, "zebra", "zebra", "HOURS", 1, false, "", "", "");
        registerExpiryGauge(registry, "alpha", "alpha", "HOURS", 1, false, "", "", "");

        List<ConfigEntry> entries = new FailoverConfigSnapshotService(registry).configEntries();

        assertThat(entries).extracting(ConfigEntry::name).containsExactly("alpha", "zebra");
    }

    @Test
    @DisplayName("no gauges registered yields an empty list")
    void emptyWhenNoGaugesRegistered() {
        MeterRegistry registry = new SimpleMeterRegistry();

        assertThat(new FailoverConfigSnapshotService(registry).configEntries()).isEmpty();
    }
}
