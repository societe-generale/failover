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

The `benchmark` profile adds the JMH dependencies, registers `src/benchmark/java` as a source root,
wires the JMH annotation processor (alongside Lombok), and runs `org.openjdk.jmh.Main` over every
`*Benchmark` class.

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
