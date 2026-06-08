---
icon: material/office-building-outline
---

# Multi-Tenant Store

`MultiTenantFailoverStore` wraps the base store and routes each operation to the correct tenant's table or schema based on a `TenantResolver`.

---

## Dependency

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-store-multitenant</artifactId>
    <version>3.0.0</version>
</dependency>
```

---

## Store Assembly Position

`MultiTenantFailoverStore` sits between `AsyncFailoverStore` and the base store:

```
AsyncFailoverStore
  └── MultiTenantFailoverStore    ← routes by tenant
        └── JdbcFailoverStore (one per tenant)
```

---

## Available Extension Points

| SPI | Purpose | How to override |
|---|---|---|
| `TenantResolver` | Returns the current tenant ID | Declare `@Bean` |
| `TenantStoreFactory` | Creates a `FailoverStore` per tenant | Declare `@Bean` (required for SCHEMA strategy) |

### TenantResolver

```java
@Component
public class HeaderTenantResolver implements TenantResolver {

    private final HttpServletRequest request;

    @Override
    public String resolve() {
        return request.getHeader("X-Tenant-ID");
    }
}
```

### TenantStoreFactory (SCHEMA strategy)

```java
@Component
public class MultiDataSourceStoreFactory<T> implements TenantStoreFactory<T> {

    @Override
    public FailoverStore<T> create(String tenantId) {
        DataSource ds = resolveTenantDataSource(tenantId);
        return new JdbcFailoverStore<>(new JdbcTemplate(ds), "FAILOVER_STORE");
    }
}
```

---

## Configuration Reference

See [Multi-Tenant Configuration](../configuration/multi-tenant.md) for full YAML examples and table DDL.

---

## Next Steps

- [Multi-Tenant Configuration](../configuration/multi-tenant.md) — TABLE_PREFIX and SCHEMA setup
- [Async Store](store-async.md) — interaction with async writes
