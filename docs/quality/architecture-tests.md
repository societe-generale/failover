---
icon: material/sitemap
---

# Architecture Tests

> Audit finding **T-3** · ADR **45**

The decorator architecture relies on invariants the compiler cannot enforce. `FailoverArchitectureTest`
(in `failover-spring-boot-autoconfigure`) uses [ArchUnit](https://www.archunit.org/) to assert them
on every build. The autoconfigure module is chosen because it is the only module with **every**
`failover-*` artifact on its classpath, so a single `@AnalyzeClasses` import covers the whole library
(test classes are excluded via `ImportOption.DoNotIncludeTests`).

## Enforced rules

| Rule                                                             | Why                                                                                                                                                                                                                                                                                                                                                                                 |
|------------------------------------------------------------------|-------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `FailoverStoreAsync` must not depend on `java.lang.ThreadLocal`  | Tenant/security context is bound on the **calling thread** before the executor boundary ([ADR 19](../adr/adr.md#adr-19-failoverstoreasync-explicit-taskexecutor-replacing-async), [ADR 20](../adr/adr.md#adr-20-multitenantfailoverstore-outermost-per-tenant-routing-decorator)). Reading a ThreadLocal inside an async lambda would bind to the wrong context on a pooled thread. |
| Every concrete `FailoverStore` carries `FailoverStore` in its name | Keeps the persistence layer discoverable and consistent (`FailoverStoreJdbc`, `DefaultFailoverStore`, …).                                                                                                                                                                                                                                                                          |
| The `com.societegenerale.failover.(*)` slices are free of cycles | Catches accidental back-references that would erode the layered decorator architecture.                                                                                                                                                                                                                                                                                             |

## Deferred: the split-package rule

The audit's split-package finding (**A-1** — `failover-lookup` and the four store modules sharing
packages with `failover-core`) is a **Phase 4 breaking change** and is *not* enforced here yet. The
no-`ThreadLocal` rule is deliberately targeted by class name rather than package precisely because the
store classes currently share a package across modules. Once A-1 is resolved, a package-based layering
rule can be added.

## Verifying a rule bites

Temporarily referencing a `ThreadLocal` inside `FailoverStoreAsync` makes
`async_store_must_not_depend_on_threadlocal` fail — a quick way to confirm the rule is active before
relying on it.
