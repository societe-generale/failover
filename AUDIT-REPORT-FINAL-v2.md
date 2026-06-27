# Failover Library — Adoption & Operational Audit (FINAL v2)

**Date:** 2026-06-21
**Reviewer:** Anand Manissery (assisted)
**Scope:** Adoption ease, added complexity, runtime overload, safety, performance impact on consuming microservices.
**Method:** Knowledge-graph review (`graphify-out/GRAPH_REPORT.md`) grounded against core source: `FailoverAspect`, `Failover` annotation, `DefaultFailoverHandler`, `AdvancedFailoverHandler`, `BasicFailoverExecution`, `FailoverStoreAsync`, `ResilienceFailoverExecution`.

---

## Executive Summary

| Dimension | Rating | One-line verdict |
|---|---|---|
| Ease of adoption | ✅ Low cost | One starter dep + one annotation + one property. |
| Added code complexity | ✅ Low | Declarative; advanced features fully opt-in. |
| Added operational complexity | ⚠️ Moderate | Store infra, stale-data UX, monitoring required. |
| Runtime overload | ✅ Low (happy path) | Async writes keep caller thread free; DB write volume scales with QPS. |
| Safety | ✅ Strong | Failover bugs never break the real call; fails open/closed deliberately. |
| Performance impact | ✅ Negligible (success) | Cost paid mostly on the already-failing path. |

**Overall:** Safe to adopt. Steady-state caller overhead is negligible. The real work is operational: choosing a durable store, surfacing stale-data semantics to users, registering payload types on the deserialization allowlist, and monitoring async-write failures.

---

## 1. Ease of Adoption

### How to add failover to a microservice

1. **Add one dependency** — `failover-spring-boot-starter`. Zero-config auto-configuration wires the full handler chain.
2. **Annotate a concrete method:**
   ```java
   @Failover(name = "country", expiryDuration = 3, expiryUnit = ChronoUnit.DAYS)
   public Country getCountry(String code) { ... }
   ```
3. **Pick a store via property:**
   ```yaml
   failover:
     store:
       type: jdbc        # inmemory | caffeine | jdbc
   ```

Only `name()` is mandatory (`Failover.java:61`). Everything else defaults: expiry `1 HOUR`, key derived from method args, default expiry policy, no scatter/gather.

### Constraints that affect adoption

| Constraint | Impact | Mitigation |
|---|---|---|
| Annotation must be on a **concrete impl method, not an interface** (CGLIB proxy). | Misplacement = silent no-op. | Code review / startup scanner (`failover-scanner`) lists all `@Failover` methods at boot. |
| **Self-invocation** bypasses the Spring AOP proxy. | Internal calls to an annotated method aren't intercepted. | Standard Spring-AOP limitation; call through the bean reference. |
| **Durable store needed in prod.** `inmemory`/`caffeine` are per-instance, lost on restart, not shared across instances. | No cross-instance / cross-restart recovery. | Use `jdbc` store (H2/Postgres/MySQL/MariaDB/Oracle) or shared-store cluster tier. |
| Unique `name()` per failover point. | Collisions silently share entries. | Reserve `domain` for *intentional* sharing only. |

**Verdict:** Adoption is genuinely low-friction at the code level. The friction is operational (store choice), not developmental.

---

## 2. Added Complexity to Consuming Microservices

### Code-level complexity — LOW

Single declarative annotation. Mental model is small: *store last-known-good on success, replay on failure.* All extension points are opt-in via `@ConditionalOnMissingBean`:

- `KeyGenerator`, `ExpiryPolicy`, `PayloadEnricher`, `RecoveredPayloadHandler`, `PayloadSplitter`.
- Skip them entirely → zero added complexity.

### Operational complexity — MODERATE (the real cost)

| Area | Detail |
|---|---|
| **Store infrastructure** | JDBC store adds a table + datasource dependency to the service. Caffeine avoids infra but is per-instance and non-durable. |
| **Stale-data semantics** | Recovered value is *old*. `DefaultFailoverHandler` sets `upToDate`/`asOf` on the recovered payload (defensive copy). Consumer UI/API must surface "data as of <timestamp>". Business must explicitly accept stale-over-error. |
| **Scatter/gather** | The one genuinely complex feature: `PayloadSplitter` slices a composite result into per-entity store entries and merges on recover (`Failover.java:113-129`, `recoverAll` at `:173`). Only adopt when per-entity slicing is actually required. |
| **Exception policy choice** | `rethrow` (default) vs `never_throw`. `never_throw` means callers never see the upstream exception — can mask outages. Choose deliberately. |

**Verdict:** App code stays simple. Budget for operational design (store, UX, policy), not for invasive code changes.

---

## 3. Runtime Overload / Extra Load

### Write path (success) — caller not blocked

Default `failover.store.async=true`. Writes (`store`, `delete`, `cleanByExpiry`) are offloaded to a virtual-thread `TaskExecutor`; the caller thread is not blocked (`FailoverStoreAsync.java:107-120`). `find`/`findAll` stay synchronous (`:145`, `:158`) but only run on the **failure** path, never on success.

Happy-path caller cost = upstream latency + one async-submitted write + non-blocking metric publish. Negligible.

### Load sources to size and monitor

| Source | Concern | Action |
|---|---|---|
| **JDBC write volume** | One upsert per *successful* call. High-QPS endpoints generate proportional DB write load. | Size store DB for the endpoint's success throughput. Consider Caffeine for ultra-high-QPS where durability isn't required. |
| **Async executor saturation** | On overload, async store failures are caught, logged, and counted via meter `store-async-failed` — **data is silently not persisted** (`FailoverStoreAsync.java:114-118`, `:196`). | Alert on `failover.store.async.failed`. |
| **Schedulers** | Hourly expiry cleanup + daily report. | Cheap; verify cron windows. |

**Verdict:** No caller-thread overload by design. The load that exists is DB write volume on the success path — plan store capacity accordingly.

---

## 4. Safety

> **Security-relevant findings are written in plain language below — read this section fully before production rollout.**

### Core safety principle: a failover bug never breaks the real call

| Guarantee | Evidence |
|---|---|
| Store failure on success path is caught, logged, ignored. | `BasicFailoverExecution.java:105` — "This will impact only the failover flow". |
| Recover failure is caught, ignored; null handled by `RecoveredPayloadHandler`. | `BasicFailoverExecution.java:144`, `AdvancedFailoverHandler.java:82`. |
| A misbehaving `RecoveredPayloadHandler` is guarded; raw payload returned. | `AdvancedFailoverHandler.java:110-117` (audit I-06). |
| `Error` (OOM, StackOverflow) is **never** converted to a recoverable exception — recovery does not run on a dying JVM. | `FailoverAspect.java:68-71`. |
| `find` returns a **defensive copy**; callers mutate `upToDate`/`asOf` without corrupting stored data. | CLAUDE.md / ADR 10. |

### Security findings (action required)

1. **Deserialization allowlist (ADR 37).** The JDBC store persists payloads as JSON and deserializes on recover. An allowlist (`JsonSerializer` / "Deserialization Allowlist") prevents arbitrary-class gadget deserialization. **Action:** every custom payload type you store must be registered on the allowlist, or recovery fails closed. Verify the allowlist is reviewed whenever a new stored type is introduced.

2. **PII / sensitive data in the store.** The store persists upstream responses verbatim. Any sensitive field in a response lands in the backing store (DB or cache). **Action:** use `PayloadEnricher` to mask or encrypt sensitive fields on store; apply DB-level access controls and encryption-at-rest; treat the failover store as a system holding production response data, with the same classification as the source.

3. **`never_throw` exception policy can mask outages.** With `failover.exception-policy=never_throw`, callers always receive a recovered-or-null value and never observe the upstream failure. **Action:** ensure upstream failures remain observable via metrics/alerts (`failover.recovery.outcome.total`, recover-action metrics in `AdvancedFailoverHandler`) so a silent dependency outage is still detected.

**Verdict:** Safety design is strong and deliberate. The three security items above are the gating concerns before production.

---

## 5. Performance Impact on the Actual Microservice

### Happy path — near-zero

- Async write decorator keeps the store off the caller thread (`FailoverStoreAsync.java`).
- Metric publish is non-blocking (`BasicFailoverExecution.java:112-123`).
- Added work per call: AOP around-advice, one `ReferentialPayload` allocation, a metrics bag, `System.nanoTime()` timing.

### Failure path — bounded, paid only when upstream already failing

On exception: a synchronous store `find` + expiry check + payload enrich before returning the stale value (`DefaultFailoverHandler.java:72-93`). This cost is incurred only when the upstream is already down — stale-fast beats erroring.

### Resilience mode reduces load on a failing dependency

With `failover.type=resilience`, each failover point is wrapped in a Resilience4j circuit breaker named after the failover (`ResilienceFailoverExecution.java:67-70`). When the breaker is OPEN, the upstream is short-circuited (not called, not timed) and the stale value is served immediately — actively *reducing* pressure on the failing dependency.

### Overhead inventory

| Item | Cost |
|---|---|
| Spring AOP around-advice per annotated call | Standard Spring-AOP reflection cost. |
| Per-call allocation (`ReferentialPayload`, metrics bag) | Minor GC pressure; measured via JMH (profile-gated benchmarks, ADR 50). |
| Upstream-duration timing | Two `nanoTime` reads, non-blocking publish. |
| Circuit breaker (resilience mode) | Resilience4j per-name state machine; net reduces load during outages. |

**Verdict:** Negligible success-path impact. Measured, not assumed (JMH micro-benchmarks exist).

---

## 6. Consolidated Areas to Address Before Production

| # | Area | Severity | Status | Action |
|---|---|---|---|---|
| A1 | Durable store selection | High | ✅ **FIXED** (2026-06-22) | Startup WARN now names the recommended store (JDBC for prod/multi-instance); added a store decision guide + deployment-topology docs. Use JDBC for multi-instance / restart-surviving recovery; Inmemory/Caffeine are dev/per-instance only. |
| A2 | Async-write failure monitoring | High | ✅ **FIXED** (2026-06-22) | Submit-time executor rejection (saturation/shutdown) now emits `failover.store.async.failed` (was previously uncounted); alert on that meter. Docs updated with the alert + `DISCARD` caveat. |
| A3 | Deserialization allowlist | High (security) | ✅ **FIXED** (2026-06-22) | Allowlist already auto-derived from `@Failover` types (secure by default); added opt-in `failover.store.jdbc.strict-allowlist` that fails **closed** on an empty allowlist (was fail-open allow-all). Register custom stored types; enable strict in prod. |
| A4 | PII / sensitive data handling | High (security) | ✅ **FIXED** (2026-06-22) | Shipped built-in AES-GCM cipher (`failover.store.jdbc.encryption.cipher=aesgcm` + `key`) for real encryption-at-rest with no consumer crypto. Mask fields via `PayloadEnricher`; classify store same as source data. |
| A5 | Stale-data UX | Medium | ✅ **PROVIDED BY DESIGN** (verified 2026-06-22) | Not a library gap. Library exposes `upToDate`/`asOf`/`metadata` (`Referential`/`ReferentialAware`), auto-populated on every recover by `DefaultPayloadEnricher`. Surfacing in UI/API + business sign-off is the consumer's responsibility (mechanism + docs already supplied). |
| A6 | Exception-policy decision | Medium | ✅ **FIXED** (2026-06-23) | Mechanism already existed (rethrow/never_throw/custom + startup log + policy-independent outage metrics). Fixed a misleading javadoc on `NeverRethrowMethodExceptionPolicy`, sharpened the `never_throw` startup WARN to name outage-masking + the metrics to alert on, and documented it. Choosing the policy remains the consumer's call. |
| A7 | Store DB capacity | Medium | ✅ **FIXED** (2026-06-25) | Added opt-in `failover.store.jdbc.live-entries-gauge-enabled` → `failover.live.entries` gauge (JDBC `COUNT(*)`) so operators can monitor/alert on table growth; capacity-planning docs added. Still size for success-path write volume; bounded executor (ADR 57) + cleanup bound growth. |
| A8 | Annotation placement & self-invocation | Low | ✅ **FIXED** (2026-06-25) | Scanner now WARNs at startup when a discovered `@Failover` can't be advised (interface-only, non-public/static/final method, final class). Self-invocation documented (not statically detectable). Annotate concrete impl methods; avoid self-invocation. |
| A9 | Key-generation stability | Low | ✅ **FIXED** (2026-06-25) | Default generator already keys stably (records/enums/value types via `toString`) and warns on identity-hash args; throttled that WARN to once-per-type (was per-call hot-path flood) and added key-stability docs (identity hash, unordered collections, collisions, domain compatibility). |
| A10 | Scatter/gather adoption | Low | ✅ **FIXED** (2026-06-25) | Added scanner WARN for `recoverAll=true` without a `payloadSplitter` (was a silent no-op) + adoption/complexity docs (default to single-key; `recoverAll` needs a splitter). Partial-recovery already observable (`failover.recovery.partial.total`). Only adopt when per-entity slicing is needed. |

---

## Appendix — Evidence Index

| Claim | File:Line |
|---|---|
| Only `name()` required | `failover-domain/.../annotations/Failover.java:61` |
| Defaults (expiry 1 HOUR) | `Failover.java:69,86` |
| Scatter/gather + recoverAll | `Failover.java:113-173` |
| AOP around-advice, Error not recoverable | `failover-aspect/.../FailoverAspect.java:56-76` |
| Store-on-success ignores failover errors | `failover-core/.../BasicFailoverExecution.java:103-110` |
| Recover ignores failover errors | `BasicFailoverExecution.java:139-148` |
| Non-blocking upstream metric | `BasicFailoverExecution.java:112-123` |
| Async write offload; find sync | `failover-store-async/.../FailoverStoreAsync.java:107-160` |
| Async-failure metric `store-async-failed` | `FailoverStoreAsync.java:114-118,196-208` |
| Metrics at method-call granularity; handler guard | `failover-core/.../AdvancedFailoverHandler.java:60-117` |
| Defensive copy on `find`, expiry delete | `failover-core/.../DefaultFailoverHandler.java:72-93` |
| Circuit breaker per failover name | `failover-execution-resilience/.../ResilienceFailoverExecution.java:67-70` |
| Deserialization allowlist, PII | ADR 37, `docs/support/security.md`, `JsonSerializer` |

---

## 7. Detailed Remediation Plan

Each item maps to the action register in §6. Format per item: **Problem → Target state → Steps → Config/Code → Verification → Owner/Effort**.

---

### A1 — Durable store selection (Severity: High) — ✅ FIXED (2026-06-22)

**Resolution shipped.** Library-side guidance was strengthened so a non-durable store cannot be chosen *silently*:
- **Startup WARN now names the recommended store.** The InMemory and Caffeine config WARNs (`FailoverStoreAutoConfiguration.java`) state the store is NON-DURABLE (per-instance, lost on restart, no production protection) and recommend `failover.store.type=jdbc` for production / multi-instance; Caffeine is flagged as single-node-only.
- **Documentation added** in `docs/configuration/store-types.md`: a *Choosing a Store* decision guide (decision tree + question→store table + non-durable-in-prod danger callout) and a *Deployment Topologies & Modes* section (single-node / clustered-multi-instance / multi-tenant / async-vs-sync + summary matrix), clarifying store-cluster (use JDBC shared DB) vs. dashboard-cluster.
- Verified: `failover-spring-boot-autoconfigure` test suite green (269 tests, 0 failures). Advisory only — no fail-fast, no new properties (deliberate, per maintainer decision).

The consumer-side guidance below remains the recommended action for each deploying service.

**Problem.** `inmemory`/`caffeine` stores are per-instance and non-durable. On restart or in a multi-instance deployment, recovered data is lost or unavailable to other instances, so failover silently provides no protection after the first cold start.

**Target state.** Production runs a shared, durable store (JDBC, or the shared-store cluster tier) so any instance can recover last-known-good after restart and across the fleet.

**Steps.**
1. Provision a store table in the service's database (or a dedicated failover schema).
2. Switch config to JDBC:
   ```yaml
   failover:
     store:
       type: jdbc
       jdbc:
         table-prefix: ""      # or per-service prefix to avoid collisions in a shared DB
   ```
3. Confirm the dialect is auto-detected (H2/Postgres/MySQL/MariaDB/Oracle via `DatabaseResolver` / `FailoverStoreQueryResolver`).
4. If multiple tenants share infra, enable multi-tenant routing:
   ```yaml
   failover:
     store:
       multitenant:
         enabled: true
   ```
5. Keep `caffeine` only for explicitly non-durable, ultra-high-QPS, single-instance use cases; document that choice.

**Verification.** Integration test against real DB (pattern: `*IT.java` in `failover-spring-boot-autoconfigure`, `failover.store.async=false` for determinism). Restart a node, force an upstream failure, assert stale value still served. Run the existing dialect Testcontainers CI job for the target DB.

**Owner / Effort.** Platform + service team. ~0.5–1 day per service (mostly DDL + datasource wiring).

---

### A2 — Async-write failure monitoring (Severity: High) — ✅ FIXED (2026-06-22)

**Resolution shipped.** Closed the observability gap where a *saturated* executor dropped writes with no metric:
- **Root cause found.** The `failover.store.async.failed` meter was only emitted for failures thrown *inside* the executor task. On saturation, `executor.execute(...)` rejects at **submit time** on the calling thread (bounded executor, `ABORT` policy → `RejectedExecutionException`); that throw escaped the write methods and was swallowed by the caller's generic failover-error handler — **never counted**. This is the exact "silently drops persistence" condition.
- **Fix** (`FailoverStoreAsync.java`): submit is now wrapped in a `submit(operation, name, task)` helper that catches submit-time rejection, logs it, and emits the same `failover.store.async.failed` meter (`exception-type=java.util.concurrent.RejectedExecutionException`), then swallows it — a dropped cache write must never break the business call.
- **Tests**: added submit-rejection coverage (store/delete/cleanByExpiry emit the metric; swallowed with and without a publisher). Async module green (BUILD SUCCESS).
- **Docs** (`docs/how-to/observability.md`): the async-failure section now documents both failure modes (in-flight + submit-time rejection) and notes the `DISCARD` policy logs WARN without metering (use `ABORT` to have rejections counted).

The operational guidance below remains the recommended action for each deploying service (wire `MeterRegistry`, alert on the meter).

**Problem.** With `failover.store.async=true` (default), a saturated or failing executor causes store writes to be caught, logged, and counted — but **silently not persisted** (`FailoverStoreAsync.java:114-118`). Without an alert, the store quietly goes stale and recovery degrades unnoticed.

**Target state.** The `store-async-failed` condition is a first-class, alerting metric on the service dashboard.

**Steps.**
1. Ensure the Micrometer module is present (`failover-observable-micrometer`) and a `MeterRegistry` is wired (Actuator/Prometheus).
2. Alert on the async-failure meter (tag `action=store-async-failed`, emitted via `FailoverStoreAsync.emitFailure`, meter `failover.store.async.failed`).
3. Define a threshold: any sustained non-zero rate ⇒ warn; rate above N/min ⇒ page.
4. Size the executor / store throughput so saturation is rare; document the QPS ceiling.
5. Optional: for endpoints where lost persistence is unacceptable, evaluate `failover.store.async=false` (synchronous, deterministic, at the cost of caller latency).

**Verification.** Inject a store failure (spy throwing in delegate) and assert the meter increments — see existing `AsyncFailureMetricTests` / `ADR 41 — Async Store Failure Metric`. Confirm the alert fires in staging.

**Owner / Effort.** SRE/observability + service team. ~0.5 day (mostly dashboard + alert rule).

---

### A3 — Deserialization allowlist (Severity: High, security) — ✅ FIXED (2026-06-22)

**Resolution shipped.** Closed the one remaining fail-open path in an otherwise secure-by-default allowlist:
- **Already in place (verified).** `JsonSerializer.toClass` enforces an allowlist auto-derived from every `@Failover` payload type discovered by the scanner (exact FQCN, JDK packages excluded — audit I-02) plus `failover.store.jdbc.allowed-payload-classes`. Non-listed classes are refused per-row with a `FailoverStoreException` (fails closed). Nested/generic field types are reconstructed structurally and intentionally not gated.
- **Gap found.** When the resolved allowlist was **empty** (no `@Failover` types discovered *and* no configured entries), `isAllowed` returned `true` for everything — **allow-all / fail-open**, only a `WARN`. A misconfiguration could silently re-open the deserialization-gadget surface.
- **Fix.** Added opt-in `failover.store.jdbc.strict-allowlist` (default `false`, backward-compatible). When `true`, an empty allowlist **denies all** deserialization (fail-closed, logged at `ERROR`); the normal scanner-derived/configured path is unchanged. Threaded `Jdbc.strictAllowlist` → `serializer()` bean → new `JsonSerializer(..., strict)` constructor; `isAllowed` returns `!strict` on empty.
- **Tests** (`JsonSerializerTest`): strict+empty denies all (incl. app class + null supplier), non-strict+empty still allows (legacy), strict+populated enforced normally, ERROR log asserted. JDBC + autoconfigure modules green (BUILD SUCCESS, 40 serializer testcases).
- **Docs** (`docs/support/security.md`): documented `strict-allowlist`, fail-open vs fail-closed, recommended on for production.

The consumer-side guidance below remains (register custom types; enable strict in production).

**Problem.** The JDBC store serializes payloads to JSON and deserializes on recover. An unrestricted deserializer is a gadget-chain risk. The library mitigates with a deserialization allowlist (ADR 37 / `JsonSerializer`), but any custom stored type must be explicitly allowed or recovery fails closed.

**Target state.** Every payload type that is stored is on the allowlist; the allowlist is reviewed whenever a new stored type is introduced; no wildcard/`Object`-level allowance.

**Steps.**
1. Inventory all `@Failover` method return types (use the startup scanner output / `FailoverScanner`).
2. Register each concrete payload type (and nested types serialized within) on the deserialization allowlist (`failover.store.jdbc` allowlist config — verify exact property against `JsonSerializer` / `MergeAllowedPayloadClasses`).
3. Add a checklist item to PR review: "new `@Failover` type ⇒ allowlist updated."
4. Confirm the allowlist fails **closed** (recovery returns null / handled, never deserializes an unlisted class).
5. Avoid permissive entries (no package wildcards that re-open the gadget surface).

**Verification.** Unit test: attempt recover of an unlisted type ⇒ blocked + logged, no instantiation. Confirm all production types deserialize correctly in an `*IT.java` round-trip (store → recover).

**Owner / Effort.** Service team + security review. ~0.5 day initial inventory; ongoing per-type cost is trivial.

---

### A4 — PII / sensitive data handling (Severity: High, security) — ✅ FIXED (2026-06-22)

**Resolution shipped.** Made real encryption-at-rest usable without consumer crypto code:
- **Gap.** ADR 56 already provided the encryption framework (`PayloadCipher` SPI, `EncryptingSerializer`, `ENC(...)` envelope), but the only built-in cipher was `b64` (encoding, not encryption). Real protection required every team to hand-write AES-GCM — so PII-at-rest was effectively unaddressed for non-crypto teams.
- **Fix.** Shipped a production-grade `AesGcmPayloadCipher` (id `aesgcm`) in `failover-store-jdbc`: AES-GCM, fresh random IV per write, 128-bit auth tag, `Base64(IV‖ciphertext+tag)`; `decrypt` fails loudly on wrong key / tampered row. Auto-registered from new `failover.store.jdbc.encryption.key` (Base64 16/24/32-byte key, treated as a secret; invalid key fails startup fast). Enable with `encryption.enabled=true` + `encryption.cipher=aesgcm`. Composes with ADR 56 rotation/mixed-cipher reads; no SPI change.
- **Field-level masking** remains available via `PayloadEnricher` (all store types) for cases where only some fields are sensitive or non-JDBC stores are used.
- **Tests**: `AesGcmPayloadCipherTest` (14 — round-trip, semantic security, wrong-key/tamper/garbage fail, key-length validation, Base64 factory) + autoconfig wiring test (`ENC(aesgcm:..)` round-trips through the serializer). JDBC + autoconfigure modules green (BUILD SUCCESS).
- **Docs**: `payload-encryption.md` (AES-GCM as the recommended out-of-the-box path; own-cipher example retargeted to KMS) and `security.md` PII section (encrypt-at-rest config + `PayloadEnricher` masking + TTL). ADR 61.

The operational guidance below remains (manage the key in a secret store; classify the store as PII; constrain TTL).

**Problem.** The store persists upstream responses verbatim. Any sensitive field lands in the backing store (DB/cache) with whatever protection that store has. This may breach data-classification or retention rules.

**Target state.** Sensitive fields are masked or encrypted before storage; the store is encrypted at rest and access-controlled; the failover store carries the same data classification as its source.

**Steps.**
1. Classify each stored payload; identify PII / secret fields.
2. Implement a `PayloadEnricher<T>` that masks or encrypts sensitive fields in `enrichOnStore` and reverses (or leaves masked) in `enrichOnRecover`. Register as a bean (replaces default via `@ConditionalOnMissingBean`).
3. Apply infra controls: encryption-at-rest on the store DB, least-privilege DB credentials, network isolation.
4. Align expiry (`expiryDuration`/`expiryUnit`) with data-retention policy — short TTL for sensitive data; the hourly cleanup scheduler purges expired rows.
5. Document the store in the service's data-protection record.

**Verification.** Inspect a stored row in the DB and confirm sensitive fields are masked/encrypted, not plaintext. Round-trip test confirms recovered payload is usable per design. Security sign-off.

**Owner / Effort.** Service team + data-protection/security. ~1–2 days (enricher impl + infra + sign-off).

---

### A5 — Stale-data UX (Severity: Medium) — ✅ PROVIDED BY DESIGN (verified 2026-06-22)

**Cross-check outcome.** This is **not a library defect** — it is a consumer responsibility, and the library already supplies everything needed to discharge it. No library change required.

**What the library provides (verified):**
- `Referential` (base class) and `ReferentialAware` (interface) in `failover-domain` expose `upToDate` (Boolean), `asOf` (Instant), and a `metadata` bag.
- `DefaultPayloadEnricher` (the default enricher bean) populates these on **every recover** (`DefaultPayloadEnricher.java:51-84`): `setUpToDate(false)` for a recovered value, `setAsOf(<capture timestamp>)`, and exception details into `metadata` — directly on the object the caller receives. On the success path `upToDate=true`.
- Consumer-facing docs already cover this (quickstart, how-it-works, configure-`@Failover`, FAQ).

**Boundary.** The library cannot render UX or make the business stale-vs-error decision — it has no UI/API layer and no knowledge of the consumer's contract. Its responsibility ends at exposing an accurate, automatically-populated staleness signal, which it does. Surfacing `asOf`/`upToDate` in the UI/API and obtaining business sign-off is correctly the consuming microservice's job.

The steps below are therefore **consumer guidance**, not library work.

---

**(Original problem statement — retained for context.)** Recovered data is old. If the consumer presents it as live, users may act on stale information. The recovered payload carries `asOf`/`upToDate`, but surfacing it is the consumer's responsibility.

**Target state.** Every consumer of a failover-protected response surfaces "data as of <timestamp>" (and/or a stale flag) in UI/API, and the business has signed off on stale-over-error.

**Steps.**
1. Expose `asOf`/`upToDate` from the payload to the API contract / UI layer.
2. UI: render a stale indicator + timestamp when `upToDate == false`.
3. API: include `asOf` in the response envelope; document the field for downstream consumers.
4. Obtain explicit business acceptance of stale-over-error per endpoint.

**Verification.** Failure-path test asserts `upToDate=false` and a populated `asOf`. UX review confirms the indicator renders.

**Owner / Effort.** Service + product/UX. ~0.5–1 day per consumer surface.

---

### A6 — Exception-policy decision (Severity: Medium) — ✅ FIXED (2026-06-23)

**Cross-check + fix.** The core mechanism already existed and is sound; the gap was clarity/correctness, not behaviour:
- **Already in place (verified).** Three policies (`rethrow` default / `never_throw` / `custom`); `never_throw` already logged a startup WARN naming the active policy. Outage observability is **policy-independent** — the recover metric (`AdvancedFailoverHandler`) fires before the exception policy runs, so `failover.recovery.outcome.total{outcome=not_recovered}` and `failover.user.impact.total{impact=blocked}` register an outage even when the exception is suppressed.
- **Bug fixed.** `NeverRethrowMethodExceptionPolicy`'s javadoc was copy-pasted from the rethrow policy and wrongly claimed it rethrows when nothing is recovered. Corrected to describe its real always-return-recovered-or-null behaviour and the outage-masking consequence (the implementation was already correct; only the doc lied).
- **Improved.** The `never_throw` startup WARN now explicitly states outages are masked from callers and names the two meters to alert on. `docs/how-to/exception-policy.md` gains a matching "masks outages — alert on metrics" callout.
- **No behaviour/SPI/property change**, so no ADR — this is a documentation-correctness + operator-guidance fix. Tests green (core + autoconfigure BUILD SUCCESS).

Choosing `rethrow` vs `never_throw` remains a deliberate per-service decision (consumer responsibility); the framework now states the trade-off accurately and keeps the outage observable either way.

**Problem.** `failover.exception-policy=never_throw` makes callers always receive recovered-or-null and never see the upstream exception — convenient, but can mask a dependency outage.

**Target state.** The policy is a conscious per-service decision; outages remain observable regardless of policy.

**Steps.**
1. Decide per failover domain: `rethrow` (default — propagate when recovery is empty) vs `never_throw` (always degrade gracefully).
   ```yaml
   failover:
     exception-policy: rethrow   # or never_throw
   ```
2. If `never_throw`: ensure outage visibility via recover metrics (`failover.recovery.outcome.total`, `is-recovered`/`is-recovery-failed` tags from `AdvancedFailoverHandler`) and alert on recovery-driven traffic.
3. Document the rationale in the service runbook.

**Verification.** Test both branches: empty store + `rethrow` ⇒ exception propagates; `never_throw` ⇒ null/handled value returned and recover metric emitted.

**Owner / Effort.** Service team + SRE. ~0.5 day (mostly decision + alert).

---

### A7 — Store DB capacity (Severity: Medium) — ✅ FIXED (2026-06-25)

**Resolution shipped.** Capacity sizing stays an operator task, but the library now gives the missing *observability* lever and the controls to bound growth:
- **Gap.** The `failover.live.entries` gauge existed but was registered only for in-process stores; the JDBC store — where capacity matters — was excluded (to avoid a `COUNT(*)` per scrape), so table growth was invisible through the failover meters.
- **Fix.** `FailoverStoreJdbc` now implements `FailoverStoreSizeAware` via a new `SELECT COUNT(*) WHERE FAILOVER_NAME = ?` (resolver `getCountByNameQuery()`), gated by opt-in `failover.store.jdbc.live-entries-gauge-enabled` (default `false`). When on, `failover.live.entries{name,domain}` reports rows per failover for growth alerting; when off, no `COUNT(*)` ever runs (unchanged default). Flows through the existing decorator chain; not available in multi-tenant mode (routing wrapper isn't size-aware).
- **Existing controls reaffirmed.** Bounded async executor (ADR 57) caps the write blast radius under a failure storm; short TTL + expiry cleanup (with the `EXPIRE_ON` index) bound row count.
- **Tests**: resolver count-query content; JDBC `liveEntryCount` returns counts + `supported` gating (real H2). jdbc + autoconfigure green (BUILD SUCCESS). **ADR 62.**
- **Docs**: a *Capacity planning* section (write-volume formula + sizing + controls + the opt-in gauge) in `store-types.md`, and a *Gauges* table in `observability.md`.

The operational guidance below remains (size the DB for success-path write volume; tune the executor).

**Problem.** With JDBC store, every *successful* call performs an upsert. High-QPS endpoints generate proportional write load that can stress the store DB.

**Target state.** Store DB is sized for the aggregate success-path write throughput with headroom; async executor is tuned to that ceiling.

**Steps.**
1. Estimate writes/sec = success QPS per annotated endpoint × instances.
2. Load-test the store DB at projected peak; confirm upsert latency stays within the async executor's drain rate (else A2 failures appear).
3. Tune: connection pool size, executor capacity, and—where durability isn't required—prefer Caffeine for the hottest endpoints.
4. Consider per-service `table-prefix` / schema isolation if sharing a DB.

**Verification.** Load test at peak; assert zero `store-async-failed` and acceptable DB CPU/IO. JMH micro-benchmarks (ADR 50, profile-gated) for per-call overhead baseline.

**Owner / Effort.** Platform + service team. ~1 day (load test + tuning).

---

### A8 — Annotation placement & self-invocation (Severity: Low) — ✅ FIXED (2026-06-25)

**Resolution shipped.** Misplacement is now caught at boot instead of during an incident:
- **Fix.** `SpringContextFailoverScanner` already discovered every `@Failover` (including interface-declared ones via `findAnnotation`) but never checked advisability. It now emits a `WARN` per discovered failover that **cannot be advised** by the CGLIB proxy, naming the failover + method + the specific reason: annotation only on a supertype/interface (not the concrete method), non-public / `static` / `final` method, or `final` class. Message: *"Failover 'x' on `Foo#bar` will NOT be applied … no effect until fixed."* Discovery/behaviour otherwise unchanged.
- **Self-invocation** is a runtime call-graph property the scanner cannot see statically, so it is **documented** rather than warned (call through the injected bean / split into another bean).
- **No false positives on interface beans.** `@FeignClient`, Spring Data repositories, and `@HttpExchange` clients are interface beans advised by JDK dynamic proxies — interface-level `@Failover` is correct there. The check returns early for interfaces and JDK proxy classes, so those are never warned.
- **Tests**: 7 new (interface-only / final method / static / non-public / final class warn; well-placed concrete method and interface-bean placement do not). Scanner module green (19 tests, BUILD SUCCESS).
- **Docs**: new *Placement* section in `configure-failover-annotation.md` (placement rules table + self-invocation + the startup WARN + the `failover.registered.total` gauge / health indicator). **ADR 63.**

The consumer guidance below remains (annotate concrete impl methods; avoid self-invocation).

**Problem.** `@Failover` on an interface, on a non-public method, or invoked via self-invocation is silently not intercepted (Spring AOP / CGLIB constraint) — failover appears configured but never runs.

**Target state.** All `@Failover` annotations are on proxied concrete methods; no self-invocation path relies on failover; coverage is verified at startup.

**Steps.**
1. Place `@Failover` on concrete public impl methods only.
2. Remove self-invocation of annotated methods (call through the injected bean, or split into a separate bean).
3. Use the startup scanner (`failover-scanner` / `SpringContextFailoverScanner`) output to confirm every intended method is registered.
4. Add a PR-review checklist item.

**Verification.** Startup log lists the expected failover methods. Integration test forces failure on each annotated path and asserts recovery actually triggers.

**Owner / Effort.** Service team. ~0.25 day audit.

---

### A9 — Key-generation stability (Severity: Low) — ✅ FIXED (2026-06-25)

**Cross-check + fix.** The default generator was already stability-aware; the fix is a hot-path refinement plus guidance:
- **Already in place (verified).** `DefaultKeyGenerator` produces stable keys for `String`/`Number`/`Boolean`/primitives, recurses collections/arrays, and uses `toString()` for records/enums/value types (deterministic, restart-stable). For a type with only identity `toString()` it falls back to `ClassName@hashCode` **and warns** that the key is unstable. The `KeyGenerator` SPI contract already mandates deterministic, collision-free, side-effect-free keys.
- **Defect fixed.** That unstable-key WARN was emitted on **every** call (`castToStringValue` is on the hot path) — a log flood on a high-QPS endpoint. Now throttled to **once per (failover, type)** via a dedup set; the diagnostic is preserved, the flood removed.
- **Docs added** (`concepts/key-generation.md`): expanded the per-type table (toString-stable vs identity-hash), and a *Key Stability Requirement* section covering identity hash, **unordered collections** (`Set` iteration-order instability), non-deterministic components, collisions, and **domain key compatibility**.
- **Tests**: +2 (warns once per type across repeated calls; once per distinct type). `failover-core` `DefaultKeyGeneratorTest` green (31 tests, BUILD SUCCESS).
- **No ADR** — log-throttle refinement, not an architectural change (stable-key design predates this; UUID normalisation is ADR 22).

Choosing a stable, collision-free key for unusual arg types remains the consumer's call (custom `KeyGenerator`); the framework keys stably for common types and now flags unstable ones without flooding.

**Problem.** The default `KeyGenerator` derives the store key from method arguments. Unstable args (mutable objects, non-deterministic `toString`, ordering) or colliding args produce wrong-key recovery or cross-entity collisions.

**Target state.** Each failover point uses a stable, collision-free key derived from a well-defined business identifier.

**Steps.**
1. Review args of each annotated method; ensure they uniquely and stably identify the entity.
2. Where args are unsuitable, implement a custom `KeyGenerator` bean and reference it: `@Failover(keyGenerator = "myKeyGen")`.
3. For shared `domain` usage, ensure the single-entity and list endpoints derive compatible keys.

**Verification.** Unit test: same logical entity ⇒ identical key across calls; distinct entities ⇒ distinct keys. Store/recover round-trip returns the correct entity.

**Owner / Effort.** Service team. ~0.25–0.5 day per non-trivial endpoint.

---

### A10 — Scatter/gather adoption (Severity: Low) — ✅ FIXED (2026-06-25)

**Cross-check + fix.** Mostly an adoption-guidance concern; the feature itself is complete and well-tested. One genuine silent-misconfiguration gap was found and closed:
- **Gap.** `ScatterGatherFailoverHandler.recover` enters the scatter path only when `payloadSplitter` is set, so `@Failover(recoverAll=true)` **without** a `payloadSplitter` silently falls back to single-key recover — recover-all never runs, no error. (A `payloadSplitter` naming a missing bean already fails loudly via `PayloadSplitterNotFoundException`; only this combination was silent.)
- **Fix.** `SpringContextFailoverScanner` now WARNs at startup for `recoverAll=true` + blank `payloadSplitter`, naming the failover/method and the fix (complements ADR 63; ADR 64).
- **Already in place (verified).** Partial recovery is observable — `failover.recovery.partial.total{name,method}` plus INFO recovered/missing counts — and `merge` owns the null/partial policy. No change needed there.
- **Docs.** `concepts/scatter-gather.md` gains an *adoption / when-not-to* callout (default to single-key; adopt only for genuine per-entity slicing) and an explicit *recover-all needs a `payloadSplitter`* warning by the trigger table.
- **Tests**: +3 (recoverAll without splitter warns; with splitter no warn; recoverAll=false no warn). Scanner module green (25 tests, BUILD SUCCESS).

Choosing to adopt scatter/gather remains the consumer's call; the framework now flags the one silent misconfiguration and documents the trade-off.

**Problem.** Scatter/gather (`payloadSplitter`, `recoverAll`) is powerful but the most complex feature — per-slice storage, per-slice timeout, parallel dispatch, partial-recovery merge. Misuse adds avoidable complexity.

**Target state.** Scatter/gather is used only where per-entity slicing of a composite result is genuinely required; single-key behavior is the default everywhere else.

**Steps.**
1. Default to single-key `@Failover`; do **not** set `payloadSplitter` unless slicing a composite (list/wrapper) result into per-entity entries is needed.
2. If adopting: implement `PayloadSplitter` (`splitOnStore` / `splitOnRecover` / `merge`); keep expiry consistent across failovers sharing a `domain` (scanner warns on mismatch).
3. Tune parallelism / per-slice timeout (`failover.scatter.parallel`, ADR 24 / ADR 38) deliberately.
4. Document partial-recovery semantics for consumers (some slices may be stale/missing).

**Verification.** Tests for split-on-store, split-on-recover, merge, partial recovery, and per-slice timeout (patterns in `ScatterGatherFailoverHandlerTest` / scatter test communities).

**Owner / Effort.** Service team. Effort only when adopted — ~1–2 days for a non-trivial splitter.

---

### Remediation Sequencing

1. **Before any prod traffic:** A1 (durable store), A3 (allowlist), A4 (PII), A2 (async monitoring) — security + data-safety gates.
2. **Before go-live sign-off:** A5 (stale UX), A6 (policy), A7 (capacity).
3. **Hardening / per-endpoint:** A8, A9, A10 — verify as endpoints are onboarded.
