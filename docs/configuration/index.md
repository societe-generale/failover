---
icon: material/tune
---

# Configuration

All failover properties are bound to the `failover.*` prefix — YAML, properties files, or environment variables.

## Property hierarchy

```mermaid
mindmap
  root((failover))
    package-to-scan
      Required
    store
      type
        jdbc
        caffeine
        inmemory
      jdbc
        table-prefix
        datasource-url
      caffeine
        spec
    exception-policy
      throw
      never_throw
    multi-tenant
      enabled
      isolation-strategy
        TABLE_PREFIX
        SCHEMA
```

## Sections

| Section | Description |
|---|---|
| [Properties Reference](properties-reference.md) | Complete list of every `failover.*` property |
| [Store Types](store-types.md) | Choosing and configuring the backing store |
| [Multi-Tenant](multi-tenant.md) | Isolating stores by tenant (TABLE_PREFIX or SCHEMA) |

Start with [Properties Reference](properties-reference.md) for a quick overview, then drill into the specific section you need.
