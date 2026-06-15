# Failover — Code Audit & Review Report

**Project:** `failover` — Spring Boot library for transparent referential failover (store-and-replay)
**Audit date:** 2026-06-15
**Branch reviewed:** `phase-4-improvrments`
**Reviewer:** Automated code audit (Claude)
**Scope:** Design / architecture · code quality · documentation · testability

> Method note: this audit reads the live source tree. The repository's knowledge graph
> (`graphify-out/`) was generated on 2026-06-08 and predates recent commits (e.g.
> `audit-driven hardening`, the per-backend package split). Where the graph lists historical
> review findings (`FINDING-03…13`), those were **verified against current code** and found
> already remediated. Conclusions below reflect the code as it stands today, not the graph.

---

## 1. Executive Summary

`failover` is a **mature, well-architected, production-grade** Spring Boot library. It exhibits
the discipline of a published OSS component: Apache-2.0 headers on every file, exhaustive Javadoc,
null-safety annotations, a 16-module reactor with clean layering, and an unusually strong test
and quality-gate posture (88 unit + 9 integration tests, 95% mutation threshold, ArchUnit rules,
concurrency tests against real H2).

The codebase is in a **release-ready** state. The improvement areas identified are refinements and
hardening of already-good practices — not defects blocking use. There is **one documentation bug**
worth fixing promptly (a broken entry-point pointer in `CLAUDE.md`).

### Scorecard

| Dimension | Grade | Summary |
|---|:---:|---|
| Design / Architecture | **A** | Clean decorator chain, SPI everywhere, zero import cycles, strong module boundaries |
| Code Quality | **A−** | Modern Java 21, documented edge cases, 0 TODO/`System.out`; a few large classes & micro-perf spots |
| Documentation | **A−** | ADRs, MkDocs site, per-package `package-info`; one broken pointer + a generated-metadata gap |
| Testability | **A** | High test:code ratio, mutation + arch + concurrency testing; ITs concentrated in one module by design |

**Overall: A− (excellent).**

---

## 2. Project at a Glance

| Metric | Value |
|---|---|
| Reactor modules | 16 + aggregate `report` |
| Main source | ~9,500 LOC Java |
| Test source | ~18,300 LOC Java (**~1.9 : 1** test-to-main) |
| Unit tests (`*Test.java`) | 88 |
| Integration tests (`*IT.java`) | 9 (real H2) |
| Auto-configuration classes | 22 |
| Java / Spring Boot | **21 / 4.0.6** |
| Null-safety | JSpecify 1.0.0 |
| Mutation testing | PIT, **threshold 95%** |
| Architecture tests | ArchUnit 1.3.0 |
| `TODO`/`FIXME` in main | **0** |
| `System.out` / `printStackTrace` in main | **0** |
| Import cycles | **0** (per graph analysis) |

---

## 3. Design & Architecture

### 3.1 What is good

- **Decorator handler chain is textbook.** Responsibilities are cleanly separated and each layer
  is independently testable:
  ```
  AdvancedFailoverHandler        ← metrics, RecoveredPayloadHandler, exception policy
    └── ScatterGatherFailoverHandler   ← split composite result into per-entity slices
          └── DefaultFailoverHandler     ← core store/recover, expiry check
  ```
- **Store assembly is composable** via decorators: `AsyncFailoverStore → MultiTenantFailoverStore → {InMemory | Caffeine | JDBC | custom}`.
- **SPI-first extensibility.** Every core bean is `@ConditionalOnMissingBean`; consumers swap
  `KeyGenerator`, `ExpiryPolicy`, `PayloadSplitter`, `RecoveredPayloadHandler`, `ContextPropagator`,
  `DatabaseResolver`, `TenantResolver`, etc. by declaring a bean. This is the strongest design trait.
- **Decisions are recorded.** 27+ ADRs document non-obvious choices (Instant-over-LocalDateTime for
  timezone-aware expiry, defensive-copy contract, native merge/upsert dialect detection, outermost
  multi-tenant routing, parallel scatter executor injection).
- **Clean module boundaries.** Per-backend stores are isolated modules; `failover-domain` holds only
  the annotation contract; resilience and observability are opt-in modules. No cyclic dependencies.
- **Modern concurrency.** Virtual-thread executor for async writes and parallel scatter; per-slice
  timeout handling that degrades a timed-out recover slice to "not recovered" rather than hanging the
  business thread (`ScatterGatherFailoverHandler#zip`).

### 3.2 What to improve

| # | Area | Observation | Severity |
|---|---|---|:---:|
| A1 | **Stale auto-config registration** ✅ **RESOLVED (2026-06-15)** | `META-INF/spring.factories` carried an `EnableAutoConfiguration=` block that (a) Spring Boot 4 **ignored** in favour of `AutoConfiguration.imports`, and (b) referenced **two classes that no longer exist** — `FailoverCaffeineStoreAutoConfiguration` and `FailoverJdbcStoreAutoConfiguration` (removed in the per-backend package split). **Fix:** dead block deleted; valid `FailureAnalyzer=` registration retained. Stale Javadoc in `FailoverAutoConfiguration` naming the same deleted classes also corrected. Live registration via `AutoConfiguration.imports` unaffected. | ~~Medium~~ |
| A5 | **Micrometer (metrics) autoconfig ordering** ✅ **RESOLVED (2026-06-15)** | `FailoverMicrometerAutoConfiguration` was ordered only `@AutoConfiguration(after = FailoverAutoConfiguration.class)`. The `MeterRegistry` it gates on (`@ConditionalOnBean(MeterRegistry.class)`) is contributed by Spring Boot's **own** metrics autoconfigurations; with no ordering relative to them, the condition was evaluated **before** the registry existed, so the bean silently backed off and the app fell back to `MdcLoggerObservablePublisher` only — **no Micrometer metrics in production**. The unit tests masked it by injecting `MeterRegistry` as a *user bean* (always visible before conditions). **Fix:** added `afterName` ordering against the Boot metrics autoconfigurations (`MetricsAutoConfiguration`, `CompositeMeterRegistryAutoConfiguration`, `SimpleMetricsExportAutoConfiguration`) — by name, so no compile-time dependency is introduced. Added a regression test (`WhenMeterRegistryAutoConfigured`) that lets Boot **auto-configure** the registry; verified it **fails** without the fix and **passes** with it. | ~~High~~ |
| A6 | **Micrometer tracing autoconfig ordering** ✅ **RESOLVED (2026-06-15)** | Same class of bug found by auditing all `@ConditionalOnBean` sites: `MicrometerTracingAutoConfiguration` gates `MicrometerContextPropagator` on `@ConditionalOnBean(Tracer.class)` but was ordered only after `FailoverAutoConfiguration`. The `Tracer` is contributed by Spring Boot's Brave/OpenTelemetry tracing autoconfigurations — so on the same ordering race, scatter/gather slices silently **lose span propagation**. **Fix:** added `afterName` ordering against the Boot tracing autoconfigurations (`MicrometerTracingAutoConfiguration`, `brave…BraveAutoConfiguration`, `otel…OpenTelemetryTracingAutoConfiguration`). Added an ordering-guard test (only the micrometer-tracing API is on the test classpath, so a behavioural test would need Brave/OTel deps). *(Note: `FailoverStoreAutoConfiguration`'s `@ConditionalOnBean(TenantStoreFactory)` was checked and is **correctly** ordered after its contributor — no fix needed.)* | ~~High~~ |
| A2 | `ScatterGatherFailoverHandler` size ✅ **RESOLVED (2026-06-15)** | Split into a ~120-line facade plus four package-private collaborators — `PayloadScatter`, `PayloadGather`, `SliceDispatcher`, `SplitterInvoker` (ADR 49). Public API (3 constructors) and behaviour unchanged; all log messages preserved. Regression-covered by the existing `ScatterGatherFailoverHandlerTest`; PIT holds at 96%/99%. | ~~Low~~ |
| A3 | Metrics construction on hot path ✅ **RESOLVED (2026-06-15)** | `Metrics.collect` now builds keys by concatenation instead of `String.format`; `AdvancedFailoverHandler` uses typed `collect` overloads. JMH (`MetricsBuildBenchmark`) shows the recover bag build dropping **744 → 204 ns/op (~3.6×)**. See A-3/Q-2 fix and ADR 50. | ~~Info~~ |
| A4 | JDBC insert→update race ✅ **RESOLVED (2026-06-15)** | `FailoverStoreJdbc#insertOrUpdate` now applies a **single bounded retry**: when a concurrent expiry delete drops the row between the failed INSERT and the UPDATE (UPDATE → 0 rows), the loop re-INSERTs the now-absent row. Bounded to `MAX_INSERT_OR_UPDATE_ATTEMPTS` (2); on repeated loss the write is abandoned at `warn` (regenerable cache). Two unit tests added. | ~~Info~~ |

---

## 4. Code Quality

### 4.1 What is good

- **Uniform hygiene.** Apache-2.0 header on every file; consistent Lombok usage
  (`@AllArgsConstructor`, `@Slf4j`); zero `TODO`/`FIXME`, zero `System.out`/`printStackTrace` in main.
- **Null-safety is real, not decorative.** JSpecify `@Nullable`/`@NonNull` on the handler and
  splitter APIs, and the nullness contract is honoured in the logic.
- **Edge cases are reasoned about in-code.** Standout examples:
  - `FailoverAspect#returnResult` deliberately **re-throws `Error`** (OOM/StackOverflow) so recovery
    never runs on a failing JVM — only `Throwable` is wrapped.
  - `DefaultKeyGenerator` detects types that override `toString()` and **warns** when an identity
    hash would be used (unstable across JVM restarts with a persistent store) — a genuine correctness
    trap, surfaced rather than hidden (prior `FINDING-07` fully remediated).
  - `AdvancedFailoverHandler#recover` swallows recovery-path exceptions **intentionally and loudly**
    (logged), delegating the null to `RecoveredPayloadHandler` (prior `FINDING-04` remediated).
  - `FailoverStoreJdbc` degrades from native merge to INSERT/UPDATE on `BadSqlGrammarException`,
    flips an `AtomicBoolean` once, and documents the race window.
- **Defensive-copy contract** on `FailoverStore.find` is documented and respected — callers mutate
  `upToDate`/`asOf` without corrupting stored data.

### 4.2 What to improve

| # | Area | Observation | Severity |
|---|---|---|:---:|
| Q1 | Naming | `DefaultKeyGenerator.NUMBER_TYPES` holds `Number`, `String`, `Boolean` — the name under-describes its contents (rename to e.g. `SCALAR_TYPES`). | Trivial |
| Q2 | Metric string-building ✅ **RESOLVED (2026-06-15)** | Typed `Metrics.collect(String, long)` / `(String, boolean)` overloads plus `collect`'s existing null→`""` coercion remove the repeated `Long.toString`/`Boolean.toString`/ternary noise in `AdvancedFailoverHandler`; nested-cause fields use two small null-safe helpers. See ADR 50. | ~~Low~~ |
| Q3 | `@SuppressWarnings` | 2 occurrences in main, both `"unchecked"` on generic casts (`CastingUtils`, `RethrowIfNoRecoveryMethodExceptionPolicy`) — **legitimate**; optional clarifying comment only. | Trivial |
| Q4 | Logging volume ✅ **RESOLVED (2026-06-15)** | `DefaultFailoverHandler` store/recover lifecycle events stay at `INFO` (name only); the full `ReferentialPayload` `toString` body moved to `DEBUG`. No full-payload serialization on the `INFO` path. | ~~Low~~ |

---

## 5. Documentation

### 5.1 What is good

- **Layered, navigable docs**: MkDocs Material site with getting-started, concepts, how-to guides
  (custom key generator, expiry, payload enricher, splitter, exception policy, multi-tenant,
  observability), configuration reference, modules, ADRs, and a changelog.
- **`package-info.java` in every package** — rare and valuable; it makes the Javadoc tree coherent.
- **Method- and class-level Javadoc is thorough**, including parameter semantics, null behaviour, and
  cross-references (`{@link …}`).
- Top-level docs (`README`, `CONTRIBUTING`, `SECURITY`, `LICENSE`) and a quality section documenting
  mutation, architecture, integration, and concurrency testing.

### 5.2 What to improve

| # | Area | Observation | Severity |
|---|---|---|:---:|
| **D1** | **Broken entry pointer** ✅ **RESOLVED (2026-06-15)** | `CLAUDE.md` instructed: *"Use `graphify-out/wiki/index.md` as your entry point"* — that directory never existed. **Fix:** repointed to `graphify-out/GRAPH_REPORT.md`, the real graph artifact present in the tree. | ~~Medium~~ |
| D2 | Config metadata | No `additional-spring-configuration-metadata.json`; only the generated `spring-configuration-metadata.json` exists. Hand-authored metadata would give IDE autocomplete + descriptions for custom properties. | Low |
| D3 | Stale knowledge graph | `graphify-out/` is dated 2026-06-08 and predates recent commits; its `FINDING-*` nodes describe already-fixed issues. Regenerate to avoid future readers acting on stale findings. | Low |

---

## 6. Testability

### 6.1 What is good

- **High coverage breadth and depth**: ~1.9:1 test-to-main LOC, 88 unit tests, 9 real-H2 integration
  tests, **PIT mutation testing at a 95% threshold** (very demanding), and **ArchUnit** rules guarding
  layering.
- **Determinism by design**: integration tests run with `failover.store.async=false` so writes are
  synchronous and assertions are stable.
- **Concurrency is tested**, not assumed (dedicated concurrency scenarios for the JDBC/multi-tenant
  stores).
- **Clear conventions** (`*Test.java` = Surefire/Mockito, `*IT.java` = Failsafe/Spring) and AssertJ
  throughout, matching the project's stated standards.

### 6.2 What to improve

| # | Area | Observation | Severity |
|---|---|---|:---:|
| T1 | No coverage gate | PIT enforces a mutation threshold, but **JaCoCo has no `check`/`<minimum>` rule** — line/branch coverage is reported (for Sonar) but not gated in the build. Add a JaCoCo `check` execution to prevent regressions. | Low |
| T2 | IT concentration | All integration tests live in `failover-spring-boot-autoconfigure` by design. It centralises wiring tests but makes that module heavy and couples backend IT failures to one place. | Info |
| T3 | Cross-DB coverage ✅ **RESOLVED (2026-06-15)** | Testcontainers dialect ITs added for PostgreSQL, MySQL and MariaDB (`*DialectIT` over `AbstractDialectIT`): real engine, store/merge/find/clean round-trip, native-merge fragment asserted. Oracle still resolver-unit-tested only. | ~~Low~~ |
| T4 | `@ConditionalOnBean` tests masked a real bug (see **A5**) | Autoconfig tests that supply the gated bean as a **user bean** (`.withBean(MeterRegistry…)`) always satisfy `@ConditionalOnBean` regardless of ordering, so they hid the production ordering failure. **Lesson applied:** for any `@ConditionalOnBean` on a bean contributed by *another* autoconfiguration, add a test that registers the **real** contributing autoconfigurations via `AutoConfigurations.of(...)` (no hand-injected bean). A `grep` for other `@ConditionalOnBean` usages on framework-contributed types is worthwhile. | Low |

---

## 7. Improvement Plan (Phased)

Phased so each phase is independently shippable and ordered by value-to-effort.

### Phase 1 — Documentation & build hygiene (low risk, fast)
**Goal:** remove the one real doc bug and close cheap gaps.
1. ✅ **D1 DONE** — `CLAUDE.md` entry-point pointer repointed to `graphify-out/GRAPH_REPORT.md`.
2. **D3** Regenerate the knowledge graph (`/graphify`) so `FINDING-*` nodes reflect current code.
3. ✅ **A1 DONE** — dead `EnableAutoConfiguration=` block deleted from `META-INF/spring.factories`
   (`FailureAnalyzer=` retained); stale Javadoc in `FailoverAutoConfiguration` corrected.
4. **Q1/Q3** Rename `NUMBER_TYPES → SCALAR_TYPES`. (`@SuppressWarnings` audited: 2 in main, both
   `"unchecked"` on generic casts in `CastingUtils` and `RethrowIfNoRecoveryMethodExceptionPolicy` —
   legitimate; no action beyond an optional clarifying comment.)

*Exit:* docs accurate, no dead config, naming clarified. No behavioural change.

### Phase 2 — Test gates (low risk, prevents regression)
**Goal:** lock in the already-high quality.
1. **T1** Add a JaCoCo `check` execution with line/branch minimums (start at current measured levels,
   e.g. 90%/80%, fail the build below).
2. **D2** Author `additional-spring-configuration-metadata.json` for custom `failover.*` properties
   (descriptions, defaults, enum hints) to enrich IDE support.

*Exit:* coverage regressions fail CI; consumers get autocomplete for all properties.

### Phase 3 — Robustness hardening (medium effort, additive) ✅ **DONE (2026-06-15)**
**Goal:** strengthen the data-layer and observability edges.
1. ✅ **T3 DONE** — Testcontainers dialect ITs exist for PostgreSQL, MySQL and MariaDB
   (`*DialectIT` over `AbstractDialectIT`): each spins up a real engine and runs the
   store/merge/find/clean round-trip, asserting the native merge fragment is selected (not the
   fallback). Named `*DialectIT` so the default build excludes them (run under the dialect profile;
   require Docker).
2. ✅ **A4 DONE** — chose **single bounded retry** over document-as-accepted. `insertOrUpdate` now
   loops up to `MAX_INSERT_OR_UPDATE_ATTEMPTS` (2): when a concurrent expiry delete drops the row
   between the failed INSERT and the UPDATE (UPDATE affects 0 rows), the row is now absent so the
   re-INSERT succeeds. Bounded so a pathologically re-deleted key cannot spin; if every attempt
   loses the race the write is abandoned at `warn` (regenerable cache, re-stored next upstream call).
   Two unit tests added (`InsertOrUpdateBoundedRetryScenarios`): retry-succeeds and retry-exhausted.
3. ✅ **Q4 DONE** — payload-body logging split: `DefaultFailoverHandler` keeps the store/recover
   **lifecycle** events at `INFO` (name only) and moves the full `ReferentialPayload` `toString`
   body to `DEBUG`. High-throughput services no longer pay full-payload serialization at `INFO`.

*Exit:* dialect paths verified on real DBs; logging production-friendly; race policy explicit (bounded retry).

### Phase 4 — Refactor & performance polish (optional, lowest priority)
**Goal:** maintainability and micro-perf, only if churn justifies it.
1. ✅ **A2 DONE** — `ScatterGatherFailoverHandler` split into a thin facade + `PayloadScatter` /
   `PayloadGather` / `SliceDispatcher` / `SplitterInvoker` collaborators (ADR 49). Public API and
   behaviour unchanged; PIT 96%/99% held.
2. ✅ **A3/Q2 DONE** — `Metrics` builder helper: concatenated keys (no `String.format`) + typed
   `collect` overloads; `AdvancedFailoverHandler` noise removed. Backed by a profile-gated JMH
   benchmark (`MetricsBuildBenchmark`): recover-bag build **744 → 204 ns/op (~3.6×)**. ADR 50.

*Exit:* smaller, cheaper hot path; no functional change.

---

## 8. Risk Register

| ID | Risk | Likelihood | Impact | Mitigation |
|---|---|:---:|:---:|---|
| D1 | ✅ **Resolved** — pointer repointed to `graphify-out/GRAPH_REPORT.md` | — | — | Done 2026-06-15 |
| A1 | ✅ **Resolved** — dead `spring.factories` block deleted, Javadoc corrected | — | — | Done 2026-06-15 |
| A5 | ✅ **Resolved** — Micrometer metrics autoconfig now ordered after Boot metrics autoconfigs; regression test added | — | — | Done 2026-06-15 |
| A6 | ✅ **Resolved** — Micrometer tracing autoconfig now ordered after Boot tracing autoconfigs; ordering-guard test added | — | — | Done 2026-06-15 |
| T1 | Silent coverage regression over time | Med | Med | Phase 2 JaCoCo gate |
| A4 | ✅ **Resolved** — bounded single retry re-inserts the concurrently-deleted row; abandons at `warn` only on repeated loss | — | — | Done 2026-06-15 |
| T3 | ✅ **Resolved** — Testcontainers dialect ITs (PostgreSQL/MySQL/MariaDB) exercise native merge on real engines | — | — | Done 2026-06-15 |

---

## 9. Conclusion

`failover` is a **high-quality, well-documented, thoroughly tested** library that reflects deliberate
architectural decisions and sustained engineering discipline. Historical review findings have been
addressed, the design is genuinely extensible, and the test posture (mutation + architecture +
concurrency) exceeds typical industry practice.

The recommended work is **incremental hardening**, not repair. The single highest-priority item is the
trivial **Phase 1 / D1** documentation-pointer fix; everything else is optional polish that further
raises an already-strong bar.

**Final grade: A− (excellent, release-ready).**

---

*Generated from a live source-tree audit. Knowledge-graph artifacts were treated as background and
re-verified against current code, per the note in §1.*
