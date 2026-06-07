---
icon: material/puzzle-outline
---

# Modules

Failover is split into focused modules. The starter includes all of them. Use individual modules for fine-grained dependency control.

| Module | Description |
|---|---|
| [Core](core.md) | `FailoverHandler`, `KeyGenerator`, `ExpiryPolicy`, `PayloadEnricher`, `ContextPropagator` abstractions |
| [Observability](observability.md) | Scanner, observer, MDC logging, Micrometer meters, health indicator — full observability stack |
| [JDBC Store](store-jdbc.md) | Persistent store backed by H2, PostgreSQL, MySQL, MariaDB, Oracle, or SQL Server |
| [Caffeine Store](store-caffeine.md) | In-process cache store backed by Caffeine |
| [Async Store](store-async.md) | Non-blocking write decorator using a virtual-thread executor |
| [Multi-Tenant Store](store-multitenant.md) | Per-tenant routing via `TABLE_PREFIX` or `SCHEMA` isolation strategy |
| [Resilience](execution-resilience.md) | Resilience4j circuit-breaker integration for upstream calls |
| [Scheduler](scheduler.md) | Expiry-cleanup and observable schedulers |

Start with [Core](core.md) to understand the central abstractions, then add store and execution modules as needed.
