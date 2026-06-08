---
icon: material/brain
---

# Concepts

Core ideas that explain how Failover stores, recovers, and expires referential data.

```mermaid
classDiagram
    class FailoverAspect {
        +failoverAround(ProceedingJoinPoint)
    }
    class FailoverHandler {
        +store(Failover, args, payload) T
        +recover(Failover, args, clazz, cause) T
        +clean()
    }
    class FailoverStore {
        +store(ReferentialPayload)
        +find(name, key) Optional
        +delete(ReferentialPayload)
        +cleanByExpiry(Instant)
    }
    class KeyGenerator {
        +key(Failover, List~Object~) String
    }
    class ExpiryPolicy {
        +computeExpiry(Failover) Instant
        +isExpired(Failover, ReferentialPayload) boolean
    }
    class PayloadEnricher {
        +enrichOnStore(...) ReferentialPayload
        +enrichOnRecover(...) ReferentialPayload
    }

    FailoverAspect --> FailoverHandler : delegates to
    FailoverHandler --> FailoverStore : store / find
    FailoverHandler --> KeyGenerator : derive key
    FailoverHandler --> ExpiryPolicy : compute / check expiry
    FailoverHandler --> PayloadEnricher : enrich on store/recover
```

---

| Concept | Description |
|---|---|
| [How It Works](how-it-works.md) | End-to-end store/recover lifecycle with sequence diagrams |
| [Expiry Policies](expiry.md) | TTL computation, SpEL expressions, custom ExpiryPolicy |
| [Key Generation](key-generation.md) | Three-layer key architecture, UUID hashing, custom generators |
| [Scatter / Gather](scatter-gather.md) | Per-entity storage for collection-returning methods |
| [Domain Grouping](domain.md) | Cross-failover store sharing via the `domain` attribute |

---

## Next Steps

- [How It Works](how-it-works.md) — start here for the full picture
- [Getting Started](../getting-started/index.md) — add Failover to your project
