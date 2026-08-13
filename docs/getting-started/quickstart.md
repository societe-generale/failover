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

    -- Required: keeps the expiry-cleanup DELETE (`WHERE EXPIRE_ON < ?`) off a full table scan.
    CREATE INDEX IDX_DEMO_FAILOVER_STORE_EXPIRE_ON ON DEMO_FAILOVER_STORE (EXPIRE_ON);
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

!!! tip "`name` vs `domain` — don't confuse them"
    - **`name`** (required) identifies *this* failover point. It is the metric label and the default store grouping. Keep it **unique per method** — two methods sharing a `name` (without an explicit `domain`) will share store entries and clash.
    - **`domain`** (optional) deliberately *groups* several `@Failover` methods so they share store entries — e.g. a single-fetch and a batch-fetch over the same referential. Methods in one `domain` must agree on expiry; a mismatch is warned at startup.

    Rule of thumb: set only `name` until you actually need two methods to read each other's stored data — then give them the same `domain`. See [Domain Grouping](../concepts/domain.md).

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

## 7. Add the Dashboard (JDBC Store + JDBC Shared-Store, Direct Write)

The most complete production shape in one service: the `@Failover` store, the dashboard's cluster aggregate, and
the peer snapshot writes all live in the **same database** — no ingest endpoint, no ingest auth to configure,
one dependency set. Run one instance or ten identical replicas behind a load balancer; every replica uses this
exact same YAML, pointed at the same DB.

=== "Maven"

    ```xml title="pom.xml"
    <dependency>
        <groupId>com.societegenerale.failover</groupId>
        <artifactId>failover-spring-boot-starter</artifactId>
        <version>3.0.0</version>
    </dependency>
    <dependency>
        <groupId>com.societegenerale.failover</groupId>
        <artifactId>failover-dashboard-spring-boot-starter</artifactId>
        <version>3.0.0</version>
    </dependency>
    <dependency>
        <groupId>com.societegenerale.failover</groupId>
        <artifactId>failover-dashboard-snapshotstore-jdbc</artifactId>
        <version>3.0.0</version>
    </dependency>
    ```

=== "Gradle"

    ```kotlin title="build.gradle.kts"
    implementation("com.societegenerale.failover:failover-spring-boot-starter:3.0.0")
    implementation("com.societegenerale.failover:failover-dashboard-spring-boot-starter:3.0.0")
    implementation("com.societegenerale.failover:failover-dashboard-snapshotstore-jdbc:3.0.0")
    ```

```yaml title="application.yml — complete, copy-pasteable, includes instance live tracking"
spring:
  datasource:
    url: jdbc:postgresql://db:5432/myapp
    username: myapp
    password: secret

failover:
  enabled: true
  store:
    type: jdbc                       # the @Failover store itself — recovered payloads
    jdbc:
      table-prefix: DEMO_            # -> DEMO_FAILOVER_STORE (DDL: step 2 above)

  dashboard:
    enabled: true
    security:
      allow-insecure: true           # dev/local only — starts unsecured with a loud WARN;
      #   REFUSED under the 'prod' profile. Remove this and grant FAILOVER_ADMIN once
      #   Spring Security is configured for real use.
    cluster:
      mode: shared-store
      shared-store:
        store: jdbc                  # durable cluster aggregate — survives a restart
        max-instances: 10
        liveness-seconds: 180        # instance is DOWN after this long without a heartbeat (≈ 3x peer interval)
        liveness:
          enabled: true              # without this: no HeartbeatStore, no liveness query, ever
        jdbc:
          table-prefix: DEMO_        # -> DEMO_FAILOVER_DASHBOARD_SNAPSHOT / _HEARTBEAT (DDL below)
      snapshot:
        ingest:
          enabled: false             # no POST /api/cluster/snapshot or /heartbeat mapped — nothing to secure
        heartbeat:
          enabled: true              # sends a ~10-byte ping (instance id only) every interval-seconds
          interval-seconds: 60       # keep ≤ ⅓ of liveness-seconds above
        jdbc:
          enabled: true              # this instance writes its own snapshot + heartbeat straight into the tables
          table-prefix: DEMO_        # MUST match shared-store.jdbc.table-prefix above
```

```sql title="create_dashboard_snapshot_table.sql (PostgreSQL)"
CREATE TABLE DEMO_FAILOVER_DASHBOARD_SNAPSHOT
(
    INSTANCE_ID   VARCHAR(255) PRIMARY KEY,
    RECEIVED_AT   TIMESTAMP(6) WITH TIME ZONE NOT NULL,
    SUMMARY_JSON  TEXT NOT NULL,
    BASELINE_JSON TEXT,   -- reset-aware carried baseline; nullable
    CONFIG_JSON   TEXT    -- pushed @Failover config entries; nullable
);
```

```sql title="create_dashboard_heartbeat_table.sql (PostgreSQL)"
CREATE TABLE DEMO_FAILOVER_DASHBOARD_HEARTBEAT
(
    INSTANCE_ID VARCHAR(255) PRIMARY KEY,
    LAST_SEEN   TIMESTAMP(6) WITH TIME ZONE NOT NULL
);
```

Open `http://<app>:<port>/failover-dashboard` — no login needed with `allow-insecure: true` (dev/local; see the
warning below). Two independent JDBC tables share a `table-prefix` here for clarity — `store.jdbc.table-prefix`
names the `@Failover` store's own table, `dashboard.cluster.shared-store.jdbc.table-prefix` (which must equal
`dashboard.cluster.snapshot.jdbc.table-prefix` on every peer) names the dashboard's snapshot + heartbeat tables.
Neither table is created by the module — both DDLs above are yours to run.

!!! warning "`allow-insecure: true` is dev/local only"
    Starts the dashboard with **no access gate** — anyone who can reach `/failover-dashboard` sees it. Refused
    outright under the `prod` Spring profile (context fails fast). For real deployments, remove this line and
    grant the `FAILOVER_ADMIN` authority instead — see [Security](../support/security.md).

!!! tip "Multiple replicas, zero extra config"
    This YAML is already replica-ready: point every instance at the same database with the same `table-prefix`
    and each one becomes a `@Failover` node, a peer pushing its own snapshot row, and a heartbeat pinger — all
    into the same shared tables. No dashboard "host" process, no ingest URL, no ingest credentials to distribute.

!!! note "Only need part of this?"
    - **Just the `@Failover` store, no dashboard** — stop after step 2 (JDBC).
    - **Dashboard without cluster aggregation** (single instance, no peers) — drop the whole `cluster:` block;
      `mode` defaults to `local` and the dashboard reads only this instance's own metrics.
    - **No instance live tracking** — drop `shared-store.liveness*` and `snapshot.heartbeat*`; snapshot
      aggregation works the same without it, instances just stay `UNKNOWN` instead of `LIVE`/`DOWN`.
    - **HTTP ingest instead of direct write** (peers on a different network than the DB) — see
      [Dashboard → Scenario D](../modules/dashboard.md#scenario-d-cluster-via-shared-store-jdbc-durable) and the
      [deployment-configs reference](../modules/dashboard.md#configuration-how-to).

---

## Next Steps

- [How It Works](../concepts/how-it-works.md) — full lifecycle explanation
- [Properties Reference](../configuration/properties-reference.md) — all configuration options
- [Store Types](../configuration/store-types.md) — choose the right backing store
- [Dashboard](../modules/dashboard.md) — every deployment shape (single JVM, shared-store, Prometheus), copy-pasteable
