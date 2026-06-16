---
icon: material/speedometer
---

# Micro-Benchmarks

Performance-sensitive code on the failover paths is backed by [JMH](https://github.com/openjdk/jmh)
micro-benchmarks. They are **profile-gated** (`-Pbenchmark`) and excluded from the default build:
benchmark classes are named `*Benchmark` (not `*Test`), so Surefire never runs them.

---

## Run

```bash
mvn -pl failover-core -Pbenchmark test-compile exec:exec
```

Benchmark sources live in `src/test/java` named `*Benchmark` (so Surefire never runs them); `jmh-core`
is a permanent test-scoped dependency so they always compile. The `benchmark` profile wires the JMH
annotation processor (alongside Lombok) to generate the benchmark metadata, then runs
`org.openjdk.jmh.Main` over every `*Benchmark` class.

---

## Metric construction (`MetricsBuildBenchmark`)

`AdvancedFailoverHandler` builds a `Metrics` bag on every store/recover (the recover bag has 11
entries). The benchmark compares the previous `String.format`-based key building (plus per-call
`toString`/ternary noise) against the current `Metrics` helper (concatenated keys + typed `collect`
overloads).

| Implementation | ns/op |
|---|---|
| Legacy (`String.format` key + `toString`/ternary) | **744.1 ± 16.8** |
| Helper (concatenated key + typed overloads) | **204.4 ± 2.2** |

≈ **3.6× faster** (−540 ns/op, ~73%), the gain dominated by removing `String.format`. See
[ADR 50](../adr/adr.md#adr-50-metrics-builder-helper-cheaper-metric-construction-on-the-recover-path).

> Absolute numbers vary by hardware/JDK; what matters is the **relative** improvement and that the
> helper has no behavioural change (keys, values, null coercion identical).

---

## Performance Validation

Beyond the micro-benchmark, two concurrency claims are guarded by deterministic tests in the default
build (no profile, no flaky long-runs).

### JDBC cleanup under write load (`FailoverStoreJdbcTest.ConcurrencyScenarios`)

Validates that the expiry-cleanup `DELETE … WHERE EXPIRE_ON < ?` does not deadlock or serialise behind
high write volume. `shouldSustainWritesWhileCleanupRunsConcurrently` runs **12 writer threads × 200
upserts (2 400 writes)** interleaved with **4 threads hammering `cleanByExpiry` (400 runs)** against the
same H2 table. The test asserts no thread surfaced a deadlock/lock-contention exception and that every
non-expired row survived (exact final row count). Backed by the mandatory `EXPIRE_ON` index
([Store — JDBC](../modules/store-jdbc.md#table-ddl)), cleanup stays an indexed range scan rather than a
table-locking full scan (audit I-13).

### Virtual-thread scatter/gather scaling (`SliceDispatcherTest.HighConcurrencyVirtualThreads`)

The scatter/gather executor is an **unbounded virtual-thread executor**
(`SimpleAsyncTaskExecutor` with `setVirtualThreads(true)`), so there is **no thread-pool size to tune** —
each slice gets its own virtual thread. `manyBlockingRecoverSlicesScaleOnVirtualThreads` dispatches
**1 000 recover slices that each block ~10 ms** (modelling a per-slice store round-trip): they complete
**concurrently and in order**, far below the ~10 s serial cost (asserted `< 2.5 s`). A companion test
confirms 1 000 store slices all run without pool exhaustion.

**Sizing guidance:** because virtual threads are cheap and unbounded, the real concurrency limit is the
**downstream resource** — for the JDBC store, the connection pool. Size that for the widest scatter
fan-out, and keep `connection-timeout ≤ failover.scatter.timeout`. See
[Store — JDBC › Connection Pool Tuning](../modules/store-jdbc.md#connection-pool-tuning).
