---
icon: material/sync
---

# Concurrency Tests

> Audit finding **T-2** · ADR **44**

Two threading invariants are central to correctness and are covered by deterministic contention
tests that run as part of the **default build** (no Docker, no flakiness).

## Multi-tenant store routing

`MultiTenantFailoverStore` builds one store per tenant lazily via
`ConcurrentHashMap.computeIfAbsent` (see [ADR 20](../adr/adr.md#adr-20-multitenantfailoverstore-outermost-per-tenant-routing-decorator)).
If first access for a tenant raced across threads and built the store more than once, tenants could
end up writing to divergent store instances.

`MultiTenantFailoverStoreConcurrencyTest` proves this cannot happen:

- A `CountDownLatch` start-gate releases **64 threads simultaneously** against the *same* tenant; a
  counting `TenantStoreFactory` asserts `create()` ran **exactly once**, and all 64 writes are found.
- A second case drives **4 tenants × 32 threads** and asserts exactly 4 factory invocations — one
  store per tenant, with no cross-tenant leakage.

## Async store executor path

`FailoverStoreAsync` offloads writes to a `TaskExecutor` and swallows executor-side exceptions so a
store failure never breaks the business call (see [ADR 19](../adr/adr.md#adr-19-failoverstoreasync-explicit-taskexecutor-replacing-async)).
Under load, every submitted write must still be applied exactly once, and a failure must surface as
the `store-async-failed` metric ([ADR 41](../adr/adr.md#adr-41-async-store-failure-metric-visibility-for-a-silently-degraded-layer))
rather than vanish.

`FailoverStoreAsyncConcurrencyTest` proves both:

- **500 concurrent** `store()` submissions through a real `ThreadPoolTaskExecutor` — the executor is
  drained via `awaitTermination` (no polling library) and every write is asserted to have landed
  exactly once.
- An injected failing delegate triggers the `store-async-failed` metric on the `ObservablePublisher`.

!!! tip "Deterministic, not timing-based"
    Draining is done by shutting the executor and joining it, so these tests assert a *completed*
    state rather than sleeping or polling — no `Thread.sleep`, no `await` library, no flakiness.
