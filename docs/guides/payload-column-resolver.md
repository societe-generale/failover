---
icon: material/table-column
---

# Payload Column Resolver

`PayloadColumnResolver` controls how the JDBC store reads and writes the `PAYLOAD` column. The default implementation maps it as `VARCHAR`, which works for most databases when payload JSON fits within the column limit.

Override it when:

- Your database uses `TEXT`, `CLOB`, or `BLOB` for large payload columns.
- You use a custom column type (e.g. `JSONB` on PostgreSQL) and need a matching extractor.
- Payload serialisation produces strings longer than `VARCHAR(255)` (or your configured limit).

---

## Interface Contract

```java
public interface PayloadColumnResolver {

    /**
     * Returns the JDBC type constant for the PAYLOAD column.
     * @see java.sql.Types
     */
    int payloadType();

    /**
     * Extracts the payload string from the current ResultSet row.
     */
    String extractPayload(ResultSet resultSet, String payloadColumn) throws SQLException;
}
```

The default implementation:

```java
public class VarcharPayloadColumnResolver implements PayloadColumnResolver {

    @Override
    public int payloadType() {
        return Types.VARCHAR;     // JDBC type used in parameter binding
    }

    @Override
    public String extractPayload(ResultSet resultSet, String payloadColumn) throws SQLException {
        return resultSet.getString(payloadColumn);   // extract from VARCHAR column
    }
}
```

---

## Implement for TEXT / CLOB

Use when the `PAYLOAD` column is defined as `TEXT` (MySQL, PostgreSQL) or `CLOB` (Oracle, SQL Server):

```java
@Component
public class ClobPayloadColumnResolver implements PayloadColumnResolver {

    @Override
    public int payloadType() {
        return Types.CLOB;                          // (1) bind as CLOB
    }

    @Override
    public String extractPayload(ResultSet resultSet, String payloadColumn) throws SQLException {
        java.sql.Clob clob = resultSet.getClob(payloadColumn);   // (2) read CLOB
        if (clob == null) return null;
        try (var reader = clob.getCharacterStream()) {
            return new java.io.BufferedReader(reader)
                    .lines()
                    .collect(java.util.stream.Collectors.joining("\n"));
        } catch (java.io.IOException e) {
            throw new SQLException("Failed to read CLOB payload", e);
        }
    }
}
```

1. `Types.CLOB` is used when binding the payload parameter in INSERT/UPDATE/MERGE statements.
2. Read via `getClob()` rather than `getString()` — some drivers reject `getString()` on CLOB columns.

---

## Implement for PostgreSQL JSONB

When the column type is `JSONB` and you want the driver to handle the cast explicitly:

```java
@Component
public class JsonbPayloadColumnResolver implements PayloadColumnResolver {

    @Override
    public int payloadType() {
        return Types.OTHER;                         // PostgreSQL maps JSONB to Types.OTHER
    }

    @Override
    public String extractPayload(ResultSet resultSet, String payloadColumn) throws SQLException {
        Object value = resultSet.getObject(payloadColumn);
        return value != null ? value.toString() : null;
    }
}
```

Pair this with a DDL change on your `FAILOVER_STORE` table:

```sql
ALTER TABLE FAILOVER_STORE ALTER COLUMN PAYLOAD TYPE JSONB USING PAYLOAD::JSONB;
```

---

## Registration

Declare the resolver as a Spring bean. The JDBC auto-configuration is `@ConditionalOnMissingBean(PayloadColumnResolver.class)` — your bean replaces the default:

```java
@Bean
public PayloadColumnResolver payloadColumnResolver() {
    return new ClobPayloadColumnResolver();
}
```

Or annotate the class with `@Component` directly — no extra configuration needed.

---

## Testing

Test the resolver in isolation against a real `ResultSet`:

```java
class ClobPayloadColumnResolverTest {

    private final ClobPayloadColumnResolver resolver = new ClobPayloadColumnResolver();

    @Test
    void payloadType_is_CLOB() {
        assertThat(resolver.payloadType()).isEqualTo(Types.CLOB);
    }

    @Test
    void extractPayload_reads_clob_content() throws Exception {
        String json = "{\"id\":1,\"name\":\"Alpha\"}";
        Clob clob   = mock(Clob.class);
        when(clob.getCharacterStream())
            .thenReturn(new java.io.StringReader(json));

        ResultSet rs = mock(ResultSet.class);
        when(rs.getClob("PAYLOAD")).thenReturn(clob);

        assertThat(resolver.extractPayload(rs, "PAYLOAD")).isEqualTo(json);
    }

    @Test
    void extractPayload_returns_null_for_sql_null() throws Exception {
        ResultSet rs = mock(ResultSet.class);
        when(rs.getClob("PAYLOAD")).thenReturn(null);

        assertThat(resolver.extractPayload(rs, "PAYLOAD")).isNull();
    }
}
```

---

## See Also

- [Database Resolver](database-resolver.md) — detects the database product for merge dialect selection
- [Failover Store Query Resolver](failover-store-query-resolver.md) — customise the full SQL and parameter binding
- [Store Types](../configuration/store-types.md) — JDBC store configuration reference
