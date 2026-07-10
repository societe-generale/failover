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
import com.societegenerale.failover.core.scanner.FailoverScanner;
import com.societegenerale.failover.core.store.FailoverStoreSizeAware;
import com.societegenerale.failover.scanner.SpringContextFailoverScanner;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.binder.MeterBinder;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.NonNull;
import org.jspecify.annotations.Nullable;
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
 *       named failover; tags: {@code name}, {@code domain}, {@code unit}, {@code duration},
 *       {@code recoverAll}, {@code payloadSplitter}, {@code keyGenerator}, {@code expiryPolicy}.
 *       Read back by {@code FailoverConfigSnapshotService} to rebuild the dashboard's configuration
 *       view without depending on {@code FailoverScanner}</li>
 *   <li>{@code failover.config.global} (Gauge, value {@code 1}) — process-wide framework settings that
 *       apply to every failover; tags: {@code storeType}, {@code executionType}, {@code exceptionPolicy},
 *       {@code asyncStore}. Registered only when a {@link GlobalConfig} is supplied</li>
 *   <li>{@code failover.live.entries} (Gauge) — current stored entry count per failover (cache footprint);
 *       tags: {@code name}, {@code domain}. Registered only when the assembled store is
 *       {@link FailoverStoreSizeAware} and supports counting: always for in-memory / Caffeine, and for JDBC
 *       only when {@code failover.store.jdbc.live-entries-gauge-enabled=true} (opt-in COUNT(*) per scrape,
 *       audit A7). Not available in multi-tenant mode</li>
 * </ul>
 *
 * <h2>Lifecycle ordering</h2>
 * The binder implements {@link SmartInitializingSingleton} so per-failover expiry gauges are
 * registered after {@link SpringContextFailoverScanner}
 * has completed its scan. The total-count gauge is a lazy supplier — safe to register immediately
 * in {@link #bindTo} before the scanner has finished.
 *
 * @author Anand Manissery
 */
@Slf4j
public class FailoverMeterBinder implements MeterBinder, SmartInitializingSingleton {

    private final FailoverScanner scanner;
    private final FailoverExpiryExtractor expiryExtractor;
    private final @Nullable FailoverStoreSizeAware sizeAwareStore;
    private final @Nullable GlobalConfig globalConfig;
    private final List<MeterRegistry> registries = new ArrayList<>();

    /**
     * Process-wide framework settings echoed on the {@code failover.config.global} gauge — the same
     * values {@code DashboardConfigService} used to read from the {@code Environment} directly, now
     * emitted so a remote dashboard can read them too.
     *
     * @param storeType       {@code failover.store.type} (e.g. {@code inmemory}, {@code jdbc})
     * @param executionType   {@code failover.type} (e.g. {@code basic}, {@code resilience})
     * @param exceptionPolicy {@code failover.exception-policy} (e.g. {@code rethrow})
     * @param asyncStore      {@code failover.store.async}
     */
    public record GlobalConfig(String storeType, String executionType, String exceptionPolicy, boolean asyncStore) {
    }

    /**
     * Creates a binder with the required scanner and expiry extractor (no live-entries gauge, no global-config gauge).
     *
     * @param scanner         scanner that provides the list of registered failovers
     * @param expiryExtractor extracts expiry configuration from {@code @Failover} annotations
     */
    public FailoverMeterBinder(FailoverScanner scanner, FailoverExpiryExtractor expiryExtractor) {
        this(scanner, expiryExtractor, null);
    }

    /**
     * Creates a binder that additionally exposes the {@code failover.live.entries} gauge when the assembled
     * store can report its size.
     *
     * @param scanner         scanner that provides the list of registered failovers
     * @param expiryExtractor extracts expiry configuration from {@code @Failover} annotations
     * @param store           the assembled failover store; the live-entries gauge is registered only when it is
     *                        a {@link FailoverStoreSizeAware} that {@link FailoverStoreSizeAware#liveEntryCountSupported() supports} counting
     */
    public FailoverMeterBinder(FailoverScanner scanner, FailoverExpiryExtractor expiryExtractor, @Nullable Object store) {
        this(scanner, expiryExtractor, store, null);
    }

    /**
     * Creates a binder that additionally exposes the {@code failover.config.global} gauge.
     *
     * @param scanner         scanner that provides the list of registered failovers
     * @param expiryExtractor extracts expiry configuration from {@code @Failover} annotations
     * @param store           the assembled failover store; the live-entries gauge is registered only when it is
     *                        a {@link FailoverStoreSizeAware} that {@link FailoverStoreSizeAware#liveEntryCountSupported() supports} counting
     * @param globalConfig    process-wide framework settings; the {@code failover.config.global} gauge is
     *                        registered only when this is non-{@code null}
     */
    public FailoverMeterBinder(FailoverScanner scanner, FailoverExpiryExtractor expiryExtractor,
                                @Nullable Object store, @Nullable GlobalConfig globalConfig) {
        this.scanner = scanner;
        this.expiryExtractor = expiryExtractor;
        this.sizeAwareStore = (store instanceof FailoverStoreSizeAware sa && sa.liveEntryCountSupported()) ? sa : null;
        this.globalConfig = globalConfig;
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
        if (globalConfig != null) {
            Gauge.builder("failover.config.global", () -> 1)
                .description("Process-wide failover framework settings (tags only)")
                .tag("storeType", globalConfig.storeType())
                .tag("executionType", globalConfig.executionType())
                .tag("exceptionPolicy", globalConfig.exceptionPolicy())
                .tag("asyncStore", String.valueOf(globalConfig.asyncStore()))
                .register(registry);
        }
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
                String domain = failover.domain().isBlank() ? failover.name() : failover.domain();
                Gauge.builder("failover.config.expiry.seconds", () -> seconds)
                    .description("Configured expiry duration in seconds for this failover")
                    .tag("name", failover.name())
                    .tag("domain", domain)
                    .tag("unit", expiryExtractor.expiryUnit(failover).name())
                    .tag("duration", String.valueOf(expiryExtractor.expiryDuration(failover)))
                    .tag("recoverAll", String.valueOf(failover.recoverAll()))
                    .tag("payloadSplitter", failover.payloadSplitter())
                    .tag("keyGenerator", failover.keyGenerator())
                    .tag("expiryPolicy", failover.expiryPolicy())
                    .register(registry);
                if (sizeAwareStore != null) {
                    // The store keys entries under the effective (domain) name; count by that.
                    Gauge.builder("failover.live.entries", sizeAwareStore, s -> s.liveEntryCount(domain))
                        .description("Current number of stored failover entries (cache footprint)")
                        .tag("name", failover.name())
                        .tag("domain", domain)
                        .register(registry);
                }
            }
        }
    }

    private double toSeconds(Failover failover) {
        long duration = expiryExtractor.expiryDuration(failover);
        ChronoUnit unit = expiryExtractor.expiryUnit(failover);
        return duration * unit.getDuration().toNanos() / 1_000_000_000.0;
    }
}
