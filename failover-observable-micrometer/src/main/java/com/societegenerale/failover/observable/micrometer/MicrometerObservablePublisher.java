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
 *   <li>{@code failover.recovery.outcome.total} (Counter) — tags: {@code name}, {@code domain},
 *       {@code method}, {@code outcome} ({@code recovered} | {@code not_recovered} | {@code error}).
 *       Per intercepted method; the source for the failover / recovery / non-recovery rates.</li>
 *   <li>{@code failover.recovery.partial.total} (Counter) — tags: {@code name}, {@code method}.
 *       A scatter/gather recover where some (not all) slices were recovered.</li>
 *   <li>{@code failover.exception.total} (Counter) — tags: {@code name},
 *       {@code exception_type}, {@code cause_type}</li>
 *   <li>{@code failover.operation.duration} (Timer) — tags: {@code name}, {@code action}
 *       — only when the {@code Metrics} bag carries a {@code failover-duration-ns} key</li>
 *   <li>{@code failover.store.async.failed} (Counter) — tags: {@code name}, {@code operation},
 *       {@code exception_type} — emitted when an async store/delete/cleanByExpiry fails inside
 *       the executor thread (the async store layer is otherwise visible only in logs)</li>
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
    static final String DOMAIN_KEY       = "failover-domain";
    static final String METHOD_KEY       = "failover-method";
    static final String STORED_KEY       = "failover-is-stored";
    static final String RECOVERED_KEY    = "failover-is-recovered";
    static final String RECOVERY_FAIL    = "failover-is-recovery-failed";
    static final String EX_TYPE_KEY      = "failover-exception-type";
    static final String CAUSE_TYPE_KEY   = "failover-exception-cause-type";
    static final String DURATION_NS_KEY  = "failover-duration-ns";
    static final String ASYNC_OP_KEY     = "failover-async-operation";
    static final String ASYNC_FAILED     = "store-async-failed";

    /** Recover outcome tag values for {@code failover.recovery.outcome.total}. */
    static final String OUTCOME_RECOVERED     = "recovered";
    static final String OUTCOME_NOT_RECOVERED = "not_recovered";
    static final String OUTCOME_ERROR         = "error";
    /** Fallback tag value when a tag is absent from the metrics bag. */
    static final String UNKNOWN              = "unknown";

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
            case "recover-partial" -> publishRecoverPartial(name, info);
            case ASYNC_FAILED -> publishAsyncFailed(name, info);
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

        publishRecoveryOutcome(name, info, recovered, recovFailed);
    }

    /**
     * Single per-method outcome counter from which the three operational rates are derived:
     * <ul>
     *   <li><b>failover rate</b> — total intercepted upstream failures = sum over all outcomes;</li>
     *   <li><b>recovery rate</b> — {@code outcome=recovered} (a stored value was returned within expiry);</li>
     *   <li><b>non-recovery rate</b> — {@code outcome=not_recovered} (no stored value: not found or expired)
     *       — the user-impact signal worth alerting on.</li>
     * </ul>
     * A recover-path failure ({@code recovery_failed=true}) is reported as a distinct
     * {@code outcome=error} so a store/serialization fault is never miscounted as a clean miss.
     */
    private void publishRecoveryOutcome(String name, Map<String, String> info, String recovered, String recovFailed) {
        String outcome = "true".equals(recovFailed) ? OUTCOME_ERROR
                : "true".equals(recovered) ? OUTCOME_RECOVERED
                : OUTCOME_NOT_RECOVERED;
        Counter.builder("failover.recovery.outcome.total")
            .description("Failover recover outcomes per intercepted method: recovered / not_recovered / error")
            .tag("name", name)
            .tag("domain", info.getOrDefault(DOMAIN_KEY, name))
            .tag("method", info.getOrDefault(METHOD_KEY, UNKNOWN))
            .tag("outcome", outcome)
            .register(registry)
            .increment();
    }

    /**
     * Per-method partial-recovery counter (audit I-04): a scatter/gather recover where some — but not
     * all — slices were recovered, so the merged collection may be incomplete. Alert on a non-zero rate.
     */
    private void publishRecoverPartial(String name, Map<String, String> info) {
        Counter.builder("failover.recovery.partial.total")
            .description("Scatter/gather recoveries where some (not all) slices were recovered")
            .tag("name", name)
            .tag("method", info.getOrDefault(METHOD_KEY, UNKNOWN))
            .register(registry)
            .increment();
    }

    private void publishAsyncFailed(String name, Map<String, String> info) {
        String operation = info.getOrDefault(ASYNC_OP_KEY, "unknown");
        String exType    = info.getOrDefault(EX_TYPE_KEY, "unknown");
        Counter.builder("failover.store.async.failed")
            .description("Async store-layer operations that failed inside the executor")
            .tag("name", name)
            .tag("operation", operation)
            .tag("exception_type", exType)
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
