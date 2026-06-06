---
icon: material/database-edit
---

# Failover Store Query Resolver

`FailoverStoreQueryResolver` owns all SQL text, parameter arrays, and SQL-type arrays for the JDBC failover store. Centralising these in one interface means a DDL change — adding a column, renaming a table, switching a dialect — requires edits in exactly one place.

Override it when:

- Your `FAILOVER_STORE` table lives in a custom schema or has a non-standard name.
- You need to add application-specific columns (e.g. tenant ID, region, trace ID).
- Your database is not among the four built-in dialects (H2, PostgreSQL, MySQL/MariaDB, Oracle).
- You need a completely custom upsert strategy (e.g. stored procedure, JDBC batch).

---

## Interface Contract

```java
public interface FailoverStoreQueryResolver {

    // — Query strings —
    String getInsertQuery();     // INSERT a new row
    String getUpdateQuery();     // UPDATE an existing row
    String getSelectQuery();     // SELECT a single row by (FAILOVER_NAME, FAILOVER_KEY)
    String getDeleteQuery();     // DELETE a single row by (FAILOVER_NAME, FAILOVER_KEY)
    String getCleanUpQuery();    // DELETE all rows where EXPIRE_ON < given timestamp

    @Nullable
    String getMergeQuery();      // native upsert; null → INSERT + UPDATE fallback

    // — Parameter builders —
    <T> Object[] buildInsertMergeParams(ReferentialPayload<T> payload);
    int[]        buildInsertMergeTypes();

    <T> Object[] buildUpdateParams(ReferentialPayload<T> payload);
    int[]        buildUpdateTypes();
}
```

---

## Default Implementation

`DefaultFailoverStoreQueryResolver` handles all four dialects and the generic fallback:

| Database | Strategy |
|---|---|
| H2 | `MERGE INTO {prefix}FAILOVER_STORE … KEY (FAILOVER_NAME, FAILOVER_KEY)` |
| PostgreSQL | `INSERT … ON CONFLICT (FAILOVER_NAME, FAILOVER_KEY) DO UPDATE SET …` |
| MySQL / MariaDB | `INSERT … ON DUPLICATE KEY UPDATE …` |
| Oracle | `MERGE INTO … USING (SELECT … FROM DUAL)` |
| Other / `null` | Separate INSERT + UPDATE on duplicate (no native upsert) |

Column order for INSERT / MERGE parameters:

```
FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS
```

Column order for UPDATE parameters:

```
AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS, FAILOVER_NAME, FAILOVER_KEY   (SET first, WHERE last)
```

The table name is `{tablePrefix}FAILOVER_STORE`. Configure the prefix via:

```yaml
failover:
  store:
    jdbc:
      table-prefix: MYAPP_       # → MYAPP_FAILOVER_STORE
```

---

## Custom Schema or Table Name

Extend `DefaultFailoverStoreQueryResolver` and override only the queries that differ:

```java
@Component
public class TenantPrefixedQueryResolver extends DefaultFailoverStoreQueryResolver {

    public TenantPrefixedQueryResolver(
            String tablePrefix,
            Serializer serializer,
            DatabaseResolver databaseResolver,
            PayloadColumnResolver payloadColumnResolver) {
        super("TENANT_SCHEMA." + tablePrefix, serializer, databaseResolver, payloadColumnResolver);
    }
}
```

This produces SQL like:

```sql
INSERT INTO TENANT_SCHEMA.MYAPP_FAILOVER_STORE (FAILOVER_NAME, FAILOVER_KEY, …) VALUES (?, ?, …)
```

---

## Add Application-Specific Columns

Override all query methods and both parameter builders when you need extra columns:

```java
@Component
@RequiredArgsConstructor
public class RegionAwareQueryResolver implements FailoverStoreQueryResolver {

    private final String region;            // injected from config
    private final Serializer serializer;
    private final PayloadColumnResolver payloadColumnResolver;

    @Override
    public String getInsertQuery() {
        return "INSERT INTO FAILOVER_STORE " +
               "(FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS, REGION) " +
               "VALUES (?, ?, ?, ?, ?, ?, ?)";
    }

    @Override
    public String getUpdateQuery() {
        return "UPDATE FAILOVER_STORE " +
               "SET AS_OF = ?, EXPIRE_ON = ?, PAYLOAD = ?, PAYLOAD_CLASS = ? " +
               "WHERE FAILOVER_NAME = ? AND FAILOVER_KEY = ? AND REGION = ?";
    }

    @Override
    public String getSelectQuery() {
        return "SELECT FAILOVER_NAME, FAILOVER_KEY, AS_OF, EXPIRE_ON, PAYLOAD, PAYLOAD_CLASS " +
               "FROM FAILOVER_STORE WHERE FAILOVER_NAME = ? AND FAILOVER_KEY = ? AND REGION = ?";
    }

    @Override
    public String getDeleteQuery() {
        return "DELETE FROM FAILOVER_STORE WHERE FAILOVER_NAME = ? AND FAILOVER_KEY = ? AND REGION = ?";
    }

    @Override
    public String getCleanUpQuery() {
        return "DELETE FROM FAILOVER_STORE WHERE EXPIRE_ON < ? AND REGION = ?";
    }

    @Override
    public String getMergeQuery() {
        return null;    // region-partitioned store; INSERT + UPDATE fallback is fine
    }

    @Override
    public <T> Object[] buildInsertMergeParams(ReferentialPayload<T> p) {
        return new Object[]{
            p.getName(), p.getKey(),
            Timestamp.valueOf(p.getAsOf()), Timestamp.valueOf(p.getExpireOn()),
            serializer.serialize(p.getPayload()), serializer.toClassName(p.getPayload()),
            region                                      // (1) extra column
        };
    }

    @Override
    public int[] buildInsertMergeTypes() {
        return new int[]{
            Types.VARCHAR, Types.VARCHAR,
            Types.TIMESTAMP, Types.TIMESTAMP,
            payloadColumnResolver.payloadType(), Types.VARCHAR,
            Types.VARCHAR                               // REGION
        };
    }

    @Override
    public <T> Object[] buildUpdateParams(ReferentialPayload<T> p) {
        return new Object[]{
            Timestamp.valueOf(p.getAsOf()), Timestamp.valueOf(p.getExpireOn()),
            serializer.serialize(p.getPayload()), serializer.toClassName(p.getPayload()),
            p.getName(), p.getKey(),
            region                                      // WHERE clause
        };
    }

    @Override
    public int[] buildUpdateTypes() {
        return new int[]{
            Types.TIMESTAMP, Types.TIMESTAMP,
            payloadColumnResolver.payloadType(), Types.VARCHAR,
            Types.VARCHAR, Types.VARCHAR,
            Types.VARCHAR                               // REGION
        };
    }
}
```

1. `region` is appended to every INSERT/MERGE params array as the last element, matching the `REGION` placeholder.

> **Column order must match parameter order.** The types array at `buildInsertMergeTypes()` and `buildUpdateTypes()` must be kept in sync with the `?` placeholders in the corresponding SQL — off-by-one errors produce silent data corruption.

---

## Registration

Declare as `@Component` or `@Bean`. Auto-configuration is `@ConditionalOnMissingBean(FailoverStoreQueryResolver.class)`:

```java
@Bean
public FailoverStoreQueryResolver failoverStoreQueryResolver(
        Serializer serializer,
        DatabaseResolver databaseResolver,
        PayloadColumnResolver payloadColumnResolver) {
    return new RegionAwareQueryResolver("EU", serializer, payloadColumnResolver);
}
```

Exactly one bean must implement `FailoverStoreQueryResolver` — the framework wires it into `FailoverStoreJdbc` automatically.

---

## Testing

Test the query resolver in isolation — no database required:

```java
class RegionAwareQueryResolverTest {

    private final Serializer serializer = mock(Serializer.class);
    private final PayloadColumnResolver colResolver = mock(PayloadColumnResolver.class);
    private final RegionAwareQueryResolver resolver =
            new RegionAwareQueryResolver("EU", serializer, colResolver);

    @BeforeEach
    void setup() {
        when(colResolver.payloadType()).thenReturn(Types.VARCHAR);
        when(serializer.serialize(any())).thenReturn("{\"id\":1}");
        when(serializer.toClassName(any())).thenReturn("com.example.Country");
    }

    @Test
    void insert_query_includes_region_column() {
        assertThat(resolver.getInsertQuery()).contains("REGION");
    }

    @Test
    void insert_params_length_matches_placeholder_count() {
        ReferentialPayload<Object> payload = buildSamplePayload();
        Object[] params = resolver.buildInsertMergeParams(payload);
        int[] types    = resolver.buildInsertMergeTypes();

        assertThat(params).hasSize(7);
        assertThat(types).hasSize(7);
        assertThat(params[6]).isEqualTo("EU");          // REGION is last
    }

    @Test
    void update_params_have_region_in_where_position() {
        ReferentialPayload<Object> payload = buildSamplePayload();
        Object[] params = resolver.buildUpdateParams(payload);

        assertThat(params[params.length - 1]).isEqualTo("EU");
    }

    private ReferentialPayload<Object> buildSamplePayload() {
        return ReferentialPayload.builder()
                .name("countries").key("FR")
                .asOf(Instant.now())
                .expireOn(Instant.now().plusSeconds(3600))
                .payload(new Object())
                .build();
    }
}
```

---

## See Also

- [Database Resolver](database-resolver.md) — supplies the product name used to select the merge dialect
- [Payload Column Resolver](payload-column-resolver.md) — controls the JDBC type for the `PAYLOAD` column
- [Store Types](../configuration/store-types.md) — JDBC store schema and configuration reference
