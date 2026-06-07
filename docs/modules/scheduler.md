---
icon: material/clock-outline
---

# Scheduler Module

`failover-scheduler` provides two background jobs that maintain the health of the failover store.

## Dependency

```xml
<dependency>
    <groupId>com.societegenerale.failover</groupId>
    <artifactId>failover-scheduler</artifactId>
    <version>3.0.0</version>
</dependency>
```

Included transitively via `failover-spring-boot-starter`.

---

## ExpiryCleanupScheduler

Removes all expired entries from the store by calling `FailoverHandler.clean()`.

```yaml
failover:
  scheduler:
    enabled: true
    cleanup-cron: "0 0 * * * *"   # every hour (default)
```

The cleanup calls `FailoverStore.cleanByExpiry(now)`. For JDBC stores this executes:

```sql
DELETE FROM FAILOVER_STORE WHERE EXPIRE_ON < ?
```

!!! tip "Cleanup frequency"
    Tune `cleanup-cron` based on your store size and expiry distribution. More frequent cleanup keeps the JDBC table smaller at the cost of more DELETE queries.

---

## ObservableScheduler

Calls `FailoverObserver.observe()` on a cron schedule — collects metrics from all registered `@Failover` configurations and publishes them to all registered `ObservablePublisher` beans.

```yaml
failover:
  scheduler:
    enabled: true
    report-cron: "0 0 0 * * *"   # daily at midnight (default)
```

Default output: structured log entry via `MdcLoggerObservablePublisher`. Add `failover-observable-micrometer` to also publish Micrometer gauges.

---

## Disabling the Scheduler

```yaml
failover:
  scheduler:
    enabled: false
```

Disables both schedulers. Individual jobs cannot be disabled independently — disable the whole scheduler and implement your own if needed.

---

## Manual Invocation

Trigger cleanup or observation directly from application code:

```java
@Autowired FailoverHandler<Object> handler;

// manual cleanup
handler.clean();
```

```java
@Autowired FailoverObserver failoverObserver;

// manual observe
failoverObserver.observe();
```
