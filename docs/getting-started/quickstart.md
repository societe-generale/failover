---
icon: material/lightning-bolt
---

# Quickstart

Build a failover-enabled service in 5 minutes — one dependency, one annotation, and your service is protected.

---

## 1. Add the Dependency

=== "Maven"

    ```xml title="pom.xml"
    <dependency>
        <groupId>com.societegenerale.failover</groupId>
        <artifactId>failover-spring-boot-starter</artifactId>
        <version>3.0.0</version>
    </dependency>
    ```

=== "Gradle"

    ```kotlin title="build.gradle.kts"
    implementation("com.societegenerale.failover:failover-spring-boot-starter:3.0.0")
    ```

---

## 2. Configure the Store

=== "In-Memory (dev/test)"

    ```yaml title="application.yml"
    # No additional config needed — in-memory is the default.
    # Not suitable for production: data is lost on restart.
    failover:
      enabled: true
    ```

=== "Caffeine (single-node)"

    ```yaml title="application.yml"
    failover:
      store:
        type: caffeine
    ```

=== "JDBC (production)"

    ```yaml title="application.yml"
    failover:
      store:
        type: jdbc
        jdbc:
          table-prefix: DEMO_
    ```

    ```sql title="create_table.sql"
    CREATE TABLE DEMO_FAILOVER_STORE (
        FAILOVER_NAME  VARCHAR(50)                  NOT NULL,
        FAILOVER_KEY   VARCHAR(256)                 NOT NULL,
        AS_OF          TIMESTAMP(9) WITH TIME ZONE  NOT NULL,
        EXPIRE_ON      TIMESTAMP(9) WITH TIME ZONE  NOT NULL,
        PAYLOAD        VARCHAR(4000),
        PAYLOAD_CLASS  VARCHAR(256),
        PRIMARY KEY (FAILOVER_NAME, FAILOVER_KEY)
    );
    ```

---

## 3. Define Your Domain Type

Your return type must extend `Referential` or implement `ReferentialAware` to carry failover metadata (`upToDate`, `asOf`) back to callers.

=== "Extend Referential"

    ```java title="Country.java"
    @Data
    @EqualsAndHashCode(callSuper = false)
    public class Country extends Referential {
        private String code;
        private String name;
        private String currency;
    }
    ```

    `Referential` adds three fields:

    | Field | Type | Description |
    |---|---|---|
    | `upToDate` | `boolean` | `true` when fetched live; `false` when recovered from store |
    | `asOf` | `Instant` | When the payload was originally stored |
    | `metadata` | `Metadata` | Optional carrier for additional context |

=== "Implement ReferentialAware"

    ```java title="Country.java"
    @Data
    public class Country implements ReferentialAware {
        private String code;
        private String name;
        private boolean upToDate;
        private Instant asOf;
        private Metadata metadata;

        @Override
        public void setUpToDate(boolean upToDate) { 
            this.upToDate = upToDate; 
        }

        @Override
        public void setAsOf(Instant asOf) { 
            this.asOf = asOf; 
        }

        @Override
        public void setMetadata(Metadata metadata) { 
            this.metadata = metadata; 
        }
    }
    ```

    Use `ReferentialAware` when you cannot extend `Referential` (e.g. the class already has a superclass).

---

## 4. Annotate Your Method

Place `@Failover` on a method of a Spring-managed bean. The annotation works on any bean type: `@Service`, `@Component`, `@FeignClient`, etc.

```java title="CountryClient.java" hl_lines="1 2 3 4"
@FeignClient(name = "country-service", url = "${country.service.url}")
public interface CountryClient {

    @Failover(
        name = "country-by-code",
        expiryDuration = 24,
        expiryUnit = ChronoUnit.HOURS
    )
    Country findByCode(@RequestParam String code);

    @Failover(
        name = "all-countries",
        expiryDuration = 1,
        expiryUnit = ChronoUnit.DAYS
    )
    List<Country> findAll();
}
```

!!! warning "Annotate the implementation, not the interface"
    Spring AOP uses CGLIB proxies on concrete classes. If your `@Failover` is on an interface method (like a Feign client), the framework still intercepts it — but if you use CGLIB proxies directly, the annotation must be on the concrete class method.

---

## 5. What You Get

On every successful upstream call, Failover stores the result with the configured TTL:

```
INFO  FailoverHandler: Storing information on 'country-by-code' for failover.
      ReferentialPayload: {name=country-by-code, key=<UUID>, upToDate=true, asOf=..., expireOn=...}
```

On upstream failure, the last stored result is served automatically:

```
INFO  FailoverHandler: Recovering information on 'country-by-code' from failover store
      due to exception: Connection refused
INFO  FailoverHandler: Successfully recovered the information on 'country-by-code'.
      ReferentialPayload: {upToDate=false, asOf=2024-01-15T10:30:00Z}
```

The returned object has `upToDate=false` and `asOf` set to the original store timestamp:

```java
Country country = countryClient.findByCode("FR");
System.out.println(country.isUpToDate());  // false (recovered from store)
System.out.println(country.getAsOf());     // 2024-01-15T10:30:00Z
```

---

## 6. Scatter / Gather (Optional)

For collection-returning methods, use a `PayloadSplitter` to store each entity individually — enabling partial recovery when only some entries are available.

```java title="CountryClient.java" hl_lines="3"
@Failover(
    name = "countries-by-codes",
    domain = "country",                      // shares store with country-by-code
    payloadSplitter = "countrySplitter",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
List<Country> findByCodes(@RequestParam String codes);  // codes = "FR,DE,US"
```

See [Scatter / Gather](../concepts/scatter-gather.md) for the full implementation guide.

---

## Next Steps

- [How It Works](../concepts/how-it-works.md) — full lifecycle explanation
- [Properties Reference](../configuration/properties-reference.md) — all configuration options
- [Store Types](../configuration/store-types.md) — choose the right backing store
