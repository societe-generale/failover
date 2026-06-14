---
icon: material/format-list-bulleted
---

# Properties Reference

All properties are prefixed with `failover`. There are no mandatory properties — the framework starts with production-safe defaults.

---

## Root Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `failover.enabled` | `boolean` | `true` | Enable or disable the entire failover framework. Set `false` to bypass all interception without removing annotations. |
| `failover.type` | `FailoverType` | `BASIC` | Execution strategy. `BASIC` uses try/catch; `RESILIENCE` wraps upstream calls in a Resilience4j circuit-breaker; `CUSTOM` for your own `FailoverExecution` bean. |
| `failover.exception-policy` | `ExceptionPolicy` | `RETHROW` | Behaviour when recovery finds nothing. `RETHROW` re-throws the original upstream exception; `NEVER_THROW` returns `null` (or the `RecoveredPayloadHandler` result); `CUSTOM` for your own `MethodExceptionPolicy` bean. |

---

## Store Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `failover.store.type` | `StoreType` | `INMEMORY` | Backing store. `INMEMORY` (dev/test only — not persistent), `CAFFEINE`, `JDBC`, `CUSTOM`. |
| `failover.store.async` | `boolean` | `true` | Offload write operations (`store`, `delete`, `cleanByExpiry`) to a background virtual-thread executor. `find` is always synchronous. Set `false` when using the JDBC `SCHEMA` multi-tenant strategy. |
| `failover.store.allowed-payload-classes` | `List<String>` | `[]` | Deserialization allowlist for serializing stores (JDBC). Exact class names or package prefixes. **Additive** to the secure-by-default auto-allowlist derived from discovered `@Failover` payload packages — set only for classes the scanner cannot infer. See [Security](../support/security.md). |

### JDBC Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `failover.store.jdbc.table-prefix` | `String` | `""` | Prefix prepended to `FAILOVER_STORE` to form the table name. `MYAPP_` → table `MYAPP_FAILOVER_STORE`. Validated to contain only letters, digits, underscores, and dot-separated qualifiers. |

### Multi-Tenant Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `failover.store.multitenant.enabled` | `boolean` | `false` | Enable multi-tenant store routing. |
| `failover.store.multitenant.strategy` | `JdbcMultiTenantStrategy` | `TABLE_PREFIX` | `TABLE_PREFIX` — separate table per tenant. `SCHEMA` — separate schema per tenant (requires custom `TenantStoreFactory` bean). |
| `failover.store.multitenant.default-tenant` | `String` | `""` | Fallback tenant ID when `TenantResolver` returns `null`. Throws `FailoverStoreException` if blank and resolver returns `null`. |
| `failover.store.multitenant.strict` | `boolean` | `false` | In `TABLE_PREFIX` mode, reject a tenant that is not present in `tenants` (throws `FailoverStoreException`) instead of silently routing it to the shared global table. When `false`, such a tenant is allowed with a one-time `WARN`. The `default-tenant` is exempt. |
| `failover.store.multitenant.tenants` | `Map<String, TenantConfig>` | `{}` | Per-tenant configuration. Key = tenant ID. Each entry can override `table-prefix` (TABLE_PREFIX strategy) or `schema` (SCHEMA strategy). |

---

## Scheduler Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `failover.scheduler.enabled` | `boolean` | `true` | Enable or disable both schedulers. |
| `failover.scheduler.report-cron` | `String` | `"0 0 0 * * *"` | Cron expression for the observable report publisher. Default: daily at midnight. |
| `failover.scheduler.cleanup-cron` | `String` | `"0 0 * * * *"` | Cron expression for the expiry-cleanup scheduler. Default: every hour. |

---

## Scatter Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `failover.scatter.parallel` | `boolean` | `true` | Dispatch scatter/gather slices in parallel using virtual threads. Set `false` for sequential processing. |
| `failover.scatter.timeout` | `Duration` | `10s` | Per-slice timeout for the parallel path (ignored when `parallel=false`). On timeout a recover slice is treated as not recovered, and a store slice surfaces the timeout (isolated by the execution layer) — a hung slice never blocks the caller indefinitely. Empty/null = wait indefinitely. |

---

## Full Example

```yaml title="application.yml"
failover:
  enabled: true
  type: basic                        # basic | resilience | custom
  exception-policy: rethrow          # rethrow | never_throw | custom

  store:
    type: jdbc                       # inmemory | caffeine | jdbc | custom
    async: true
    allowed-payload-classes: []      # additive; auto-derived from @Failover payload packages
    jdbc:
      table-prefix: MYAPP_
    multitenant:
      enabled: false
      strategy: table_prefix
      default-tenant: ""
      strict: false                  # reject tenants absent from the tenants map
      tenants:
        acme:
          table-prefix: ACME_
        globex:
          table-prefix: GLOBEX_

  scheduler:
    enabled: true
    report-cron: "0 0 0 * * *"       # daily midnight
    cleanup-cron: "0 0 * * * *"      # every hour

  scatter:
    parallel: true
    timeout: 10s                     # per-slice timeout for the parallel path
```

---

## Next Steps

- [Store Types](store-types.md) — choose the right backing store for your environment
- [Multi-Tenant](multi-tenant.md) — per-tenant store routing
- [Modules](../modules/index.md) — module responsibilities and dependencies
