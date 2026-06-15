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

package com.societegenerale.failover.core.observable;

import org.openjdk.jmh.annotations.Benchmark;
import org.openjdk.jmh.annotations.BenchmarkMode;
import org.openjdk.jmh.annotations.Fork;
import org.openjdk.jmh.annotations.Measurement;
import org.openjdk.jmh.annotations.Mode;
import org.openjdk.jmh.annotations.OutputTimeUnit;
import org.openjdk.jmh.annotations.Scope;
import org.openjdk.jmh.annotations.State;
import org.openjdk.jmh.annotations.Warmup;
import org.openjdk.jmh.infra.Blackhole;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.TimeUnit;

/**
 * Micro-benchmark backing audit A-3/Q-2: builds the full recover-path metric bag (the heaviest of
 * the two paths, 11 entries) and compares the previous implementation — {@code String.format} key
 * building plus per-call {@code Long.toString}/{@code Boolean.toString}/ternary noise — against the
 * current {@link Metrics} helper (plain key concatenation plus typed {@code collect} overloads).
 *
 * <p>Not a unit test (named {@code *Benchmark} so Surefire skips it). Run via the {@code benchmark}
 * profile:
 *
 * <pre>{@code mvn -pl failover-core -Pbenchmark test-compile exec:exec}</pre>
 *
 * @author Anand Manissery
 */
@State(Scope.Thread)
@BenchmarkMode(Mode.AverageTime)
@OutputTimeUnit(TimeUnit.NANOSECONDS)
@Warmup(iterations = 3, time = 1)
@Measurement(iterations = 5, time = 1)
@Fork(1)
public class MetricsBuildBenchmark {

    private static final String NAME = "third-parties-failover";
    private static final long EXPIRY_DURATION = 30L;
    private static final String EXPIRY_UNIT = "MINUTES";

    private final Throwable cause =
            new IllegalStateException("upstream unavailable", new java.net.ConnectException("connection refused"));

    /** Previous style: {@code String.format} key + {@code Long/Boolean.toString} + null→"" ternaries. */
    @Benchmark
    public Map<String, String> legacyRecoverMetrics(Blackhole bh) {
        long startNanos = System.nanoTime();
        Map<String, String> info = new LinkedHashMap<>();
        info.put("%s-%s".formatted("failover", "name"), NAME);
        info.put("%s-%s".formatted("failover", "action"), "recover");
        info.put("%s-%s".formatted("failover", "expiry-duration"), Long.toString(EXPIRY_DURATION));
        info.put("%s-%s".formatted("failover", "expiry-unit"), EXPIRY_UNIT);
        info.put("%s-%s".formatted("failover", "exception-type"), cause.getClass().getCanonicalName());
        info.put("%s-%s".formatted("failover", "exception-cause-type"),
                cause.getCause() != null ? cause.getCause().getClass().getCanonicalName() : "");
        info.put("%s-%s".formatted("failover", "exception-message"),
                cause.getMessage() != null ? cause.getMessage() : "");
        info.put("%s-%s".formatted("failover", "exception-cause-message"),
                cause.getCause() != null && cause.getCause().getMessage() != null ? cause.getCause().getMessage() : "");
        info.put("%s-%s".formatted("failover", "is-recovered"), Boolean.toString(false));
        info.put("%s-%s".formatted("failover", "is-recovery-failed"), Boolean.toString(true));
        info.put("%s-%s".formatted("failover", "duration-ns"), Long.toString(System.nanoTime() - startNanos));
        bh.consume(info);
        return info;
    }

    /** Current style: {@link Metrics} helper — concatenated key + typed {@code collect} overloads. */
    @Benchmark
    public Map<String, String> optimizedRecoverMetrics(Blackhole bh) {
        long startNanos = System.nanoTime();
        Throwable rootCause = cause.getCause();
        Metrics metrics = Metrics.of(NAME)
                .collect("action", "recover")
                .collect("expiry-duration", EXPIRY_DURATION)
                .collect("expiry-unit", EXPIRY_UNIT)
                .collect("exception-type", cause.getClass().getCanonicalName())
                .collect("exception-cause-type", rootCause == null ? null : rootCause.getClass().getCanonicalName())
                .collect("exception-message", cause.getMessage())
                .collect("exception-cause-message", rootCause == null ? null : rootCause.getMessage())
                .collect("is-recovered", false)
                .collect("is-recovery-failed", true)
                .collect("duration-ns", System.nanoTime() - startNanos);
        bh.consume(metrics);
        return metrics.getInfo();
    }
}
