# Properties Reference

All properties are prefixed with `failover`.

---

## Root Properties

| Property | Type | Default | Description |
|---|---|---|---|
| `failover.enabled` | `boolean` | `true` | Enable or disable the entire failover framework. Set `false` to bypass all interception without removing annotations. |
| `failover.package-to-scan` | `String` | _(required)_ | Base package the framework scans for `@Failover` annotations. **Mandatory when `failover.enabled=true`.** Example: `com.example.myapp` |
| `failover.type` | `FailoverType` | `BASIC` | Failover execution strategy. `BASIC` uses try/catch; `RESILIENCE` uses Resilience4j circuit-breaker; `CUSTOM` for your own `FailoverExecution` bean. |
| `failover.exception-policy` | `ExceptionPolicy` | `RETHROW` | Behaviour when recovery fails. `RETHROW` re-throws the original exception; `NEVER_THROW` returns `null` (or `RecoveredPayloadHandler` result); `CUSTOM` for your own bean. |

---

## Store Properties (`failover.store.*`)

| Property | Type | Default | Description |
|---|---|---|---|
| `failover.store.type` | `StoreType` | `INMEMORY` | Backing store implementation. `INMEMORY` (dev/test only), `CAFFEINE`, `JDBC`, `CUSTOM`. |
| `failover.store.async` | `boolean` | `true` | Offload write operations (`store`, `delete`, `cleanByExpiry`) to a background `TaskExecutor`. `find` is always synchronous. Set `false` when using the JDBC SCHEMA multi-tenant strategy. |

### JDBC Properties (`failover.store.jdbc.*`)

| Property | Type | Default | Description |
|---|---|---|---|
| `failover.store.jdbc.table-prefix` | `String` | `""` | Prefix prepended to `FAILOVER_STORE` to form the table name. Example: `MYAPP_` → table `MYAPP_FAILOVER_STORE`. |

### Multi-Tenant Properties (`failover.store.multitenant.*`)

| Property | Type | Default | Description |
|---|---|---|---|
| `failover.store.multitenant.enabled` | `boolean` | `false` | Enable multi-tenant store routing. |
| `failover.store.multitenant.strategy` | `JdbcMultiTenantStrategy` | `TABLE_PREFIX` | `TABLE_PREFIX` — separate table per tenant. `SCHEMA` — separate schema/database (requires custom `TenantStoreFactory` bean). |
| `failover.store.multitenant.default-tenant` | `String` | `""` | Fallback tenant ID when the `TenantResolver` returns `null`. Throws `FailoverStoreException` if blank and resolver returns `null`. |
| `failover.store.multitenant.tenants` | `Map<String, TenantConfig>` | `{}` | Per-tenant configuration. Key is the tenant ID. Each entry can override `table-prefix`. |

---

## Scheduler Properties (`failover.scheduler.*`)

| Property | Type | Default | Description |
|---|---|---|---|
| `failover.scheduler.enabled` | `boolean` | `true` | Enable or disable both schedulers. |
| `failover.scheduler.report-cron` | `String` | `"0 0 0 * * *"` | Cron expression for the report-publisher scheduler. Default: daily at midnight. |
| `failover.scheduler.cleanup-cron` | `String` | `"0 0 * * * *"` | Cron expression for the expiry-cleanup scheduler. Default: every hour. |

---

## Scatter Properties (`failover.scatter.*`)

| Property | Type | Default | Description |
|---|---|---|---|
| `failover.scatter.parallel` | `boolean` | `true` | Dispatch scatter/gather slices in parallel using virtual threads. Set `false` for sequential processing. |

---

## Full Example

```yaml title="application.yml"
failover:
  enabled: true
  package-to-scan: com.example.myapp
  type: basic
  exception-policy: rethrow

  store:
    type: jdbc
    async: true
    jdbc:
      table-prefix: MYAPP_
    multitenant:
      enabled: false

  scheduler:
    enabled: true
    report-cron: "0 0 0 * * *"
    cleanup-cron: "0 0 * * * *"

  scatter:
    parallel: true
```

---

## Environment Variable Equivalents

Spring Boot maps kebab-case properties to `UPPER_SNAKE_CASE` environment variables:

| Property | Environment variable |
|---|---|
| `failover.enabled` | `FAILOVER_ENABLED` |
| `failover.package-to-scan` | `FAILOVER_PACKAGE_TO_SCAN` |
| `failover.store.type` | `FAILOVER_STORE_TYPE` |
| `failover.store.jdbc.table-prefix` | `FAILOVER_STORE_JDBC_TABLE_PREFIX` |
| `failover.scheduler.cleanup-cron` | `FAILOVER_SCHEDULER_CLEANUP_CRON` |
