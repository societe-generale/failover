/*
 * Copyright 2022-2023, Société Générale All rights reserved.
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
import com.societegenerale.failover.core.observable.publisher.ObservablePublisher;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * {@link ObservablePublisher} that emits real Micrometer meters on every store/recover event.
 *
 * <p>Complements {@link com.societegenerale.failover.core.observable.publisher.MdcLoggerObservablePublisher}
 * by publishing actual {@link Counter} and {@link Timer} registrations visible to Prometheus / Grafana.
 *
 * <h2>Meters emitted</h2>
 * <ul>
 *   <li>{@code failover.store.total} (Counter) — tags: {@code name}, {@code stored}</li>
 *   <li>{@code failover.recover.total} (Counter) — tags: {@code name}, {@code recovered},
 *       {@code recovery_failed}</li>
 *   <li>{@code failover.exception.total} (Counter) — tags: {@code name},
 *       {@code exception_type}, {@code cause_type}</li>
 *   <li>{@code failover.operation.duration} (Timer) — tags: {@code name}, {@code action}
 *       — only when the {@code Metrics} bag carries a {@code failover-duration-ns} key</li>
 * </ul>
 *
 * <h2>Tag cardinality</h2>
 * Boolean tags ({@code stored}, {@code recovered}, {@code recovery_failed}) have cardinality 2.
 * {@code exception_type} and {@code cause_type} use class canonical names and are expected to be
 * low-cardinality in practice. Exception messages are intentionally <em>never</em> used as tags.
 *
 * <h2>Event discrimination</h2>
 * Operational events (store/recover) carry a {@code failover-action} key.
 * Startup report events do not, and are silently ignored by this publisher
 * (they are handled by {@link FailoverMeterBinder}).
 *
 * @author Anand Manissery
 */
public class MicrometerObservablePublisher implements ObservablePublisher {

    static final String ACTION_KEY       = "failover-action";
    static final String NAME_KEY         = "failover-name";
    static final String STORED_KEY       = "failover-is-stored";
    static final String RECOVERED_KEY    = "failover-is-recovered";
    static final String RECOVERY_FAIL    = "failover-is-recovery-failed";
    static final String EX_TYPE_KEY      = "failover-exception-type";
    static final String CAUSE_TYPE_KEY   = "failover-exception-cause-type";
    static final String DURATION_NS_KEY  = "failover-duration-ns";

    private final MeterRegistry registry;

    /**
     * Creates a publisher that records meters in the given registry.
     *
     * @param registry the Micrometer registry to publish meters to
     */
    public MicrometerObservablePublisher(MeterRegistry registry) {
        this.registry = registry;
    }

    @Override
    public void publish(Metrics metrics) {
        Map<String, String> info = metrics.getInfo();
        String action = info.get(ACTION_KEY);
        if (action == null) {
            return; // startup/config event — handled by FailoverMeterBinder
        }
        String name = info.getOrDefault(NAME_KEY, "unknown");
        switch (action) {
            case "store"   -> publishStore(name, info);
            case "recover" -> publishRecover(name, info);
            default        -> { /* unknown action — ignore */ }
        }
        recordDuration(name, action, info);
    }

    private void publishStore(String name, Map<String, String> info) {
        String stored = info.getOrDefault(STORED_KEY, "false");
        Counter.builder("failover.store.total")
            .description("Total failover store attempts")
            .tag("name", name)
            .tag("stored", stored)
            .register(registry)
            .increment();
    }

    private void publishRecover(String name, Map<String, String> info) {
        String recovered   = info.getOrDefault(RECOVERED_KEY, "false");
        String recovFailed = info.getOrDefault(RECOVERY_FAIL, "false");
        String exType      = info.getOrDefault(EX_TYPE_KEY, "unknown");
        String causeType   = info.getOrDefault(CAUSE_TYPE_KEY, "");
        if (causeType.isBlank()) causeType = "none";

        Counter.builder("failover.recover.total")
            .description("Total failover recovery attempts")
            .tag("name", name)
            .tag("recovered", recovered)
            .tag("recovery_failed", recovFailed)
            .register(registry)
            .increment();

        Counter.builder("failover.exception.total")
            .description("Root exceptions triggering failover recovery")
            .tag("name", name)
            .tag("exception_type", exType)
            .tag("cause_type", causeType)
            .register(registry)
            .increment();
    }

    private void recordDuration(String name, String action, Map<String, String> info) {
        String nanosStr = info.get(DURATION_NS_KEY);
        if (nanosStr == null) return;
        try {
            long nanos = Long.parseLong(nanosStr);
            Timer.builder("failover.operation.duration")
                .description("Wall time of failover store/recover path")
                .tag("name", name)
                .tag("action", action)
                .register(registry)
                .record(nanos, TimeUnit.NANOSECONDS);
        } catch (NumberFormatException ignored) {
            // malformed value — skip silently
        }
    }
}
