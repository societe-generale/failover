---
icon: material/book-open-variant
---

# API Reference

Public API surface for all Failover modules. Full Javadoc available in [Java Docs](javadocs.md).

## Public API Overview

### failover-domain

| Type | Description |
|---|---|
| `@Failover` | Method-level annotation. Declares a failover point with name, expiry, key generator, expiry policy, and optional payload splitter. |
| `Referential` | Abstract base class. Extend to get `upToDate`, `asOf`, `metadata` populated automatically on recovery. |
| `ReferentialAware` | Interface alternative to `Referential`. Implement to receive metadata without inheriting from an abstract class. |
| `Metadata` | Container for additional recovery metadata. |

### failover-core

| Type | Description |
|---|---|
| `FailoverHandler<T>` | Central handler: `store`, `recover`, `clean`. |
| `FailoverStore<T>` | Store abstraction: `store`, `find`, `delete`, `cleanByExpiry`. |
| `KeyGenerator` | Derives a store key from method arguments. |
| `ExpiryPolicy<T>` | Computes and checks TTL. |
| `PayloadEnricher<T>` | Populates metadata on domain objects at store/recover time. |
| `RecoveredPayloadHandler` | Post-recovery payload transformer. |
| `ContextPropagator` | Captures and restores thread-local context for async dispatch. |
| `FailoverObserver` | Observes all registered `@Failover` configurations and publishes metrics. |
| `ObservablePublisher` | SPI for publishing failover metrics to external sinks. |

### failover-store-multitenant

| Type | Description |
|---|---|
| `TenantResolver` | Implement and declare as a bean to provide the current tenant ID. |
| `TenantContext` | Thread-local holder for tenant ID. |
| `TenantContextPropagator` | `ContextPropagator` implementation for tenant context. |
| `TenantStoreFactory<T>` | Creates a `FailoverStore` per tenant. |
