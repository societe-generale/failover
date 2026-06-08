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

<div class="grid cards" markdown>

-   :material-arrow-decision-outline:{ .lg .middle } **How It Works**

    ---

    End-to-end store/recover lifecycle with call-flow, state machine, and sequence diagrams.

    [:octicons-arrow-right-24: Learn the lifecycle](how-it-works.md)

-   :material-timer-outline:{ .lg .middle } **Expiry Policies**

    ---

    TTL computation, SpEL expressions, payload-driven expiry, and custom `ExpiryPolicy` beans.

    [:octicons-arrow-right-24: Explore expiry](expiry.md)

-   :material-key-variant:{ .lg .middle } **Key Generation**

    ---

    Three-layer key architecture — method name, annotation name, and hashed arguments.

    [:octicons-arrow-right-24: Understand key derivation](key-generation.md)

-   :material-scatter-plot:{ .lg .middle } **Scatter / Gather**

    ---

    Per-entity storage for collection-returning methods — store and recover individual slices.

    [:octicons-arrow-right-24: Learn scatter/gather](scatter-gather.md)

-   :material-domain:{ .lg .middle } **Domain Grouping**

    ---

    Share store entries across multiple `@Failover` annotations via the `domain` attribute.

    [:octicons-arrow-right-24: Explore domains](domain.md)

</div>

---

## Next Steps

- [How It Works](how-it-works.md) — start here for the full picture
- [Getting Started](../getting-started/index.md) — add Failover to your project
