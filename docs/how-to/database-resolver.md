---
icon: material/database-settings-outline
---

# Database Resolver

`DatabaseResolver` controls which `DataSource` the JDBC store uses for upsert dialect detection. Override it when you use the multi-tenant `SCHEMA` strategy and need to route to a per-tenant `DataSource`.

---

## Interface

```java
public interface DatabaseResolver {
    String resolve(DataSource dataSource);
}
```

Returns a database product name string (e.g. `"H2"`, `"PostgreSQL"`, `"MySQL"`, `"Oracle"`, `"Microsoft SQL Server"`). The JDBC store uses this to pick the correct upsert SQL dialect.

---

## When to Use

The default `DatabaseResolver` calls `dataSource.getConnection().getMetaData().getDatabaseProductName()`. Override it when:

- You use a connection pool that does not reliably expose product metadata.
- You know the database product at configuration time and want to avoid a metadata lookup.
- You test with H2 but deploy on PostgreSQL and need separate dialect resolution per environment.

---

## Step 1 — Implement DatabaseResolver

```java title="FixedDatabaseResolver.java"
@Component
public class FixedDatabaseResolver implements DatabaseResolver {

    @Value("${failover.database-product:}")
    private String product;

    @Override
    public String resolve(DataSource dataSource) {
        if (!product.isBlank()) return product;
        try (Connection c = dataSource.getConnection()) {
            return c.getMetaData().getDatabaseProductName();
        } catch (SQLException e) {
            throw new FailoverStoreException("Cannot resolve database product", e);
        }
    }
}
```

---

## Supported Product Names

| Returned string | Dialect used |
|---|---|
| `H2` | `MERGE INTO ... KEY (...)` |
| `PostgreSQL` | `INSERT ... ON CONFLICT DO UPDATE` |
| `MySQL` / `MariaDB` | `INSERT ... ON DUPLICATE KEY UPDATE` |
| `Oracle` | `MERGE INTO ... USING DUAL` |
| `Microsoft SQL Server` | `MERGE INTO ... USING (VALUES ...) AS src` |

---

## Next Steps

- [Store Types](../configuration/store-types.md) — JDBC store setup
- [Multi-Tenant](../configuration/multi-tenant.md) — routing to per-tenant DataSources
