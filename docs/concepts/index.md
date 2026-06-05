---
icon: material/brain
---

# Concepts

Core ideas behind the Failover framework.

## How failover works

```mermaid
flowchart LR
    C([Your Code]) --> A{FailoverAspect}
    A -->|invoke| U([Upstream API])
    U -- "✅ success" --> ST[Store payload + TTL]
    ST --> R1([return upToDate=true])
    U -- "❌ failure" --> Q[Query store]
    Q -- "fresh entry" --> R2([return upToDate=false])
    Q -- "expired / missing" --> R3([re-throw])
```

## Entry lifecycle

```mermaid
stateDiagram-v2
    direction LR
    [*]     --> Live    : first successful call
    Live    --> Live    : success — TTL refreshed
    Live    --> Stale   : upstream fails, entry still fresh
    Stale   --> Live    : upstream recovers
    Stale   --> Expired : TTL exceeded
    Expired --> [*]     : re-throw / null
```

## Topic reference

| Concept | Description |
|---|---|
| [How It Works](how-it-works.md) | End-to-end lifecycle: interceptor → handler → store → recover |
| [Expiry Policies](expiry.md) | TTL computation, SpEL expressions, custom policy |
| [Key Generation](key-generation.md) | How store keys are derived from method arguments |
| [Scatter / Gather](scatter-gather.md) | Per-entity storage for collection-returning methods |

Understanding the [store/recover lifecycle](how-it-works.md) first makes everything else click.
