---
icon: material/database-search-outline
---

# Failover Store Query Resolver

`FailoverStoreQueryResolver` overrides the SQL queries used by the JDBC store. Use this when you need schema-qualified table names, custom column mappings, or database-specific query optimisations.

---

## Interface

```java
public interface FailoverStoreQueryResolver<T> {
    String storeQuery(String tableName);
    String findQuery(String tableName);
    String deleteQuery(String tableName);
    String cleanByExpiryQuery(String tableName);
    RowMapper<ReferentialPayload<T>> rowMapper();
}
```

Each method receives the computed `tableName` (including prefix) and returns the SQL string for that operation.

---

## When to Use

- Table is in a non-default schema: `MY_SCHEMA.FAILOVER_STORE`
- Custom column names or types
- Database-specific hints or query syntax not covered by the default dialect detection

---

## Step 1 — Implement FailoverStoreQueryResolver

```java title="SchemaQualifiedQueryResolver.java"
@Component
public class SchemaQualifiedQueryResolver<T> implements FailoverStoreQueryResolver<T> {

    private final String schema;

    public SchemaQualifiedQueryResolver(@Value("${db.schema:PUBLIC}") String schema) {
        this.schema = schema;
    }

    @Override
    public String storeQuery(String tableName) {
        return "MERGE INTO " + schema + "." + tableName +
               " (FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS)" +
               " KEY (FAILOVER_NAME, FAILOVER_KEY)" +
               " VALUES (?, ?, ?, ?, ?, ?)";
    }

    @Override
    public String findQuery(String tableName) {
        return "SELECT * FROM " + schema + "." + tableName +
               " WHERE FAILOVER_NAME = ? AND FAILOVER_KEY = ?";
    }

    @Override
    public String deleteQuery(String tableName) {
        return "DELETE FROM " + schema + "." + tableName +
               " WHERE FAILOVER_NAME = ? AND FAILOVER_KEY = ?";
    }

    @Override
    public String cleanByExpiryQuery(String tableName) {
        return "DELETE FROM " + schema + "." + tableName +
               " WHERE EXPIRE_ON < ?";
    }

    @Override
    public RowMapper<ReferentialPayload<T>> rowMapper() {
        return null;   // null = use default row mapper
    }
}
```

---

## Next Steps

- [Store Types](../configuration/store-types.md) — JDBC table DDL
- [Payload Column Resolver](payload-column-resolver.md) — customise payload serialisation
