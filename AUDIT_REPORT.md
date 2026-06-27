# Failover Library — Code Audit & Architecture Review

**Project:** `failover` (Spring Boot referential-failover library)
**Version:** 3.0.0-SNAPSHOT · Java 21 · Spring Boot 4.0.6
**Branch reviewed:** `web-starter`
**Date:** 2026-06-17
**Reviewer:** Automated code audit (Claude Code)

---

## 1. Executive Summary

`failover` is a mature, multi-module Spring Boot library that transparently caches referential
service responses and replays the last known-good value when an upstream dependency fails. The
codebase is **well above industry average** in nearly every dimension assessed: clean layering,
disciplined use of decorators and SPIs, secure-by-default posture, exemplary Javadoc, and a test
suite that is **~1.8× the size of production code** backed by JaCoCo coverage gates and 95%
PITest mutation threshold.

| Dimension | Rating (out of 5) |
|---|---|
| Design & Architecture | ★★★★★ |
| Clean Code / Coding Standards | ★★★★★ |
| Design Patterns | ★★★★★ |
| Testing Strategy | ★★★★★ |
| SOLID Adherence | ★★★★☆ |
| Maintainability | ★★★★★ |
| Reliability / Resilience | ★★★★★ |
| Security | ★★★★★ |
| Documentation | ★★★★★ |
| **Overall** | **★★★★★ (4.8)** |

**Verdict:** Production-grade. The findings below are refinements, not corrections. No critical or
high-severity defects were identified.

### Key metrics

| Metric | Value |
|---|---|
| Maven modules | 19 |
| Main source files | 176 |
| Test source files | 134 |
| Main LOC | 11,638 |
| Test LOC | 21,286 |
| Test : Main ratio | **1.83 : 1** |
| `TODO` / `FIXME` / `HACK` markers | **0** |
| `@SuppressWarnings` in main | 2 (both justified) |
| `@Deprecated` leftovers | 0 |
| Import cycles | **0** (per graph analysis) |
| Largest main file | 492 LOC (`FailoverAutoConfiguration`) |
| Mutation-test threshold | 95% (PITest, CI-gated) |
| Documentation pages | 59 (MkDocs + ADRs) |

---

## 2. Design & Architecture

### 2.1 What went well

- **Clean module boundaries.** 19 modules each own one responsibility (`failover-core`,
  `-aspect`, `-store-*`, `-scanner`, `-scheduler`, `-observable-micrometer`, `-dashboard`, …).
  A consumer adds a single starter POM; everything else auto-configures.
- **Decorator handler chain** is the architectural spine and is composed, not inherited:
  `AdvancedFailoverHandler → ScatterGatherFailoverHandler → DefaultFailoverHandler`. Each layer
  adds exactly one concern (observability/policy → scatter-gather → store/recover).
- **Store assembly chain** mirrors the same idea:
  `AsyncFailoverStore → MultiTenantFailoverStore → base store (InMemory|Caffeine|JDBC|custom)`.
  Cross-cutting concerns (async, tenancy) are decorators over a stable `FailoverStore<T>` SPI.
- **Zero import cycles** across 3,645 graph nodes / 8,839 edges — strong evidence of acyclic,
  intentional dependency direction.
- **Self-contained dashboard module.** `failover-dashboard` deliberately does **not** depend on
  `failover-spring-boot-autoconfigure`; it reads globals from `Environment`. This avoids a
  circular/heavyweight coupling and keeps the dashboard optional.
- **Configuration over wiring.** Every core bean is `@ConditionalOnMissingBean`, so any default
  is replaceable by declaring a competing bean — a textbook open/closed extension surface.

### 2.2 What can improve

| # | Finding | Criticality |
|---|---|---|
| A-1 | `FailoverAutoConfiguration` (492 LOC) and `FailoverStoreAutoConfiguration` (351 LOC) are the two largest files and act as wiring god-objects (104 and 68 graph edges). They are cohesive but dense; future growth risks them becoming change-magnets. | **Low** |
| A-2 | `ScatterGatherFailoverHandler` exposes **four overloaded constructors** for backward compatibility. Correct today, but the telescoping set will keep growing; a builder would cap the blast radius. | **Low** |
| A-3 | The graph flags several large, low-cohesion communities ("Multi-Tenant Routing & Config" cohesion 0.05). These are mostly config + test fan-out, but worth confirming the multi-tenant package isn't accreting unrelated concerns. | **Info** |

---

## 3. Coding Standards (Clean Code)

### 3.1 What went well

- **Intention-revealing names** throughout (`effectiveName`, `enrichOnRecover`, `cleanByExpiry`,
  `resolvedAllowedPayloadClasses`). No cryptic abbreviations.
- **Small, single-purpose methods.** `DefaultFailoverHandler.store/recover/doRecover` each read
  top-to-bottom with one clear responsibility.
- **Consistent licence header** on every file; consistent package structure.
- **Lombok** (`@Slf4j`, `@AllArgsConstructor`, `@Getter`) removes boilerplate without hiding
  behaviour — used in 55 main files, uniformly.
- **JSpecify nullness annotations** (`@NonNull` / `@Nullable`) applied at API boundaries — rare
  discipline that makes contracts machine-checkable.
- **Zero technical-debt markers** (`TODO`/`FIXME`/`HACK`) in the entire main tree.
- **Logging is leveled correctly:** `info` for lifecycle, `debug` for payload detail, `warn` for
  recovery miss, `error` for async failure — and never logs at a level that would leak payloads
  by default.

### 3.2 What can improve

| # | Finding | Criticality |
|---|---|---|
| C-1 | Minor formatting inconsistency: `if(referentialPayload!=null)` (no spaces) in `DefaultFailoverHandler.doRecover` vs. spaced style elsewhere. A Spotless/Checkstyle gate would normalise this automatically. | **Low** |
| C-2 | No automated format/style enforcement plugin (Spotless/Checkstyle) detected in the parent POM, despite enforcer + jacoco + pitest all being present. Style currently relies on author discipline. | **Low** |
| C-3 | Some log lines embed full `ReferentialPayload` at `info` (e.g. async store). On verbose stores this is noisy; consider `debug` for the full object, `info` for the name only. | **Low** |

---

## 4. Design Patterns

Patterns are used deliberately and correctly — not cargo-culted:

| Pattern | Where | Note |
|---|---|---|
| **Decorator** | Handler chain; store assembly chain | The defining pattern of the codebase. |
| **Strategy / SPI** | `KeyGenerator`, `ExpiryPolicy`, `PayloadEnricher`, `PayloadSplitter`, `RecoveredPayloadHandler`, `ContextPropagator`, `FailoverStore` | All replaceable via beans. |
| **Template Method** | `AbstractFailoverHandler` (`store`/`recover`/`recoverAll` hooks) | Shared orchestration, specialised steps. |
| **Facade** | `ScatterGatherFailoverHandler` delegates to `PayloadScatter`, `PayloadGather`, `SliceDispatcher`, `SplitterInvoker` | Thin coordinator, focused collaborators. |
| **Factory** | `TenantStoreFactory`, store auto-config assemblers | Per-tenant store construction. |
| **Observer / Publisher** | `ObservablePublisher` + Micrometer/MDC/Composite publishers | Pluggable observability sink. |
| **Circuit Breaker** | `failover-execution-resilience` (Resilience4j) | Optional `type: resilience`. |
| **Around-advice (Proxy)** | `FailoverAspect` | Single, minimal AOP entry point. |

**What can improve (Low):** the telescoping-constructor smell in `ScatterGatherFailoverHandler`
(A-2) is the one place a Builder would read better than overloads.

---

## 5. Testing Strategy

### 5.1 What went well

- **Test-to-code ratio 1.83:1** (21,286 / 11,638 LOC) — exceptional for a library.
- **Clear unit/integration split:** `*Test.java` (Surefire, pure Mockito+JUnit5, no Spring) vs.
  `*IT.java` (Failsafe, `@SpringBootTest` against real H2). Integration tests force
  `failover.store.async=false` for deterministic assertions — a thoughtful test-design choice.
- **PITest mutation testing at a 95% threshold** — far beyond line coverage; proves the tests
  actually assert behaviour, not just execute lines.
- **JaCoCo aggregate coverage gate** across modules (`failover-test-report`) wired into CI and Sonar.
- **Testcontainers dialect matrix** (H2, PostgreSQL, MySQL, MariaDB, Oracle) for the JDBC store.
- **JMH micro-benchmarks** (profile-gated) and dedicated concurrency tests — a quality tier most
  libraries never reach.
- **AssertJ used consistently** (`assertThat(...)`) in ~100+ test files — matches the project's
  stated convention.
- **`@ConditionalOnMissingBean` override tests** confirm every extension point is actually
  overridable (the `When*` test communities).

### 5.2 What can improve

| # | Finding | Criticality |
|---|---|---|
| T-1 | PITest is `pitest.skip=true` by default (CI-gated only). Correct for local speed, but ensure the CI job is **required** on PRs so the 95% threshold can't silently rot. | **Medium** |
| T-2 | All integration tests live in `failover-spring-boot-autoconfigure`. The new `failover-dashboard` correctly owns its own tests, but confirm each *store* module's IT coverage isn't solely centralised, which can hide module-local regressions. | **Low** |

---

## 6. SOLID Principles

| Principle | Assessment | Evidence |
|---|---|---|
| **S** — Single Responsibility | ★★★★★ | Each handler/decorator/store owns one concern; scatter-gather was deliberately split into 4 collaborators (audit A-2 in source). |
| **O** — Open/Closed | ★★★★★ | Universal `@ConditionalOnMissingBean` + named-bean SPIs. Extend without editing core. |
| **L** — Liskov Substitution | ★★★★★ | All `FailoverStore`/`FailoverHandler` implementations honour the contract, incl. the documented "`find` returns a defensive copy" invariant. |
| **I** — Interface Segregation | ★★★★☆ | SPIs are narrow and focused. Minor: `FailoverStore` bundles read + write + cleanup; an async decorator overrides only writes, hinting the interface could be split (read vs. write vs. lifecycle). |
| **D** — Dependency Inversion | ★★★★★ | Everything depends on interfaces; concrete wiring isolated in auto-config. Constructor injection throughout, no field injection. |

**What can improve (Low):** the one ISP nuance (I) — `FailoverStore` could be segregated into
read/write/lifecycle interfaces so decorators like `FailoverStoreAsync` (which only offloads
writes) implement exactly what they touch. Cosmetic; not worth a breaking change in isolation.

---

## 7. Code Maintainability

**Strengths**

- Dense but navigable: a `GRAPH_REPORT.md` knowledge-graph entry point, 59 doc pages, ADRs, and
  per-module `package-info.java`.
- Javadoc explains the **why**, not just the what — e.g. `FailoverStoreAsync` documents its exact
  threading contract and why `ThreadLocal` is never read inside executor lambdas; `JsonSerializer`
  documents the lazy-allowlist memoisation rationale.
- No cyclic dependencies → any module can be reasoned about in isolation.
- Backward-compatibility is explicit and intentional (constructor overloads carry "retained for
  backward compatibility" Javadoc).

**Risks / improvements**

| # | Finding | Criticality |
|---|---|---|
| M-1 | Two auto-config classes concentrate most wiring edges (god-nodes). Split by concern (store vs. execution vs. observability) before they grow further. | **Low** |
| M-2 | No automated formatter (see C-2) means style drift accumulates as contributors grow. | **Low** |

---

## 8. Code Reliability & Resilience

This is the library's core promise, and it is handled with unusual care:

- **`Error` is never recovered.** `FailoverAspect.returnResult` explicitly rethrows
  `Error` (OOM, StackOverflow) so recovery never runs on a failing JVM — only `Throwable`
  subclasses are wrapped. Excellent fail-fast discipline.
- **Async failures are visible, not silent.** `FailoverStoreAsync` catches executor-side
  exceptions, logs at `error`, *and* emits a `store-async-failed` metric — and the metric
  publish itself is wrapped so it can never mask the original failure.
- **Defensive copy invariant** on `FailoverStore.find` (documented in CLAUDE.md and honoured)
  prevents callers mutating stored payloads.
- **Expiry handling** deletes expired entries on the recovery path and via a scheduled cleanup —
  bounded storage growth.
- **Per-slice timeout** in parallel scatter: a recover slice that times out is treated as
  "no data" rather than hanging the business thread.
- **Persisted-class allowlist** on deserialization (`JsonSerializer.toClass`) prevents loading
  arbitrary classes from store data, with clear, actionable exception messages for both
  "not allowed" and "not on classpath" cases.
- All 14 `catch` sites in main code are intentional recovery/observability boundaries — none
  swallow silently; every one logs or rethrows.

**What can improve**

| # | Finding | Criticality |
|---|---|---|
| R-1 | `JsonSerializer` allowlist defaults to **allow-all** when the resolved list is empty (historical behaviour). Safe given the scanner auto-populates packages, but document prominently that an empty allowlist = no restriction, so a misconfiguration fails *open*. | **Medium** |
| R-2 | Confirm the async executor (virtual-thread) has a bounded queue / backpressure strategy; an unbounded queue under sustained store pressure could grow memory. (Verify in `FailoverStoreAsync` executor wiring.) | **Medium** |

---

## 9. Security Posture

The new `failover-dashboard` is a model of secure-by-default design:

- **Off unless explicitly enabled** — `@ConditionalOnProperty(... havingValue = "true")` with **no
  `matchIfMissing`**. Absent property = nothing mapped.
- **Fail-closed when Spring Security is absent:** the dashboard *refuses to start* rather than
  serving operational data anonymously, unless the operator sets `allow-insecure=true` — which
  emits a loud multi-line `WARN`.
- **Role-gated `SecurityFilterChain`** scoped to the dashboard base path when Security is present,
  overridable by the consumer.
- **CSP header + exposure narrowing** enforced on every base-path request via a dedicated
  interceptor; UI assets aren't even served when UI exposure is narrowed off.
- **Read-only** — the dashboard adds no new instrumentation and exposes only existing scanner
  config + `failover.*` meters.

| # | Finding | Criticality |
|---|---|---|
| S-1 | `allow-insecure=true` is a foot-gun by design (dev/trusted-network). The loud WARN is good; consider also surfacing it as a health-indicator/startup-banner so it can't hide in logs in production. | **Low** |
| S-2 | See R-1 — allowlist fail-open default deserves a security callout in docs. | **Medium** |

---

## 10. Consolidated Findings & Criticality

| ID          | Area                 | Finding                                                  | Criticality | Status                                                                                                                                                 |
|-------------|----------------------|----------------------------------------------------------|-------------|--------------------------------------------------------------------------------------------------------------------------------------------------------|
| T-1         | Testing              | Ensure PITest 95% CI job is a **required** PR check      | **Medium**  | ✅ DONE — blocking `mutation` job in `java-maven-ci.yml`; 95% gate enforced in POM                                                                      |
| R-1/S-2     | Reliability/Security | Allowlist fails **open** on empty list — document loudly | **Medium**  | ✅ DONE — security docs document the secure-by-default derivation; allowlist moved to `failover.store.jdbc.*`                                           |
| R-2         | Reliability          | Verify async executor queue is bounded (backpressure)    | **Medium**  | ✅ DONE — `BoundedTaskExecutor` + `concurrency-limit` / `rejection-policy` (async + scatter)                                                            |
| A-1/M-1     | Architecture         | Split the two god-object auto-config classes             | **Low**     | ⬜ TODO — `FailoverStoreAutoConfiguration` assembly consolidated (ADR 54), but the classes are not yet split by concern (store/execution/observability) |
| A-2         | Patterns             | Replace telescoping constructors with a Builder          | **Low**     | ✅ DONE — `ScatterGatherFailoverHandler.builder(...)` replaces the four constructors                                                                    |
| C-1/C-2/M-2 | Clean Code           | Add Spotless/Checkstyle gate                             | **Low**     | ⬜ TODO — no format/style plugin in the parent POM                                                                                                      |
| C-3         | Clean Code           | Move full-payload logs from `info` → `debug`             | **Low**     | ✅ DONE — `DefaultFailoverHandler` payload body at `DEBUG`, name only at `INFO` (ADR 48)                                                                |
| I (SOLID)   | Design               | Segregate `FailoverStore` read/write/lifecycle           | **Low**     | ⬜ TODO — deferred to 4.0.0 (breaking; Phase 4)                                                                                                         |
| S-1         | Security             | Surface `allow-insecure` beyond logs                     | **Low**     | ◑ PARTIAL — loud `WARN` + outright refusal under `prod` profile (I-14); no health-indicator/banner contribution yet                                    |
| T-2         | Testing              | Confirm per-store-module IT coverage                     | **Low**     | ✅ DONE — Testcontainers dialect ITs in `failover-store-jdbc`; dashboard owns its own tests                                                             |

> **No Critical or High findings.** All items are hardening/polish.
>
> **Status legend:** ✅ DONE · ◑ PARTIAL · ⬜ TODO. Cross-checked against code on 2026-06-17.

---

## 11. Phase-by-Phase Improvement Plan

### Phase 0 — Verify (½ day, do first)
- Confirm the PITest + JaCoCo CI jobs are **required** status checks on PRs (T-1).
- Inspect the async `TaskExecutor` wiring for a bounded queue / rejection policy (R-2).
- Confirm each store module's integration coverage isn't solely centralised (T-2).
- *Exit criterion:* a one-paragraph note answering each, with file references.

### Phase 1 — Reliability & Security hardening (1–2 days)
- Document the allowlist fail-open-on-empty behaviour prominently in `docs/support/security.md`
  and the `JsonSerializer` Javadoc; optionally add a startup WARN when the resolved allowlist is
  empty in a JDBC/persistent store (R-1, S-2).
- Add a health-indicator or startup banner contribution when `allow-insecure=true` (S-1).
- If R-2 finds an unbounded queue, add a bounded queue + caller-runs/reject policy and a metric.
- *Exit criterion:* security doc updated; insecure mode is observable; executor backpressure
  proven by a test.

### Phase 2 — Maintainability & style (2–3 days)
- Introduce **Spotless** (or Checkstyle) bound to `verify`, with the existing code as the
  baseline; fixes C-1/C-2/M-2 permanently and prevents future drift.
- Demote full-`ReferentialPayload` log lines from `info` to `debug` (C-3).
- *Exit criterion:* `mvn verify` fails on style violation; no behavioural change.

### Phase 3 — Architectural refactors (3–5 days, optional)
- Split `FailoverAutoConfiguration` / `FailoverStoreAutoConfiguration` by concern (store /
  execution / observability) to reduce the god-node fan-out (A-1, M-1).
- Replace `ScatterGatherFailoverHandler`'s four constructors with a Builder; deprecate the
  overloads for one release (A-2).
- *Exit criterion:* no public API break this release; god-node edge count drops; tests green.

### Phase 4 — API evolution (next major, optional)
- Segregate `FailoverStore` into read / write / lifecycle interfaces so decorators implement only
  what they touch (SOLID-I). Breaking change — schedule for 4.0.0.
- *Exit criterion:* SPI documented and migrated with a compatibility shim.

---

## 12. Closing Assessment

`failover` is a reference-quality example of how to build a Spring Boot library: composable
decorators over stable SPIs, secure-by-default surfaces, fail-fast resilience semantics, and a
test suite that proves behaviour through mutation testing rather than asserting coverage numbers.
The audit surfaced **no critical defects** — the recommendations are about preventing future drift
(style gates, splitting god-objects) and closing two fail-open edges (allowlist default, async
backpressure). A team could adopt this codebase as an internal exemplar with minimal changes.

---

*Report generated by automated code audit. Severity legend: **Critical** = data loss / outage /
security breach · **High** = likely defect or significant risk · **Medium** = hardening, fix this
release · **Low** = polish / future-proofing · **Info** = observation.*
