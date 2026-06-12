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

import com.societegenerale.failover.annotations.Failover;
import com.societegenerale.failover.core.expiry.BasicFailoverExpiryExtractor;
import com.societegenerale.failover.core.expiry.FailoverExpiryExtractor;
import com.societegenerale.failover.core.observable.scanner.FailoverScanner;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.temporal.ChronoUnit;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * @author Anand Manissery
 */
class FailoverMeterBinderTest {

    private SimpleMeterRegistry registry;
    private FailoverScanner scanner;
    private FailoverMeterBinder binder;

    @BeforeEach
    void setUp() {
        registry = new SimpleMeterRegistry();
        scanner = mock(FailoverScanner.class);
        FailoverExpiryExtractor expiryExtractor = new BasicFailoverExpiryExtractor();
        binder = new FailoverMeterBinder(scanner, expiryExtractor);
    }

    @Test
    @DisplayName("registered.total gauge reflects live scanner count")
    void shouldRegisterTotalCountGaugeAsLazySupplier() {
        Failover fo = failover("fo", 2, ChronoUnit.HOURS);
        when(scanner.findAllFailover()).thenReturn(List.of(fo));

        binder.bindTo(registry);

        Gauge gauge = registry.get("failover.registered.total").gauge();
        assertThat(gauge.value()).isEqualTo(1.0);
    }

    @Test
    @DisplayName("registered.total gauge updates when scanner discovers more failovers")
    void shouldReflectUpdatedCountFromScanner() {
        Failover fo1 = failover("fo1", 1, ChronoUnit.HOURS);
        Failover fo2 = failover("fo2", 1, ChronoUnit.HOURS);
        when(scanner.findAllFailover())
            .thenReturn(List.of(fo1))    // first sample
            .thenReturn(List.of(fo1, fo2)); // second sample

        binder.bindTo(registry);

        Gauge gauge = registry.get("failover.registered.total").gauge();
        assertThat(gauge.value()).isEqualTo(1.0);
        assertThat(gauge.value()).isEqualTo(2.0);
    }

    @Test
    @DisplayName("registered.total gauge = 0 when no failovers registered")
    void shouldReturnZeroGaugeWhenEmpty() {
        when(scanner.findAllFailover()).thenReturn(List.of());
        binder.bindTo(registry);

        assertThat(registry.get("failover.registered.total").gauge().value()).isEqualTo(0.0);
    }

    @Test
    @DisplayName("expiry.seconds gauge registered per failover after afterSingletonsInstantiated")
    void shouldRegisterExpiryGaugePerFailoverAfterScan() {
        Failover fo = failover("my-fo", 2, ChronoUnit.HOURS);
        when(scanner.findAllFailover()).thenReturn(List.of(fo));

        binder.bindTo(registry);
        binder.afterSingletonsInstantiated();

        Gauge expiryGauge = registry.get("failover.config.expiry.seconds")
            .tag("name", "my-fo")
            .tag("unit", "HOURS")
            .gauge();
        assertThat(expiryGauge.value()).isEqualTo(7200.0); // 2 hours = 7200 seconds
    }

    @Test
    @DisplayName("expiry.seconds computed correctly for MINUTES unit")
    void shouldConvertMinutesToSeconds() {
        Failover fo = failover("fo-minutes", 30, ChronoUnit.MINUTES);
        when(scanner.findAllFailover()).thenReturn(List.of(fo));

        binder.bindTo(registry);
        binder.afterSingletonsInstantiated();

        double seconds = registry.get("failover.config.expiry.seconds")
            .tag("name", "fo-minutes")
            .tag("unit", "MINUTES")
            .gauge()
            .value();
        assertThat(seconds).isEqualTo(1800.0); // 30 min = 1800 s
    }

    @Test
    @DisplayName("expiry.seconds computed correctly for DAYS unit")
    void shouldConvertDaysToSeconds() {
        Failover fo = failover("fo-days", 1, ChronoUnit.DAYS);
        when(scanner.findAllFailover()).thenReturn(List.of(fo));

        binder.bindTo(registry);
        binder.afterSingletonsInstantiated();

        double seconds = registry.get("failover.config.expiry.seconds")
            .tag("name", "fo-days")
            .tag("unit", "DAYS")
            .gauge()
            .value();
        assertThat(seconds).isEqualTo(86400.0); // 1 day = 86400 s
    }

    @Test
    @DisplayName("no expiry gauges registered when scanner returns empty list")
    void shouldNotRegisterExpiryGaugesWhenEmpty() {
        when(scanner.findAllFailover()).thenReturn(List.of());

        binder.bindTo(registry);
        binder.afterSingletonsInstantiated();

        assertThat(registry.find("failover.config.expiry.seconds").gauges()).isEmpty();
    }

    @Test
    @DisplayName("multiple failovers — separate expiry gauge per name")
    void shouldRegisterSeparateGaugeForEachFailover() {
        Failover fo1 = failover("fo1", 1, ChronoUnit.HOURS);
        Failover fo2 = failover("fo2", 30, ChronoUnit.MINUTES);
        when(scanner.findAllFailover()).thenReturn(List.of(fo1, fo2));

        binder.bindTo(registry);
        binder.afterSingletonsInstantiated();

        assertThat(registry.get("failover.config.expiry.seconds").tag("name", "fo1").gauge().value())
            .isEqualTo(3600.0);
        assertThat(registry.get("failover.config.expiry.seconds").tag("name", "fo2").gauge().value())
            .isEqualTo(1800.0);
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static Failover failover(String name, long duration, ChronoUnit unit) {
        Failover fo = mock(Failover.class);
        when(fo.name()).thenReturn(name);
        when(fo.domain()).thenReturn("");
        when(fo.expiryDuration()).thenReturn(duration);
        when(fo.expiryUnit()).thenReturn(unit);
        when(fo.expiryDurationExpression()).thenReturn("");
        when(fo.expiryUnitExpression()).thenReturn("");
        return fo;
    }
}
