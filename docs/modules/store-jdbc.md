# JDBC Store Module

`failover-store-jdbc` provides a relational database-backed `FailoverStore`. Data survives application restarts and is accessible from all application instances simultaneously.

## Dependency

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-store-jdbc</artifactId>
    <version>3.0.0</version>
</dependency>
```

## Configuration

```yaml
failover:
  store:
    type: jdbc
    jdbc:
      table-prefix: MYAPP_
```

## Table DDL

```sql
CREATE TABLE MYAPP_FAILOVER_STORE (
    FAILOVER_NAME  VARCHAR(50)   NOT NULL,
    FAILOVER_KEY   VARCHAR(256)  NOT NULL,
    AS_OF          TIMESTAMP     NOT NULL,
    EXPIRE_ON      TIMESTAMP     NOT NULL,
    PAYLOAD        VARCHAR(4000),
    PAYLOAD_CLASS  VARCHAR(256),
    PRIMARY KEY (FAILOVER_NAME, FAILOVER_KEY)
);
```

Create this table before starting the application. No Flyway/Liquibase migrations are provided — DDL is intentionally left to the application team.

---

## Payload Serialization

Payloads are JSON-serialised via Jackson. The `PAYLOAD_CLASS` column stores the fully-qualified class name for deserialization.

Requirements:
- Domain type must be JSON-serialisable (public getters or `@JsonProperty` annotations)
- Deserialisation requires a no-arg constructor or `@JsonCreator`

Custom serialization:

```java
@Bean
public Serializer mySerializer(ObjectMapper objectMapper) {
    return new JsonSerializer(objectMapper);
}
```

---

## Database Compatibility

Tested with: H2 (in-memory and file), PostgreSQL, MySQL, MariaDB, Oracle, SQL Server.

The query resolver (`FailoverStoreQueryResolver`) generates ANSI SQL. For database-specific features (e.g. JSON columns, UPSERT), provide a custom `FailoverStoreQueryResolver` bean.

---

## Index Recommendation

For large deployments, add an index on `EXPIRE_ON` to speed up the cleanup query:

```sql
CREATE INDEX IDX_MYAPP_FAILOVER_STORE_EXPIRE_ON ON MYAPP_FAILOVER_STORE (EXPIRE_ON);
```

---

## Async Writes

By default, writes (`store`, `delete`, `cleanByExpiry`) are offloaded to a background `TaskExecutor`. The `find` operation is always synchronous.

Disable async writes for synchronous operation (required for SCHEMA multi-tenant strategy):

```yaml
failover:
  store:
    async: false
```
