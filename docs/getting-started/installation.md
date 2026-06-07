---
icon: material/package-variant-closed
---

# Installation

## Starter Dependency

Add a single dependency. The Spring Boot starter pulls in all required modules automatically.

=== "Maven"

    ```xml
    <dependency>
        <groupId>com.societegenerale.failover</groupId>
        <artifactId>failover-spring-boot-starter</artifactId>
        <version>3.0.0</version>
    </dependency>
    ```

=== "Gradle (Kotlin)"

    ```kotlin
    implementation("com.societegenerale.failover:failover-spring-boot-starter:3.0.0")
    ```

=== "Gradle (Groovy)"

    ```groovy
    implementation 'com.societegenerale.failover:failover-spring-boot-starter:3.0.0'
    ```

No mandatory properties — the framework starts with production-safe defaults. All you need is to annotate your Spring-managed methods with `@Failover`.

---

## Module Dependencies

If you need finer control, declare individual modules instead of the starter.

### Core (always required)

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-core</artifactId>
    <version>3.0.0</version>
</dependency>
```

### Domain (add to modules that annotate methods)

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-domain</artifactId>
    <version>3.0.0</version>
</dependency>
```

This module provides the `@Failover` annotation and the `Referential` / `ReferentialAware` contracts. Add it to any module that declares `@Failover` on methods or defines referential domain types.

### Observable — Scanner

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-observable-scanner</artifactId>
    <version>3.0.0</version>
</dependency>
```

Discovers all `@Failover` annotations from the Spring `ApplicationContext` at startup. No Micrometer dependency. Use this when you want Spring-context-based scanning without Micrometer metrics.

### Observable — Micrometer (optional extension)

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-observable-micrometer</artifactId>
    <version>3.0.0</version>
</dependency>
```

Adds Micrometer meters and a Spring Boot Actuator health indicator. Brings in `failover-observable-scanner` transitively. See [Observable Modules](../modules/observable.md).

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

Enable with `failover.type: resilience`. Requires `spring-cloud-starter-circuitbreaker-resilience4j` on the classpath.

### Multi-Tenant Store

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-store-multitenant</artifactId>
    <version>3.0.0</version>
</dependency>
```

---

## Requirements

| Requirement  | Minimum                             |
|--------------|-------------------------------------|
| Java         | 21                                  |
| Spring Boot  | 4.x                                 |
| Spring Cloud | 2025.x (optional, for Resilience4j) |

---

## Database Setup (JDBC only)

Create the store table before starting your application. The table name is `{table-prefix}FAILOVER_STORE`.

```sql
-- Example: failover.store.jdbc.table-prefix=MYAPP_
-- Table name: MYAPP_FAILOVER_STORE

CREATE TABLE MYAPP_FAILOVER_STORE (
    FAILOVER_NAME    VARCHAR(50)   NOT NULL,
    FAILOVER_KEY     VARCHAR(256)  NOT NULL,
    AS_OF            TIMESTAMP(9) WITH TIME ZONE     NOT NULL,
    EXPIRE_ON        TIMESTAMP(9) WITH TIME ZONE     NOT NULL,
    PAYLOAD          VARCHAR(4000),           -- size to fit your largest payload
    PAYLOAD_CLASS    VARCHAR(256),
    PRIMARY KEY (FAILOVER_NAME, FAILOVER_KEY)
);
```

!!! tip "Table prefix strategy"
    Use a meaningful prefix that identifies your application (`MYAPP_`, `ORDER_`, etc.).
    This makes multi-schema deployments and DBA queries easier to reason about.
