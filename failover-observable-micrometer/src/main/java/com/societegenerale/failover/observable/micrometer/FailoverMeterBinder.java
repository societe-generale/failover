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
import com.societegenerale.failover.core.expiry.FailoverExpiryExtractor;
import com.societegenerale.failover.core.observable.scanner.FailoverScanner;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.springframework.beans.factory.SmartInitializingSingleton;

import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * {@link MeterBinder} that exposes static failover configuration as Micrometer gauges.
 *
 * <h2>Meters registered</h2>
 * <ul>
 *   <li>{@code failover.registered.total} (Gauge) — total number of {@code @Failover} annotations
 *       discovered; sampled lazily so it reflects the live scanner state</li>
 *   <li>{@code failover.config.expiry.seconds} (Gauge) — configured expiry in seconds for each
 *       named failover; tags: {@code name}, {@code unit}</li>
 * </ul>
 *
 * <h2>Lifecycle ordering</h2>
 * The binder implements {@link SmartInitializingSingleton} so per-failover expiry gauges are
 * registered after {@link com.societegenerale.failover.observable.scanner.SpringContextFailoverScanner}
 * has completed its scan. The total-count gauge is a lazy supplier — safe to register immediately
 * in {@link #bindTo} before the scanner has finished.
 *
 * @author Anand Manissery
 */
@Slf4j
public class FailoverMeterBinder implements MeterBinder, SmartInitializingSingleton {

    private final FailoverScanner scanner;
    private final FailoverExpiryExtractor expiryExtractor;
    private final List<MeterRegistry> registries = new ArrayList<>();

    /**
     * Creates a binder with the required scanner and expiry extractor.
     *
     * @param scanner         scanner that provides the list of registered failovers
     * @param expiryExtractor extracts expiry configuration from {@code @Failover} annotations
     */
    public FailoverMeterBinder(FailoverScanner scanner, FailoverExpiryExtractor expiryExtractor) {
        this.scanner = scanner;
        this.expiryExtractor = expiryExtractor;
    }

    /**
     * Registers the lazy total-count gauge and stores the registry for deferred expiry-gauge binding.
     * Called by Spring Boot's {@code MeterRegistryPostProcessor} for each available registry.
     */
    @Override
    public void bindTo(@NonNull MeterRegistry registry) {
        registries.add(registry);
        Gauge.builder("failover.registered.total", scanner, s -> s.findAllFailover().size())
            .description("Number of @Failover-annotated methods registered")
            .register(registry);
    }

    /**
     * Registers per-failover expiry gauges after the scanner has completed its scan.
     * Fires after all singleton beans are instantiated.
     */
    @Override
    public void afterSingletonsInstantiated() {
        List<Failover> all = scanner.findAllFailover();
        log.debug("FailoverMeterBinder binding expiry gauges for {} failover(s).", all.size());
        for (MeterRegistry registry : registries) {
            for (Failover failover : all) {
                double seconds = toSeconds(failover);
                Gauge.builder("failover.config.expiry.seconds", () -> seconds)
                    .description("Configured expiry duration in seconds for this failover")
                    .tag("name", failover.name())
                    .tag("domain", failover.domain().isBlank() ? failover.name() : failover.domain())
                    .tag("unit", expiryExtractor.expiryUnit(failover).name())
                    .register(registry);
            }
        }
    }

    private double toSeconds(Failover failover) {
        long duration = expiryExtractor.expiryDuration(failover);
        ChronoUnit unit = expiryExtractor.expiryUnit(failover);
        return duration * unit.getDuration().toNanos() / 1_000_000_000.0;
    }
}
