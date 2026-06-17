# Code Audit Report (Re-Audit) — `societe-generale/failover`

> **Repository:** https://github.com/societe-generale/failover
> **Audited Version:** `main` branch — **v3.0.0-SNAPSHOT** (latest), last stable release: v2.1.1
> **Audit Date:** June 17, 2026
> **Audit Type:** Full re-audit against latest code (supersedes prior audit)
> **Stack:** Java 21+, Spring Boot 4.0.6, Spring Cloud 2025.1.1, Maven multi-module
> **Language Distribution:** Java 99.9%, Python 0.1%
> **Scope:** Architecture, clean code, design patterns, SOLID, testing, maintainability, reliability, **security/vulnerability**

---

## Table of Contents

1. [Executive Summary](#1-executive-summary)
2. [What Changed Since the Last Audit](#2-what-changed-since-the-last-audit)
3. [Repository Overview](#3-repository-overview)
4. [Design & Architecture](#4-design--architecture)
5. [Coding Standards & Clean Code](#5-coding-standards--clean-code)
6. [Design Patterns](#6-design-patterns)
7. [SOLID Principles](#7-solid-principles)
8. [Testing Strategy](#8-testing-strategy)
9. [Code Maintainability](#9-code-maintainability)
10. [Code Reliability & Resilience](#10-code-reliability--resilience)
11. [Security & Vulnerability Assessment](#11-security--vulnerability-assessment)
12. [Performance & Scalability](#12-performance--scalability)
13. [What Went Well](#13-what-went-well)
14. [Areas for Improvement — with Criticality](#14-areas-for-improvement--with-criticality)
15. [Phased Improvement Plan](#15-phased-improvement-plan)
16. [Summary Scorecard](#16-summary-scorecard)

---

## 1. Executive Summary

`failover` is an annotation-driven resilience library that transparently stores successful responses from referential services and replays the last known-good value when the upstream fails. This re-audit examines the latest `main` (v3.0.0-SNAPSHOT) and finds a library that has **measurably matured** since the prior review: most previously-flagged HIGH and MEDIUM issues have been explicitly resolved in code and documentation.

**Overall Rating: 9.0 / 10** (up from 8.4)

| Dimension          | Prior | Current | Trend |
|--------------------|:-----:|:-------:|:-----:|
| Architecture       | 9     | 9       | →     |
| Clean Code         | 8     | 9       | ↑     |
| Design Patterns    | 9     | 9       | →     |
| SOLID Compliance   | 9     | 9       | →     |
| Testing Strategy   | 8     | 8       | →     |
| Maintainability    | 8     | 9       | ↑     |
| Reliability        | 8     | 9       | ↑     |
| Security           | 7     | 9       | ↑↑    |
| Performance        | 8     | 8       | →     |
| Documentation      | 9     | 10      | ↑     |

The library is **production-ready**. The most significant improvement is the security posture: a dedicated security policy page now addresses PII handling, the deserialization-allowlist derivation algorithm (and its package-granular limit), SQL identifier validation for table/tenant prefixes, and multi-tenant isolation. A new, secure-by-default `failover-dashboard` module has been added with fail-closed access control and a static-only Content-Security-Policy.

Remaining work is incremental: deeper end-to-end test coverage of scatter/gather partial recovery, an optional reference `ContextPropagator` for security context, and a small number of build hygiene items.

---

## 2. What Changed Since the Last Audit

The codebase visibly responded to prior review findings. Verified resolutions:

| Prior Finding | Status Now | Evidence |
|---------------|-----------|----------|
| **PII in persistent stores undocumented** (was HIGH) | ✅ **Resolved** | Dedicated "Sensitive data (PII)" security section; documents store-as-secondary-repository risk; recommends `PayloadEnricher` encode/decode, TTL constraint, encryption-at-rest |
| **Deserialization allowlist completeness unverified** (was HIGH) | ✅ **Largely resolved** | Security page documents the exact derivation algorithm, its package-granular limit, JDK package exclusion, and the additive `allowed-payload-classes` override |
| **Multi-tenant table-name injection** (was MEDIUM) | ✅ **Resolved** | Prefixes validated against `([A-Za-z0-9_]+\.)*[A-Za-z0-9_]*`; tenant identifier never concatenated into table name |
| **InMemoryFailoverStore unbounded growth** (was MEDIUM) | ✅ **Resolved** | `failover.store.inmemory.max-entries` (default 10000, LRU eviction) |
| **Caffeine store no size limit** (was LOW) | ✅ **Resolved** | `failover.store.caffeine.max-size` (default 10000, Window-TinyLFU) |
| **Scatter timeout behavior undefined** (was LOW) | ✅ **Resolved** | `failover.scatter.timeout` (default 10s); documented per-slice timeout semantics for parallel path |
| **`enforce` plugin `<fail>false</fail>`** (was MEDIUM) | ⚠️ **Still open** | Parent POM still has `<fail>false</fail>` on enforcer |
| **Stale SCM tag `failover_1.1.0`** (was LOW) | ⚠️ **Still open** | Parent POM `<scm><tag>` still references old tag |
| **Scatter/gather end-to-end ITs** (was MEDIUM) | ◑ **Partially addressed** | Concurrency tests added (ADR 44); full partial-recovery IT still not evidenced |

New capabilities since the last audit:
- **`failover-dashboard` module** — self-contained, opt-in, secure-by-default operational dashboard (config/metrics/health views) served from the jar with no CDN, fail-closed security, and a static CSP.
- **Store size caps** for in-memory and Caffeine stores.
- **A formal security policy** mirrored from `SECURITY.md` with operator guidance.
- The ADR log has grown (now 53 ADRs spanning founding decisions through Phase 4 hardening).

---

## 3. Repository Overview

### Module Structure

```
failover (parent POM, v3.0.0-SNAPSHOT)
├── failover-domain                      @Failover · Referential · ReferentialAware · Metadata
├── failover-core                        FailoverHandler · KeyGenerator · ExpiryPolicy · PayloadEnricher · ContextPropagator
├── failover-aspect                      Spring AOP @Around interceptor
├── failover-store-inmemory              ConcurrentHashMap + LRU cap (dev/test)
├── failover-store-caffeine              Caffeine-backed in-process store (size-capped)
├── failover-store-jdbc                  JDBC store (H2 / PostgreSQL / MySQL / MariaDB / Oracle / SQL Server)
├── failover-store-async                 Non-blocking write decorator (virtual-thread executor)
├── failover-store-multitenant           TABLE_PREFIX / SCHEMA per-tenant routing
├── failover-execution-resilience        Resilience4j circuit-breaker integration
├── failover-scheduler                   Expiry-cleanup + report-publisher schedulers
├── failover-lookup                      Feign / proxy lookup utilities
├── failover-observable-scanner          @Failover scanner (Spring bean enumeration)
├── failover-observable-micrometer       Micrometer meters + Actuator health indicator
├── failover-dashboard(*-starter)        Opt-in, secure-by-default operational dashboard  [NEW]
├── failover-spring-boot-autoconfigure   Zero-config assembly
├── failover-spring-boot-starter         Single user-facing dependency
└── report                               JaCoCo aggregate coverage gate
```

### Quality Toolchain (from parent POM)

| Concern             | Tool / Version            |
|---------------------|---------------------------|
| Unit tests          | JUnit 5 + Mockito + AssertJ |
| Integration tests   | Failsafe + H2             |
| Dialect ITs         | Testcontainers 1.20.4     |
| Architecture tests  | ArchUnit 1.3.0            |
| Mutation testing    | PIT 1.20.0 (95% gate)     |
| Micro-benchmarks    | JMH 1.37                  |
| Coverage gate       | JaCoCo 0.8.15 (95%/95%)   |
| Static analysis     | SonarQube plugin 5.7      |
| Modernization       | OpenRewrite 6.39 (spring + migrate-java) |
| Null safety         | JSpecify 1.0.0            |
| Boilerplate         | Lombok 1.18.46            |

---

## 4. Design & Architecture

### 4.1 Core Architecture — Decorator Chains

The library is built around two explicit decorator chains assembled once at startup:

**Handler chain (invocation order):**
```
FailoverAspect → FailoverExecution (Basic|Resilience)
  → AdvancedFailoverHandler → ScatterGatherFailoverHandler → DefaultFailoverHandler → FailoverStore
```

**Store chain (invocation order):**
```
AsyncFailoverStore → MultiTenantFailoverStore → base store (InMemory | Caffeine | JDBC)
```

Both chains are built inside-out at startup and invoked outside-in at runtime. Each layer has one responsibility; each delegates inward then performs its own work on the way out. This is a faithful, textbook decorator implementation, and crucially the assembly is **explicit in auto-configuration** rather than reflection-driven — making the composition reviewable and testable.

### 4.2 Layer Responsibilities

| Layer | Responsibility |
|-------|----------------|
| `FailoverAspect` | AOP `@Around` interception; resolves reflected `Method`; routes to execution |
| `FailoverExecution` | Calls upstream; routes success→store, exception→recover; Basic (try/catch) or Resilience (circuit breaker) |
| `AdvancedFailoverHandler` | Micrometer metrics + `RecoveredPayloadHandler`; captures `is-recovered` before payload handler runs |
| `ScatterGatherFailoverHandler` | Splits composite results into per-entity slices; merges on recover; pass-through when no splitter |
| `DefaultFailoverHandler` | Key derivation + expiry compute/check + enrich + store/find |
| Store chain | Async offload → tenant routing → physical store |

### 4.3 ADR-Driven Architecture (53 ADRs)

The decision log is the standout architectural asset. It documents not just decisions but their evolution — e.g., ADR 11 (`BeanPostProcessor` store wrapping) is explicitly **Deprecated** and superseded by ADRs 16/18/19 (explicit auto-config assembly). This append-only, immutable record is rare even in commercial codebases.

Representative high-quality decisions:
- **ADR 26** — `Instant` over `LocalDateTime` for timezone-safe expiry across multi-node/multi-timezone deployments.
- **ADR 37** — secure-by-default deserialization allowlist.
- **ADR 39** — `Error` (OOM/StackOverflow) rethrown unwrapped; never recover on a dying JVM.
- **ADR 47** — bounded retry (2 attempts) on JDBC INSERT/UPDATE race; warns and abandons rather than looping or silently dropping.
- **ADR 50** — JMH-validated metric construction optimization (~3.6× faster recover-bag build).
- **ADR 53** — cross-module JaCoCo gate at 95% line / 95% branch.

### 4.4 Extensibility via SPI

Thirteen-plus pluggable SPIs (`FailoverStore`, `FailoverExecution`, `ExpiryPolicy`, `KeyGenerator`, `RecoveredPayloadHandler`, `PayloadEnricher`, `PayloadSplitter`, `TenantResolver`, `TenantStoreFactory`, `ContextPropagator`, `DatabaseResolver`, `ObservablePublisher`, `MethodExceptionHandler`). Every default bean is `@ConditionalOnMissingBean`, so any behavior is replaceable without forking. This is the architectural backbone and remains exemplary.

### 4.5 Architectural Concerns

**[MEDIUM] Bidirectional chain assembly cognitive load** — The "build inside-out, invoke outside-in" model is well-documented but demands careful reading for new contributors. A fluent builder for chain construction would make assembly self-documenting.

**[LOW] Module count (16+)** — Fine-grained modules maximize optional-dependency control but add reactor overhead. Small modules (`failover-lookup`, `failover-observable-scanner`) could be core subpackages without losing pluggability.

---

## 5. Coding Standards & Clean Code

### 5.1 Naming — Strong

Class, method, SPI, and configuration-property names are consistent and intention-revealing: `DefaultFailoverHandler`, `ScatterGatherFailoverHandler`, `FailoverStoreQueryResolver`, `cleanByExpiry()`, `enrichOnRecover()`, `failover.store.inmemory.max-entries`. SPI types consistently end in their role (`*Handler`, `*Store`, `*Resolver`, `*Factory`, `*Policy`, `*Publisher`).

### 5.2 Single Responsibility — Strong

Per-class focus is tight. ADR 49 explicitly decomposed `ScatterGatherFailoverHandler` into a thin facade plus `PayloadScatter` / `PayloadGather` / `SliceDispatcher` / `SplitterInvoker` collaborators — a clean-code refactor that reduced a complex class to coordinator status.

### 5.3 Configuration Hygiene — Improved

The properties reference is now comprehensive: every `failover.*` property has a type, default, and description; "There are no mandatory properties — the framework starts with production-safe defaults." Secure-by-default switches (`dashboard.enabled=false`, `multitenant.strict`, store caps) are clearly marked. This is a notable clean-code improvement at the configuration surface.

### 5.4 Residual Clean-Code Items

**[MEDIUM] SPI Javadoc contracts** — Public SPI interfaces should embed their behavioral contracts as Javadoc `@implSpec` (e.g., `FailoverStore.find()` defensive-copy requirement; `splitOnRecover` single-placeholder contract). These are documented on the concepts/ADR pages but not yet at the interface itself, leaving third-party implementers reliant on external docs.

**[LOW] Overloaded "Referential" term** — `Referential` (domain base class) vs `ReferentialPayload` (store envelope) vs the general "referential service" concept. `FailoverPayload` would disambiguate the envelope (semver-breaking, so low priority).

**[LOW] `domain` vs `name` on `@Failover`** — Semantically adjacent attributes; new users may conflate them without reading ADR 28. A one-line note in the quickstart would help.

---

## 6. Design Patterns

| Pattern | Application | Quality |
|---------|-------------|---------|
| Decorator / Chain | Handler chain + store chain | ★★★★★ |
| Strategy | `ExpiryPolicy`, `KeyGenerator`, `FailoverExecution`, `MethodExceptionHandler` | ★★★★★ |
| Proxy / AOP | `FailoverAspect` transparent interception | ★★★★★ |
| Template Method | `AbstractFailoverHandler` bridges method-aware/agnostic handlers | ★★★★☆ |
| Factory | `TenantStoreFactory` per-tenant store creation | ★★★★☆ |
| Observer / Publisher | `ObservablePublisher` → Micrometer / Log / Composite | ★★★★☆ |
| Composite | Composite publisher; scatter slice composition | ★★★★☆ |
| Scatter/Gather | `PayloadScatter` / `PayloadGather` / `SliceDispatcher` | ★★★★☆ |
| Null Object | `RecoveredPayloadHandler` default | ★★★★☆ |
| BOM / Starter | `failover-spring-boot-starter`, `failover-dashboard-spring-boot-starter` | ★★★★★ |
| Builder | Lombok `@Builder` on payload/config types | ★★★☆☆ |

**Resolved anti-pattern:** The `BeanPostProcessor` store-wrapping approach (ADR 11) was correctly identified as a lifecycle hazard and removed in favor of explicit auto-configuration (ADRs 16/18/19). Good self-correction.

**Pattern watch:** The scatter/gather implicit protocol (ADR 34 `recoverAll()` throws `UnsupportedOperationException`; ADR 35 empty-guard returns null; ADR 36 single-placeholder contract) is sophisticated but subtle — it belongs in interface-level `@implSpec` (see §5.4).

---

## 7. SOLID Principles

**SRP — ★★★★★** Macro (16 focused modules) and micro (single-purpose classes) both strong; ADR 49 reinforced it.

**OCP — ★★★★★** SPI family + `@ConditionalOnMissingBean` means closed-for-modification, open-for-extension is applied uniformly across every behavior.

**LSP — ★★★★☆** Decorators (`Async`, `MultiTenant`) and `ResilienceFailoverExecution extends BasicFailoverExecution` honor their supertype contracts. The one blemish: `ScatterGatherFailoverHandler.recoverAll()` intentionally throws `UnsupportedOperationException` (ADR 34) — defensible as a fail-loud guard, but a narrower interface or documented default method would be cleaner.

**ISP — ★★★★☆** Interfaces are small and focused (`ExpiryPolicy`, `KeyGenerator`, `ContextPropagator`). The method-aware `FailoverHandler` contract (ADR 52) widened the SPI; `AbstractFailoverHandler` bridges it, adding one indirection layer.

**DIP — ★★★★★** All cross-module dependencies point at abstractions; concrete wiring is confined to auto-configuration. Textbook compliance.

---

## 8. Testing Strategy

### 8.1 Multi-Tier Strategy (verified)

```
Unit (*Test.java)        → Surefire, pure Mockito/JUnit, no Spring context
Integration (*IT.java)   → Failsafe, @SpringBootTest on real H2, async=false (deterministic)
Dialect IT (*DialectIT)  → Testcontainers PG/MySQL/MariaDB, profile dialect-its (opt-in)
Concurrency tests        → multi-tenant routing + async executor contention (default build)
Architecture (ArchUnit)  → no-ThreadLocal-in-async, *Store naming, acyclic slices
Mutation (PIT)           → failover-core expiry/key, profile mutation, 95% gate, ~96%/99% strength
Benchmarks (JMH)         → metric construction, profile benchmark (non-gating)
Coverage (JaCoCo)        → cross-module 95% line / 95% branch (ADR 53), report module
```

`mvn clean verify` runs unit + H2 integration with **no Docker**. Heavy jobs (dialect ITs, mutation) are advisory/non-blocking in CI; the H2 build is the required gate. This two-tier approach (fast hermetic default, opt-in deep checks) is well-judged.

### 8.2 Strengths

- A 95% mutation gate with `failWhenNoMutations=true` prevents vacuous passes — far stronger than line coverage alone.
- Real-database dialect ITs validate native merge/upsert SQL rather than mocking the JDBC layer.
- ArchUnit codifies architectural invariants the compiler cannot enforce.
- ITs run with `async=false` for deterministic assertions — a mature choice.

### 8.3 Gaps (criticality)

**[MEDIUM] Scatter/gather partial-recovery end-to-end IT** — The most complex path needs explicit `@SpringBootTest` coverage where some slices recover and others do not, against a real store (not mocks), asserting the exact composite returned and the partial-recovery metric.

**[MEDIUM] Circuit-breaker state-transition ITs** — `ResilienceFailoverExecution` across CLOSED→OPEN→HALF_OPEN→CLOSED should be integration-tested, not only unit-mocked.

**[LOW] Scatter timeout path IT** — `failover.scatter.timeout` should be exercised with a deliberately slow store to prove a hung slice never blocks the caller.

**[LOW] Dashboard security ITs** — The fail-closed behavior (no Spring Security → context fails fast unless `allow-insecure=true`) and the `FAILOVER_ADMIN` role gate deserve explicit ITs.

**[LOW] SPI conformance harness** — A published test-kit that validates custom `FailoverStore` / `ExpiryPolicy` / `PayloadSplitter` implementations against the framework's contracts would protect third-party extensions.

---

## 9. Code Maintainability

### 9.1 Documentation — Exceptional (10/10)

Full MkDocs site: concepts (with sequence/state/flow diagrams), configuration reference, how-to guides, per-module pages, 53 ADRs, changelog (Keep a Changelog format), FAQ, contributing, and a dedicated security page. Inline POM comments tie configuration to audit phases and ADR numbers. This is best-in-class for an OSS library.

### 9.2 Dependency Management

- Spring Boot + Spring Cloud + Testcontainers BOMs centralize transitive versions.
- `maven-enforcer-plugin` runs `dependencyConvergence` and `requireReleaseDeps`.
- OpenRewrite configured (`rewrite-spring`, `rewrite-migrate-java`) for automated modernization.

**[MEDIUM] Enforcer is advisory** — `<fail>false</fail>` means convergence violations are reported but never break the build. For a Maven Central library, convergence bugs harm consumers most; this should be `<fail>true</fail>`.

**[LOW] Stale SCM tag** — Parent POM `<scm><tag>failover_1.1.0</tag>` is a config-drift artefact on a 3.0.0-SNAPSHOT line.

### 9.3 Build Reproducibility

UTF-8 encoding enforced; compiler and all plugin versions pinned; release plugin `pushChanges=false` prevents accidental force-push; JaCoCo `argLine` ordering documented inline. Solid.

---

## 10. Code Reliability & Resilience

### 10.1 Reliability Mechanisms (verified in latest)

- **Error vs Exception separation (ADR 39):** `java.lang.Error` rethrown unwrapped — recovery never runs on a failing JVM.
- **Defensive copy (ADR 10):** `FailoverStore.find()` returns a copy; callers mutate `upToDate`/`asOf` without corrupting persisted state.
- **Expiry-aware recovery:** expired entries never served, deleted on access.
- **Bounded JDBC race retry (ADR 47):** re-INSERTs once on a concurrent expiry-delete, then warns — no infinite loop, no silent drop.
- **Async failure visibility (ADR 41):** `AsyncFailoverStore` publishes `failover.store.async.failed` on executor-side failures.
- **Store caps:** in-memory LRU (`max-entries`) and Caffeine (`max-size`) prevent unbounded heap growth from high-cardinality keys.
- **Scatter timeout (ADR 38):** per-slice timeout bounds the parallel join; a timed-out recover slice = not recovered; a hung slice never blocks the caller indefinitely.
- **Multi-tenant strict mode (ADR 40):** rejects unconfigured tenants instead of silently routing to the global table.

### 10.2 Exception Policy

`failover.exception-policy`: `RETHROW` (default), `NEVER_THROW` (returns null / `RecoveredPayloadHandler` result), or `CUSTOM`. Clear, well-defaulted, well-documented.

### 10.3 Residual Reliability Items

**[HIGH→MEDIUM, downgraded] Scatter/gather partial recovery semantics** — Timeout and not-found handling are now far better specified (ADR 38). The remaining concern is the *caller-facing contract* when a composite is partially recovered: in a financial context a silently-shortened collection can be worse than a clean failure. Recommend (a) a documented default policy, (b) a partial-recovery metric, and (c) an optional "all-or-nothing" gather mode.

**[MEDIUM] `RecoveredPayloadHandler` failure isolation** — The handler runs after the `is-recovered` flag is captured (correct for metrics), but a throwing handler could surface to callers. Wrap the invocation in try/catch and log at ERROR; the recovered value should still return.

**[MEDIUM] Virtual-thread context propagation trap** — `AsyncFailoverStore` runs on virtual threads; ArchUnit forbids `ThreadLocal` in async paths and `ContextPropagator` exists, but Spring Security's default `ThreadLocal` `SecurityContextHolder` will silently not propagate to scatter slices unless the user implements `ContextPropagator`. Ship a reference `SecurityContextPropagator` or a loud documented warning.

**[LOW] Cleanup under load** — `cleanByExpiry` on large JDBC tables needs an index on `expire_on`. Recommend mandating the index in the shipped DDL and noting it in the JDBC module docs.

---

## 11. Security & Vulnerability Assessment

This section reflects the **current** security posture, which is materially stronger than at the prior audit thanks to a dedicated security policy page and the new dashboard module.

### 11.1 Deserialization Security — Strong (was the top concern)

**Mechanism:** The JDBC store deserializes by the class name in the `PAYLOAD_CLASS` column, restricted by an allowlist that is **secure by default**. The framework auto-allows the *packages* of every discovered `@Failover` payload type (return types + collection/array element types), minus JDK packages (`java.*`, `javax.*`, `jakarta.*`, never whitelisted). Allow-all is used only when no payload types are discovered **and** `allowed-payload-classes` is empty.

**Assessment:** This closes the classic "deserialize arbitrary class from the database" gadget-chain vector — an attacker who compromises the database cannot materialize arbitrary classes; only the application's own referential packages are loadable.

**[MEDIUM] Residual — package-granular, not deep type graph.** The allowlist is built from payload-type packages, not a transitive field-type closure. A payload whose *nested field* types live in a different package is not auto-allowed and must be added via `allowed-payload-classes`. This is documented (recovery throws `FailoverStoreException` naming the class), but:
- A package-prefix entry widens the deserialization surface — operators may over-grant to "make it work." Recommend a tighter default that prefers exact class names and warns when a package prefix is used.
- Recommend deserialization ITs with complex nested/generic payloads to validate the failure mode and the documented remediation.

### 11.2 SQL Injection — Strong

- **Data:** Spring `JdbcTemplate` parameterized queries throughout (ADR 27 migrated to varargs overloads, removing deprecated `java.sql.Types` usage).
- **Identifiers:** `failover.store.jdbc.table-prefix` and per-tenant prefixes are validated against `([A-Za-z0-9_]+\.)*[A-Za-z0-9_]*` at store/query-resolver build time; values containing spaces, quotes, `;`, or `--` are rejected with `IllegalArgumentException` before reaching SQL.
- **Tenant identity:** the tenant *identifier* is never concatenated into a table name — only its operator-configured, validated prefix is.

**Assessment:** Both the data plane (parameterized) and the identifier plane (validated, non-parameterizable) are covered. A hostile tenant id cannot inject SQL via the table name. This fully resolves the prior MEDIUM finding.

### 11.3 PII / Sensitive Data — Now Documented (was HIGH)

The security page explicitly frames the failover store as a **secondary copy of upstream responses** that may carry PII into a repository with different access controls, audit logging, encryption-at-rest, and retention than the system of record. The library deliberately does **not** mask/encrypt (it stores exactly what it recovers) and instead provides operator guidance:
- Encode/encrypt at the boundary via a `PayloadEnricher` (encode on store, decode on recover) with a documented example.
- Constrain TTL so PII does not linger; ensure expiry cleanup runs.
- Apply the same encryption-at-rest/access-control/retention to the JDBC store as any PII datastore.
- Prefer not failover-protecting highly sensitive methods if a stale copy is unacceptable.

**Assessment:** This is the correct posture for a library that cannot know the sensitivity of arbitrary payloads. The transparency-plus-guidance approach resolves the prior HIGH finding.

**[LOW] Residual** — A shipped, reusable `EncryptingPayloadEnricher` (AES-GCM with externalized key management) as an optional module would lower the barrier and reduce the chance operators roll their own incorrectly.

### 11.4 Dashboard Attack Surface — Fail-Closed (new module)

The new `failover-dashboard` is **secure-by-default**:
- Disabled unless `failover.dashboard.enabled=true`.
- With Spring Security present: contributes a `SecurityFilterChain` scoped to `base-path/**` requiring role `FAILOVER_ADMIN` (overridable).
- With Spring Security absent: **context fails fast at startup** unless `allow-insecure=true`, which starts unsecured with a loud repeated WARN (dev/trusted-network only).
- A static-only **Content-Security-Policy** on every dashboard response (no remote/inline scripts; Chart.js vendored).
- API is **read-only** — no endpoint mutates state.
- **Data minimization:** exposes only annotation metadata and aggregate counts — never payload data, keys, credentials, or connection strings.

**Assessment:** This is a well-designed administrative surface. Fail-closed + data-minimization + read-only + static CSP is exactly right.

**[LOW] Residual** — Recommend explicit ITs for the fail-fast (no-Security) path and the role gate, plus a note that `allow-insecure=true` must never reach production (consider refusing it when a `prod` profile is active).

### 11.5 JVM-Fatal Errors

`Error` propagates unwrapped through the aspect — a dying process fails fast rather than serving stale data on a corrupted JVM. Correct.

### 11.6 Vulnerability & Supply-Chain Hygiene

- **Disclosure policy:** `SECURITY.md` + GitHub Security Advisories; 5-business-day acknowledgement; supported versions matrix (3.x ✅, 2.x critical-only, <2.0 ❌).
- **Dependency versions:** managed by BOMs; enforcer checks `requireReleaseDeps` (no snapshots in releases).

**[MEDIUM] Recommend automated dependency/vulnerability scanning in CI** — Add OWASP Dependency-Check or GitHub Dependabot alerts + a CVE-scanning gate (e.g., `dependency-check-maven` or Trivy on the build). For a financial-services library this should be a required, regularly-run check, not left implicit.

**[LOW] Reproducible-build / artifact signing** — The release profile signs artifacts with GPG (good). Consider publishing a documented checksum/SBOM (CycloneDX) per release for downstream supply-chain verification.

### 11.7 Security Summary Table

| Vector | Status | Note |
|--------|:------:|------|
| Java deserialization gadget chains | ✅ Strong | Secure-by-default allowlist; JDK packages excluded |
| SQL injection (data) | ✅ Strong | Parameterized `JdbcTemplate` |
| SQL injection (identifiers) | ✅ Strong | Regex-validated prefixes; tenant id never in table name |
| PII leakage to secondary store | ✅ Documented | Operator guidance + `PayloadEnricher` encode/decode |
| Admin dashboard exposure | ✅ Fail-closed | Role-gated, data-minimized, static CSP, read-only |
| JVM-fatal error mishandling | ✅ Correct | `Error` rethrown unwrapped |
| Multi-tenant cross-tenant leakage | ✅ Strong | Strict mode; validated per-tenant prefixes |
| Dependency CVEs | ⚠️ Improve | No evidenced automated CVE gate in CI |
| Nested-type deserialization over-grant | ⚠️ Improve | Package-granular allowlist invites broad prefixes |

---

## 12. Performance & Scalability

- **Async writes on virtual threads** keep the latency-sensitive read/recover path synchronous and unblocked — the correct Java 21 idiom for I/O-bound store writes.
- **UUID/MD5 key normalization (ADR 22)** yields fixed-width store keys, protecting JDBC index performance and preventing VARCHAR overflow.
- **Metric construction optimized (ADR 50)** — JMH-proven ~3.6× faster on the recover path (744→204 ns/op).
- **Parallel scatter/gather (ADR 24)** via `CompletableFuture` on an injected executor, bounded by per-slice timeout (ADR 38).
- **Store caps** (in-memory LRU, Caffeine Window-TinyLFU) bound memory.
- **Dialect-native upsert (ADR 13)** avoids SELECT-then-write round trips on supported databases.

**[LOW] Cleanup-at-scale** — `cleanByExpiry` needs an `expire_on` index (see §10.3). **[LOW]** Connection-pool tuning guidance for high-throughput JDBC deployments is still absent. **[LOW]** Virtual-thread executor sizing defaults under very high scatter concurrency should be load-validated and documented.

---

## 13. What Went Well

**Architecture & Design**
1. Two explicit decorator chains, assembled in auto-config (not reflection) — reviewable and testable.
2. 53 ADRs with append-only, evolution-tracked decisions (ADR 11 → 16/18/19).
3. 13+ pluggable SPIs with uniform `@ConditionalOnMissingBean` — replace anything without forking.
4. Pure, dependency-free `failover-domain` module.

**Security (most-improved)**
5. Secure-by-default deserialization allowlist with documented derivation and limits.
6. Regex-validated SQL identifiers; tenant id never concatenated into table names.
7. Honest, actionable PII guidance with a `PayloadEnricher` encode/decode pattern.
8. New dashboard: fail-closed, role-gated, data-minimized, read-only, static CSP.
9. `Error` never triggers recovery on a failing JVM.

**Reliability**
10. Defensive copy on `find()`; bounded JDBC race retry; async-failure metric; store caps; scatter timeout; multi-tenant strict mode.

**Testing & Quality**
11. Unit → H2 IT → Testcontainers dialect IT → ArchUnit → PIT (95% gate) → JMH → JaCoCo (95%/95%).
12. Fast hermetic default build (no Docker); heavy checks opt-in/advisory.

**Maintainability**
13. Best-in-class documentation site; changelog; FAQ; contributing; security policy.
14. OpenRewrite for automated modernization; BOM-managed dependencies; pinned plugins.

**Modern Java**
15. Java 21 virtual threads; `Instant`-based expiry; JSpecify null-safety; Spring Boot 4 / Spring Cloud 2025.

---

## 14. Areas for Improvement — with Criticality

| # | Issue | Category | Criticality |
|---|-------|----------|:-----------:|
| I-01 | No evidenced automated dependency/CVE scanning gate in CI | Security/Supply-chain | 🟠 MEDIUM |
| I-02 | Package-granular allowlist invites broad prefix over-grants | Security | 🟠 MEDIUM |
| I-03 | Scatter/gather partial-recovery caller-facing contract | Reliability | 🟠 MEDIUM |
| I-04 | `RecoveredPayloadHandler` failure isolation (try/catch + ERROR log) | Reliability | 🟠 MEDIUM |
| I-05 | Virtual-thread `SecurityContext` propagation trap; ship reference `ContextPropagator` | Reliability | 🟠 MEDIUM |
| I-06 | `maven-enforcer-plugin` `<fail>false</fail>` | Build | 🟠 MEDIUM |
| I-07 | SPI interface Javadoc `@implSpec` contracts missing | Maintainability | 🟠 MEDIUM |
| I-08 | Scatter/gather partial-recovery end-to-end IT missing | Testing | 🟠 MEDIUM |
| I-09 | Circuit-breaker state-transition ITs missing | Testing | 🟡 LOW |
| I-10 | Dashboard fail-closed / role-gate ITs missing | Testing | 🟡 LOW |
| I-11 | `ScatterGatherFailoverHandler.recoverAll()` LSP carve-out | Design | 🟡 LOW |
| I-12 | JDBC `expire_on` index not mandated in shipped DDL | Performance | 🟡 LOW |
| I-13 | Optional `EncryptingPayloadEnricher` module would reduce PII footguns | Security | 🟡 LOW |
| I-14 | `allow-insecure=true` should be refused under a prod profile | Security | 🟡 LOW |
| I-15 | Stale SCM tag `failover_1.1.0` in parent POM | Build | 🟡 LOW |
| I-16 | Connection-pool tuning guidance absent | Performance | 🟡 LOW |
| I-17 | Published SBOM (CycloneDX) per release | Supply-chain | 🟡 LOW |
| I-18 | `domain` vs `name` attribute confusion for new users | Usability | 🟡 LOW |
| I-19 | `ReferentialPayload` naming ambiguity | Usability | 🟢 TRIVIAL |
| I-20 | 16+ modules add reactor overhead | Maintainability | 🟢 TRIVIAL |

> Note: every prior-audit HIGH item has been resolved or downgraded. No open HIGH findings remain.

---

## 15. Phased Improvement Plan

### Phase 1 — Security & Supply-Chain Hardening (Sprint 1, ~2 weeks)
| Task | Issue | Effort |
|------|-------|:------:|
| 1.1 Add OWASP Dependency-Check / Trivy CVE gate to CI (required job) | I-01 | M |
| 1.2 Publish CycloneDX SBOM per release | I-17 | S |
| 1.3 Allowlist: prefer exact class names; WARN on package-prefix grants; add nested-type deserialization ITs | I-02 | M |
| 1.4 Refuse `dashboard.security.allow-insecure=true` when a `prod` profile is active | I-14 | S |
| 1.5 Flip `maven-enforcer-plugin` to `<fail>true</fail>` | I-06 | XS |

### Phase 2 — Reliability Edge Cases (Sprint 2, ~2 weeks)
| Task | Issue | Effort |
|------|-------|:------:|
| 2.1 Define + document scatter/gather partial-recovery policy; add `failover.recovery.partial_total` metric; add optional all-or-nothing gather mode | I-03 | M |
| 2.2 Wrap `RecoveredPayloadHandler` in try/catch; log ERROR; still return recovered value | I-04 | S |
| 2.3 Ship reference `SecurityContextPropagator` (or loud documented warning) | I-05 | M |
| 2.4 Mandate `expire_on` index in shipped DDL; document it | I-12 | S |

### Phase 3 — Test Depth (Sprint 3, ~2.5 weeks)
| Task | Issue | Effort |
|------|-------|:------:|
| 3.1 End-to-end scatter/gather partial-recovery IT on real store | I-08 | L |
| 3.2 Circuit-breaker state-transition ITs (CLOSED→OPEN→HALF_OPEN→CLOSED) | I-09 | M |
| 3.3 Dashboard fail-closed + `FAILOVER_ADMIN` role-gate ITs | I-10 | M |
| 3.4 Publish/SPI conformance test-kit for custom Store/ExpiryPolicy/Splitter | — | M |

### Phase 4 — Maintainability & API Polish (Sprint 4, ~1.5 weeks)
| Task | Issue | Effort |
|------|-------|:------:|
| 4.1 Add `@implSpec` Javadoc to all SPI interfaces (defensive-copy, splitOnRecover contracts) | I-07 | M |
| 4.2 Resolve `recoverAll()` LSP carve-out (narrower interface or documented default) | I-11 | M |
| 4.3 Quickstart note: `domain` vs `name` | I-18 | XS |
| 4.4 Fix stale SCM tag | I-15 | XS |
| 4.5 Connection-pool tuning guidance in JDBC docs | I-16 | S |

### Phase 5 — Optional Value-Adds (backlog)
| Task | Issue | Effort |
|------|-------|:------:|
| 5.1 Optional `EncryptingPayloadEnricher` module (AES-GCM + externalized KMS) | I-13 | L |
| 5.2 Publish JMH benchmark results in docs; load-test cleanup + VT executor sizing | I-12/perf | M |
| 5.3 Evaluate `ReferentialPayload` → `FailoverPayload` rename (semver-breaking) | I-19 | S |

**Effort key:** XS < 2h · S = 2-4h · M = 1-2d · L = 3-5d

---

## 16. Summary Scorecard

| Dimension | Score | Notes |
|-----------|:-----:|-------|
| Architecture | 9/10 | Explicit decorator chains; 53 ADRs; SPI extensibility |
| Clean Code | 9/10 | Strong naming/SRP; config hygiene improved; SPI Javadoc gap remains |
| Design Patterns | 9/10 | Correct, well-applied; scatter protocol needs `@implSpec` |
| SOLID | 9/10 | One LSP carve-out in scatter/gather |
| Testing | 8/10 | Excellent toolchain; partial-recovery & CB-transition ITs missing |
| Maintainability | 9/10 | Best-in-class docs; enforcer-advisory + stale tag minor |
| Reliability | 9/10 | Many edge cases now handled; handler isolation + VT context remain |
| **Security** | **9/10** | **Major leap: allowlist, identifier validation, PII guidance, fail-closed dashboard; CVE-gate the main gap** |
| Performance | 8/10 | VT async, UUID keys, JMH-tuned; cleanup index + pool guidance pending |
| Documentation | 10/10 | Comprehensive site, ADRs, changelog, security policy |
| **Overall** | **9.0/10** | **Production-grade; no open HIGH findings** |

---

### Final Verdict

This re-audit finds a library that has **acted on its review feedback**. Every previously-identified HIGH finding (PII documentation, deserialization allowlist, multi-tenant injection) has been resolved, and several MEDIUM/LOW items (store caps, scatter timeout) are closed. The security posture in particular has jumped from "adequate with documented risks" to "strong, secure-by-default, with honest operator guidance" — anchored by a dedicated security policy, a validated SQL-identifier layer, a secure-by-default deserialization allowlist, and a fail-closed administrative dashboard.

The remaining work is incremental and non-structural: a CI CVE-scanning gate (the single most valuable add for a financial-services library), a clearly-defined caller-facing contract for partial scatter/gather recovery, failure isolation for the recovered-payload handler, a reference security-context propagator, and a few targeted integration tests. None require architectural change.

**Recommendation:** Safe to adopt in production. Prioritize Phase 1 (CVE gate + allowlist tightening) and Phase 2 (partial-recovery contract + handler isolation) before protecting sensitive or collection-returning referential endpoints at scale.

---

*Report generated: June 17, 2026 | Methodology: documentation & ADR audit, architecture tracing, configuration-surface review, security-policy analysis, prior-audit delta comparison | Repository state: main branch, v3.0.0-SNAPSHOT | This report supersedes the prior audit and reflects the latest available code and documentation.*
