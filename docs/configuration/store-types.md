---
icon: material/database-outline
---

# Store Types

The backing store is configured via `failover.store.type`. Four implementations are available.

---

## INMEMORY

```yaml
failover:
  store:
    type: inmemory
```

A `ConcurrentHashMap`-backed store. No persistence across restarts.

!!! danger "Not for production"
    The in-memory store loses all data on restart. Use it only for local development and unit tests.

No additional dependencies or configuration required.

---

## CAFFEINE

```yaml
failover:
  store:
    type: caffeine
```

A [Caffeine](https://github.com/ben-manes/caffeine) cache-backed store. Entries expire automatically based on the Caffeine configuration. The Failover expiry policy is applied on top — entries are checked for expiry before being returned.

### Dependency

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-store-caffeine</artifactId>
    <version>3.0.0</version>
</dependency>
```

!!! note
    `spring-boot-starter-cache` and `com.github.ben-manes.caffeine:caffeine` must be on the classpath.

---

## JDBC

```yaml
failover:
  store:
    type: jdbc
    jdbc:
      table-prefix: MYAPP_
```

A relational database-backed store. Entries survive application restarts. Supports all major databases — H2, PostgreSQL, MySQL, MariaDB, Oracle, SQL Server.

### Dependency

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-store-jdbc</artifactId>
    <version>3.0.0</version>
</dependency>
```

### Table DDL

The framework stores all timestamps as `java.time.Instant` (UTC-relative). `AS_OF` and `EXPIRE_ON` must therefore be timezone-aware columns so expiry calculations remain correct across nodes running in different JVM timezones.

=== "H2 / PostgreSQL / Oracle"

    ```sql
    -- TIMESTAMP WITH TIME ZONE preserves UTC offset on write and read
    CREATE TABLE MYAPP_FAILOVER_STORE (
        FAILOVER_NAME  VARCHAR(50)   NOT NULL,
        FAILOVER_KEY   VARCHAR(256)  NOT NULL,
        AS_OF          TIMESTAMP WITH TIME ZONE NOT NULL,
        EXPIRE_ON      TIMESTAMP WITH TIME ZONE NOT NULL,
        PAYLOAD        VARCHAR(4000),
        PAYLOAD_CLASS  VARCHAR(256),
        PRIMARY KEY (FAILOVER_NAME, FAILOVER_KEY)
    );
    ```

=== "MySQL / MariaDB"

    ```sql
    -- MySQL/MariaDB do not support TIMESTAMP WITH TIME ZONE.
    -- Use DATETIME(6) and pin the server (or JDBC URL) to UTC:
    --   jdbc:mysql://host/db?serverTimezone=UTC
    CREATE TABLE MYAPP_FAILOVER_STORE (
        FAILOVER_NAME  VARCHAR(50)   NOT NULL,
        FAILOVER_KEY   VARCHAR(256)  NOT NULL,
        AS_OF          DATETIME(6)   NOT NULL,
        EXPIRE_ON      DATETIME(6)   NOT NULL,
        PAYLOAD        VARCHAR(4000),
        PAYLOAD_CLASS  VARCHAR(256),
        PRIMARY KEY (FAILOVER_NAME, FAILOVER_KEY)
    );
    ```

=== "SQL Server"

    ```sql
    -- SQL Server equivalent is DATETIMEOFFSET (stores offset alongside value)
    CREATE TABLE MYAPP_FAILOVER_STORE (
        FAILOVER_NAME  VARCHAR(50)        NOT NULL,
        FAILOVER_KEY   VARCHAR(256)       NOT NULL,
        AS_OF          DATETIMEOFFSET     NOT NULL,
        EXPIRE_ON      DATETIMEOFFSET     NOT NULL,
        PAYLOAD        VARCHAR(4000),
        PAYLOAD_CLASS  VARCHAR(256),
        PRIMARY KEY (FAILOVER_NAME, FAILOVER_KEY)
    );
    ```

### Timezone Support by Database

| Database   | TZ-aware column type       | Native `TIMESTAMP WITH TIME ZONE` | Upsert dialect                                   | JDBC URL requirement  |
|------------|----------------------------|:---------------------------------:|--------------------------------------------------|-----------------------|
| H2 2.x     | `TIMESTAMP WITH TIME ZONE` | ✅                                | `MERGE INTO … KEY(…)`                            | —                     |
| PostgreSQL | `TIMESTAMP WITH TIME ZONE` | ✅                                | `INSERT … ON CONFLICT DO UPDATE`                 | —                     |
| Oracle     | `TIMESTAMP WITH TIME ZONE` | ✅                                | `MERGE INTO … USING (SELECT … FROM DUAL)`        | —                     |
| MySQL      | `DATETIME(6)`              | ❌                                | `INSERT … ON DUPLICATE KEY UPDATE`               | `?serverTimezone=UTC` |
| MariaDB    | `DATETIME(6)`              | ❌                                | `INSERT … ON DUPLICATE KEY UPDATE`               | `?timezone=UTC`       |
| SQL Server | `DATETIMEOFFSET`           | ✅                                | INSERT + UPDATE fallback (no native upsert)      | —                     |

!!! warning "MySQL / MariaDB — JDBC URL required"
    Without `serverTimezone=UTC` in the JDBC URL, `Timestamp.from(Instant)` binds using the JVM's local timezone, shifting stored values by the UTC offset. Always set `?serverTimezone=UTC` (MySQL Connector/J) or `?timezone=UTC` (MariaDB Connector/J).

!!! note "H2 2.x — `getObject()` return type"
    `queryForList()` and `getObject()` return `OffsetDateTime` for `TIMESTAMP WITH TIME ZONE` columns in H2 2.x. Cast to `OffsetDateTime` (not `java.sql.Timestamp`) before calling `.toInstant()`. `rs.getTimestamp()` still returns `Timestamp` — only the raw `getObject()` path differs.

!!! note "PostgreSQL alias"
    `TIMESTAMPTZ` is identical to `TIMESTAMP WITH TIME ZONE`; both are accepted by PostgreSQL.

!!! tip "Payload column size"
    `PAYLOAD` stores a JSON-serialised representation of your domain object. Size the column to fit the largest expected payload. `VARCHAR(4000)` is a safe starting point; use `CLOB`/`TEXT` for very large payloads.

### Payload Serialization

Payloads are serialised to JSON using Jackson. The `PAYLOAD_CLASS` column stores the fully-qualified class name for deserialisation. Ensure Jackson can serialise and deserialise your domain types (public no-arg constructor or `@JsonCreator`, all fields accessible).

### Async Writes

By default, store/delete/clean operations are offloaded to a background executor (`failover.store.async=true`). The `find` operation is always synchronous to ensure consistent recovery. Disable async writes when using the SCHEMA multi-tenant strategy:

```yaml
failover:
  store:
    async: false
```

---

## CUSTOM

```yaml
failover:
  store:
    type: custom
```

Provide your own `FailoverStore<T>` bean. The auto-configuration is `@ConditionalOnMissingBean`, so declaring a `FailoverStore` bean automatically takes precedence.

```java
@Bean
public FailoverStore<Object> myFailoverStore() {
    return new RedisFailoverStore(redisTemplate);
}
```

---

## Choosing a Store

| Store | Persistence | Suitable for |
|---|---|---|
| INMEMORY | No | Local dev, unit tests |
| CAFFEINE | No (in-process) | Single-instance apps, test environments |
| JDBC | Yes | Production; multi-instance; audit requirements |
| CUSTOM | Depends | Redis, Hazelcast, custom backends |
