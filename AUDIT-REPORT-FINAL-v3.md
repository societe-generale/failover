# Failover Library — Final Audit (v3)

**Date:** 2026-06-26
**Reviewer:** Anand Manissery (assisted)
**Scope:** Whole-codebase verification pass. Re-confirms the v2 remediation register (A1–A10) actually landed in source, validates the build is green, and **extends coverage to the failover dashboard** (cluster / shared-store tier) — added after v2 and not covered there.
**Method:** Knowledge-graph entry point (`graphify-out/GRAPH_REPORT.md`) grounded against source. Full `mvn clean verify` run (exit 0). Direct read of the new dashboard cluster surface.

---

## Executive Summary

| Dimension | Rating | Verdict |
|---|---|---|
| v2 remediations (A1–A10) | ✅ Verified in source | Every claimed fix is present in code, not just documented. |
| Build & tests | ✅ Green | `mvn clean verify` exit 0; 1351 `@Test` methods, 135 test classes, 19 modules. |
| Dashboard security | ✅ Secure-by-default | `enabled=false` default; role gate on `base-path/**`; `allowInsecure` refused under `prod`; strict CSP. |
| New cluster/shared-store tier | ✅ Low risk | Write (ingest) endpoint is behind the same access gate; one **Low** integrity note (D2). |
| SQL safety (new module) | ✅ Safe | Snapshot table prefix validated `[A-Za-z0-9_]*` before concatenation. |

**Overall:** No new high/medium defects. The library is production-ready subject to the same operational gates v2 named (durable store, allowlist, PII, async-write alerting). The dashboard is correctly secure-by-default. One **Low** note on cluster-snapshot ingest integrity (D2). Codebase is consistent with its documentation — claimed fixes are real.

---

## 1. Verification of v2 Remediation Register (A1–A10)

Each v2 item was re-checked **against source**, not against the v2 report's own prose. Result: all present.

| # | v2 Item | Source evidence (verified this pass) | Status |
|---|---|---|---|
| A1 | Durable-store WARN + docs | InMemory/Caffeine non-durable WARNs in `FailoverStoreAutoConfiguration`; store-decision docs. | ✅ Verified |
| A2 | Async-write submit-rejection metric | `FailoverStoreAsync` `submit(...)` helper emits `failover.store.async.failed` on `RejectedExecutionException`. | ✅ Verified |
| A3 | Strict deserialization allowlist | `strictAllowlist`/`strict-allowlist` threaded through serializer (3 files). Empty allowlist + strict ⇒ deny-all. | ✅ Verified |
| A4 | AES-GCM encryption-at-rest | `AesGcmPayloadCipher.java` present (id `aesgcm`); auto-registered from `failover.store.jdbc.encryption.key`. | ✅ Verified |
| A5 | Stale-data signal (by design) | `Referential`/`ReferentialAware` + `DefaultPayloadEnricher` populate `upToDate`/`asOf` on recover. | ✅ Verified |
| A6 | Exception-policy clarity | `never_throw` WARN + corrected `NeverRethrowMethodExceptionPolicy` javadoc; policy-independent outage metrics. | ✅ Verified |
| A7 | JDBC live-entries gauge | `FailoverStoreSizeAware` + opt-in `live-entries` gauge (13 source refs). | ✅ Verified |
| A8 | Scanner advisability WARN | `SpringContextFailoverScanner` warns on `@Failover` that cannot be advised (interface/final/static/non-public). | ✅ Verified |
| A9 | Key-stability WARN throttle | `DefaultKeyGenerator` warns once per (failover,type); test coverage present. | ✅ Verified |
| A10 | recoverAll-without-splitter WARN | `warnIfInvalidScatterConfig` in scanner (commit `63014b79`). | ✅ Verified |

**Conclusion:** The v2 register is trustworthy. No item was documentation-only.

---

## 2. Build & Test Health

- `mvn clean verify` — **exit 0** (full reactor, real H2 integration tests).
- **1351** `@Test` methods across **135** test classes, **19** modules.
- Quality gates active per project config: JaCoCo aggregate coverage gate, PIT mutation testing (95% threshold), Testcontainers dialect CI job (H2/Postgres/MySQL/MariaDB/Oracle).

No flaky/failed modules in this run.

---

## 3. New Surface Since v2 — Failover Dashboard (cluster / shared-store)

v2 did not audit the dashboard. Two things landed after it: dashboard **cluster support** (commit `1bfc57b3`) and the optional **`failover-dashboard-snapshotstore-jdbc`** module. Audited here.

### 3.1 Architecture (read-only by design)

The dashboard is self-contained (does **not** depend on `failover-spring-boot-autoconfigure`), reads globals from `Environment`, and surfaces `FailoverScanner` config + `failover.*` meters. It adds **no new instrumentation**. Metric source modes: `local` (in-process registry), `prometheus` (read-only `/api/v1/query`), `shared-store` (peers push KPI snapshots, dashboard aggregates in memory; optional JDBC durability).

### 3.2 Security posture — ✅ secure-by-default (verified)

| Control | Evidence |
|---|---|
| Master switch off by default | `DashboardProperties.enabled` `@DefaultValue("false")` — nothing mapped/served until explicit opt-in (`DashboardProperties.java:63`). |
| Access gate | When Spring Security is present, `base-path/**` is gated behind role `FAILOVER_ADMIN`. The cluster **ingest** endpoint lives under `base-path/api/cluster`, so it is covered by the same gate (`ClusterSnapshotController.java:37`). |
| Insecure escape hatch is fenced | `allowInsecure=true` starts with a loud WARN and is **refused outright under the `prod` profile** (`DashboardProperties.java:116-131`). |
| Strict CSP on every request | `DashboardExposureInterceptor` sets a static-only CSP (`script-src 'self'`, `object-src 'none'`, no inline/eval; Chart.js vendored) (`DashboardExposureInterceptor.java:45-47`). |
| Exposure narrowing | Per-endpoint `exposure.include` returns `404` for non-included endpoints without touching beans (`DashboardExposureInterceptor.java:61-65`). |
| SQL-injection safety (new module) | Snapshot table prefix validated `[A-Za-z0-9_]*` before being concatenated into the table name (`SnapshotStoreJdbc.java:175-176`). |

### 3.3 Findings

| # | Finding | Severity | Detail / Action |
|---|---|---|---|
| **D1** | Dashboard secure-by-default confirmed | ✅ Info | Off by default, role-gated, prod refuses insecure mode, strict CSP, validated SQL prefix. No action. |
| **D2** | Cluster-snapshot **ingest** is a write endpoint | ⚠️ Low | `POST base-path/api/cluster/snapshot` upserts a peer-pushed `ClusterSnapshot` into the `SnapshotStore` (`ClusterSnapshotController.java:47-51`). Integrity relies on the access gate + a trusted network. A spoofed/compromised peer could skew **aggregate KPIs only** — blast radius is observability, not the failover data path or the business call. **Action:** in `shared-store` mode, ensure the ingest path is on a trusted network and behind the security gate (do not run `allowInsecure` outside dev); prefer `prometheus` mode where a TSDB already owns auth. |
| **D3** | Shared-store is a small-cluster tier, in-memory by default | ✅ Info (by design) | Supported for ≤ ~10 instances (`SharedStore.maxInstances=10`, warns beyond); default store is in-memory (lost on restart) — it is bounded trend history, not a TSDB. Use the JDBC snapshot store or `prometheus` mode for larger / durable deployments. Documented. |

No high/medium dashboard defects.

---

## 4. Core Library — Re-confirmation (unchanged from v2)

The core safety model is intact and re-verified at the source level:

- A failover bug never breaks the real call (store/recover errors caught and ignored; only the failover flow is affected).
- `Error` (OOM/StackOverflow) is never converted to a recoverable exception — recovery does not run on a dying JVM (`FailoverAspect`).
- `find` returns a defensive copy; callers mutate `upToDate`/`asOf` without corrupting stored data.
- Async writes keep the caller thread free; `find` stays synchronous and runs only on the failure path.
- Resilience mode short-circuits a failing upstream via a per-name circuit breaker, reducing load during an outage.

The three production gates from v2 remain consumer responsibilities (mechanisms shipped):
1. Choose a **durable store** (JDBC) for multi-instance / restart survival.
2. Register stored types on the **deserialization allowlist**; enable `strict-allowlist` in prod.
3. Handle **PII** (AES-GCM at rest and/or `PayloadEnricher` masking); align TTL to retention.
4. Alert on **`failover.store.async.failed`**.

---

## 5. Consolidated Action Register (v3)

| # | Area | Severity | Status |
|---|---|---|---|
| A1–A10 | v2 register | — | ✅ Verified present in source (see §1) |
| D1 | Dashboard secure-by-default | Info | ✅ No action |
| D2 | Cluster-snapshot ingest integrity | Low | Trusted network + access gate in `shared-store` mode; prefer `prometheus` for durable/auth'd aggregation |
| D3 | Shared-store cluster ceiling / durability | Info | Use JDBC snapshot store or `prometheus` beyond small clusters |

**Gating items before production:** unchanged from v2 (durable store, allowlist-strict, PII encryption, async-write alerting). No new gate introduced by this pass.

---

## Appendix — Evidence Index (v3 additions)

| Claim | File:Line |
|---|---|
| Dashboard off by default | `failover-dashboard/.../config/DashboardProperties.java:63` |
| Access gate / `allowInsecure` refused under prod | `DashboardProperties.java:116-131` |
| Cluster ingest endpoint (write) under access gate | `failover-dashboard/.../web/ClusterSnapshotController.java:37-51` |
| Strict CSP + exposure narrowing | `failover-dashboard/.../web/DashboardExposureInterceptor.java:45-65` |
| Snapshot table-prefix SQL validation | `failover-dashboard-snapshotstore-jdbc/.../SnapshotStoreJdbc.java:175-176` |
| AES-GCM cipher (A4) | `failover-store-jdbc/.../AesGcmPayloadCipher.java` |
| Scanner advisability + scatter WARN (A8/A10) | `failover-scanner/.../SpringContextFailoverScanner.java` |
| Key-stability throttle (A9) | `failover-core/.../key/DefaultKeyGenerator.java` |
| Build green | `mvn clean verify` exit 0 |
