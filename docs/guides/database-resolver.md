---
icon: material/database-search
---

# Database Resolver

`DatabaseResolver` detects the underlying database product name from a live JDBC connection. The resolved name drives dialect selection in `DefaultFailoverStoreQueryResolver` — H2, PostgreSQL, MySQL/MariaDB, and Oracle each use a different native merge/upsert syntax.

Override it when:

- Auto-detection fails (permission denied on connection metadata).
- Your driver reports a product name that doesn't match the expected keyword (e.g. `"Amazon Aurora"` instead of `"MySQL"`).
- You run integration tests with an in-memory database that should behave as a different dialect.
- You want to harden the startup path (no live DB call during context initialisation).

---

## Interface Contract

```java
public interface DatabaseResolver {

    /**
     * @return database product name (e.g. "H2", "PostgreSQL"), or null
     *         if the name cannot be determined.
     *         Returning null disables native merge — store falls back to INSERT + UPDATE.
     */
    @Nullable
    String resolve();
}
```

The default implementation reads from JDBC metadata:

```java
public class DefaultDatabaseResolver implements DatabaseResolver {

    private final JdbcTemplate jdbcTemplate;

    @Override
    public String resolve() {
        try {
            return jdbcTemplate.execute(
                (Connection conn) -> conn.getMetaData().getDatabaseProductName()
            );
        } catch (Exception e) {
            log.warn("Failed to detect database product name — falling back to INSERT/UPDATE. Cause: {}",
                     e.getMessage());
            return null;
        }
    }
}
```

---

## Dialect Selection

`DefaultFailoverStoreQueryResolver` uses case-insensitive substring matching on the resolved name:

| Resolved name contains | Merge dialect |
|---|---|
| `h2` | `MERGE INTO … KEY (FAILOVER_NAME, FAILOVER_KEY)` |
| `postgresql` or `postgres` | `INSERT … ON CONFLICT (…) DO UPDATE SET …` |
| `mysql` or `mariadb` | `INSERT … ON DUPLICATE KEY UPDATE …` |
| `oracle` | `MERGE INTO … USING (SELECT … FROM DUAL)` |
| `null` or anything else | INSERT + UPDATE fallback (no native upsert) |

---

## Implement a Hardcoded Resolver

Useful in environments where the DB type is known at deploy time or when metadata access is restricted:

```java
@Component
public class PostgreSQLDatabaseResolver implements DatabaseResolver {

    @Override
    public String resolve() {
        return "PostgreSQL";    // always selects ON CONFLICT DO UPDATE dialect
    }
}
```

For tests using H2 but needing MySQL dialect behaviour:

```java
@TestComponent
public class MySQLCompatDatabaseResolver implements DatabaseResolver {

    @Override
    public String resolve() {
        return "MySQL";
    }
}
```

---

## Implement a Cached Resolver

`DefaultDatabaseResolver` calls JDBC metadata on every `resolve()` invocation. Cache the result when you want a single detection at startup:

```java
@Component
@RequiredArgsConstructor
@Slf4j
public class CachedDatabaseResolver implements DatabaseResolver {

    private final JdbcTemplate jdbcTemplate;

    private volatile String cachedProduct;

    @Override
    public String resolve() {
        if (cachedProduct == null) {
            synchronized (this) {
                if (cachedProduct == null) {
                    cachedProduct = detect();
                }
            }
        }
        return cachedProduct;
    }

    private String detect() {
        try {
            return jdbcTemplate.execute(
                (Connection conn) -> conn.getMetaData().getDatabaseProductName()
            );
        } catch (Exception e) {
            log.warn("DB detection failed — INSERT/UPDATE fallback. Cause: {}", e.getMessage());
            return null;
        }
    }
}
```

---

## Registration

Declare as `@Component` or `@Bean`. The JDBC auto-configuration is `@ConditionalOnMissingBean(DatabaseResolver.class)`:

```java
@Bean
public DatabaseResolver databaseResolver(JdbcTemplate jdbcTemplate) {
    return new PostgreSQLDatabaseResolver();
}
```

Declaring any bean that implements `DatabaseResolver` replaces `DefaultDatabaseResolver`.

---

## Testing

```java
class PostgreSQLDatabaseResolverTest {

    private final PostgreSQLDatabaseResolver resolver = new PostgreSQLDatabaseResolver();

    @Test
    void resolves_postgresql_name() {
        assertThat(resolver.resolve()).isEqualTo("PostgreSQL");
    }
}

class DefaultDatabaseResolverTest {

    private final JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    private final DefaultDatabaseResolver resolver = new DefaultDatabaseResolver(jdbcTemplate);

    @Test
    void returns_product_name_from_jdbc_metadata() {
        when(jdbcTemplate.execute(any(ConnectionCallback.class))).thenReturn("H2");

        assertThat(resolver.resolve()).isEqualTo("H2");
    }

    @Test
    void returns_null_when_metadata_throws() {
        when(jdbcTemplate.execute(any(ConnectionCallback.class)))
            .thenThrow(new DataAccessException("no metadata") {});

        assertThat(resolver.resolve()).isNull();
    }
}
```

---

## See Also

- [Failover Store Query Resolver](failover-store-query-resolver.md) — uses the resolved name to select merge dialect
- [Payload Column Resolver](payload-column-resolver.md) — controls PAYLOAD column JDBC type
- [Store Types](../configuration/store-types.md) — JDBC store configuration reference
