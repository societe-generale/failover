---
icon: material/package-variant-closed
---

# Installation

Add a single starter dependency — it pulls in all required modules automatically with zero mandatory configuration.

---

## Starter Dependency

=== "Maven"

    ```xml title="pom.xml"
    <dependency>
        <groupId>com.societegenerale.failover</groupId>
        <artifactId>failover-spring-boot-starter</artifactId>
        <version>3.0.0</version>
    </dependency>
    ```

=== "Gradle (Kotlin)"

    ```kotlin title="build.gradle.kts"
    implementation("com.societegenerale.failover:failover-spring-boot-starter:3.0.0")
    ```

=== "Gradle (Groovy)"

    ```groovy title="build.gradle"
    implementation 'com.societegenerale.failover:failover-spring-boot-starter:3.0.0'
    ```

No additional configuration is required. The auto-configuration starts with an in-memory store and `exception-policy: rethrow` by default.

---

## Requirements

| Requirement | Minimum version |
|---|---|
| Java | 21 |
| Spring Boot | 4.x |
| Spring Cloud | 2025.x (optional — only for Resilience4j) |

---

## Individual Module Dependencies

For fine-grained control, declare individual modules instead of the starter.

### Core (always required)

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-core</artifactId>
    <version>3.0.0</version>
</dependency>
```

### Domain annotation

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-domain</artifactId>
    <version>3.0.0</version>
</dependency>
```

Add this to every module that annotates methods with `@Failover` or defines types extending `Referential`.

### Observability — Scanner only

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-observable-scanner</artifactId>
    <version>3.0.0</version>
</dependency>
```

Discovers all `@Failover` methods in the Spring context at startup. No Micrometer dependency.

### Observability — Micrometer

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-observable-micrometer</artifactId>
    <version>3.0.0</version>
</dependency>
```

Adds Micrometer counters and a Spring Boot Actuator health indicator. Includes the scanner transitively.

### JDBC Store

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-store-jdbc</artifactId>
    <version>3.0.0</version>
</dependency>
```

Requires a `DataSource` bean and the `FAILOVER_STORE` table — see [Store Types](../configuration/store-types.md#jdbc).

### Caffeine Store

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-store-caffeine</artifactId>
    <version>3.0.0</version>
</dependency>
```

### Resilience4j Integration

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-execution-resilience</artifactId>
    <version>3.0.0</version>
</dependency>
```

Requires `spring-cloud-starter-circuitbreaker-resilience4j` on the classpath. Enable with `failover.type: resilience`.

### Multi-Tenant Store

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-store-multitenant</artifactId>
    <version>3.0.0</version>
</dependency>
```

---

## Database Setup (JDBC only)

Create the store table before starting your application. The table name defaults to `FAILOVER_STORE`; the optional `table-prefix` property prepends a custom prefix.

```sql title="failover_store.sql"
-- Default table name: FAILOVER_STORE
-- With table-prefix=MYAPP_  →  MYAPP_FAILOVER_STORE

CREATE TABLE FAILOVER_STORE (
    FAILOVER_NAME    VARCHAR(50)                      NOT NULL,
    FAILOVER_KEY     VARCHAR(256)                     NOT NULL,
    AS_OF            TIMESTAMP(9) WITH TIME ZONE      NOT NULL,
    EXPIRE_ON        TIMESTAMP(9) WITH TIME ZONE      NOT NULL,
    PAYLOAD          VARCHAR(4000),
    PAYLOAD_CLASS    VARCHAR(256),
    PRIMARY KEY (FAILOVER_NAME, FAILOVER_KEY)
);
```

| Column | Type | Description |
|---|---|---|
| `FAILOVER_NAME` | `VARCHAR(50)` | Failover name (or domain) from the annotation |
| `FAILOVER_KEY` | `VARCHAR(256)` | UUID-hashed key derived from method arguments |
| `AS_OF` | `TIMESTAMP WITH TIME ZONE` | When the payload was last stored |
| `EXPIRE_ON` | `TIMESTAMP WITH TIME ZONE` | When the entry expires |
| `PAYLOAD` | `VARCHAR(4000)` | JSON-serialised payload; size to your largest payload |
| `PAYLOAD_CLASS` | `VARCHAR(256)` | Fully qualified class name for deserialization |

!!! tip "Table prefix strategy"
    Use a prefix that identifies your application (`MYAPP_`, `ORDER_`, etc.).
    This simplifies DBA queries and avoids table name collisions in shared schemas.

---

## Next Steps

- [Quickstart](quickstart.md) — working example in 5 minutes
- [Store Types](../configuration/store-types.md) — InMemory / Caffeine / JDBC comparison
- [Properties Reference](../configuration/properties-reference.md) — all configuration options
