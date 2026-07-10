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
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.MeterRegistry;

import java.util.Comparator;
import java.util.List;

/**
 * Rebuilds the {@code @Failover} configuration view from the {@code failover.config.expiry.seconds} and
 * {@code failover.config.global} gauges that {@code FailoverMeterBinder} registers. Reads only — it never
 * registers a new meter, so it is a pure consumer of signals the framework already publishes, with no
 * dependency on {@code FailoverScanner}.
 *
 * <p>Used both by the dashboard (local mode) and by peer apps (to build {@link ClusterSnapshot}s for the
 * shared-store cluster tier) — the same split responsibility as {@link FailoverMetricsSnapshotService}.
 *
 * @author Anand Manissery
 */
public class FailoverConfigSnapshotService {

    private static final String CONFIG_EXPIRY = "failover.config.expiry.seconds";
    private static final String CONFIG_GLOBAL = "failover.config.global";
    private static final String DEFAULT = "default";

    // Framework defaults, used only when no failover.config.global gauge has been emitted
    // (an older peer, or no @Failover-annotated service present at all).
    private static final String DEFAULT_STORE_TYPE = "inmemory";
    private static final String DEFAULT_EXECUTION_TYPE = "basic";
    private static final String DEFAULT_EXCEPTION_POLICY = "rethrow";
    private static final boolean DEFAULT_ASYNC_STORE = true;

    private final MeterRegistry registry;

    /**
     * Creates a new snapshot service reading from {@code registry}.
     *
     * @param registry the meter registry to read {@code failover.config.*} gauges from
     */
    public FailoverConfigSnapshotService(MeterRegistry registry) {
        this.registry = registry;
    }

    /**
     * Rebuilds the configuration view from the emitted gauges.
     *
     * @return one {@link ConfigEntry} per {@code @Failover} discovered via emitted gauges, sorted by name;
     *         never {@code null}.
     */
    public List<ConfigEntry> configEntries() {
        GlobalTags global = globalTags();
        return registry.find(CONFIG_EXPIRY).gauges().stream()
                .map(gauge -> toEntry(gauge.getId(), global))
                .sorted(Comparator.comparing(ConfigEntry::name))
                .toList();
    }

    private ConfigEntry toEntry(Meter.Id id, GlobalTags global) {
        return new ConfigEntry(
                tag(id, "name", ""),
                tag(id, "domain", ""),
                Long.parseLong(tag(id, "duration", "0")),
                tag(id, "unit", ""),
                Boolean.parseBoolean(tag(id, "recoverAll", "false")),
                orDefault(tag(id, "payloadSplitter", "")),
                orDefault(tag(id, "keyGenerator", "")),
                orDefault(tag(id, "expiryPolicy", "")),
                global.storeType(), global.executionType(), global.exceptionPolicy(), global.asyncStore());
    }

    private GlobalTags globalTags() {
        Gauge g = registry.find(CONFIG_GLOBAL).gauge();
        if (g == null) {
            return new GlobalTags(DEFAULT_STORE_TYPE, DEFAULT_EXECUTION_TYPE, DEFAULT_EXCEPTION_POLICY, DEFAULT_ASYNC_STORE);
        }
        Meter.Id id = g.getId();
        return new GlobalTags(
                tag(id, "storeType", DEFAULT_STORE_TYPE),
                tag(id, "executionType", DEFAULT_EXECUTION_TYPE),
                tag(id, "exceptionPolicy", DEFAULT_EXCEPTION_POLICY),
                Boolean.parseBoolean(tag(id, "asyncStore", String.valueOf(DEFAULT_ASYNC_STORE))));
    }

    private static String tag(Meter.Id id, String key, String fallback) {
        String value = id.getTag(key);
        return value == null ? fallback : value;
    }

    /** Empty per-annotation overrides render as {@code "default"} to signal "framework default". */
    private static String orDefault(String value) {
        return (value == null || value.isBlank()) ? DEFAULT : value;
    }

    private record GlobalTags(String storeType, String executionType, String exceptionPolicy, boolean asyncStore) {
    }
}
