---
icon: material/timer-outline
---

# Expiry Policies

Every stored entry carries an `expireOn` timestamp. Expired entries are never served on recovery — they are deleted on first access and by the hourly cleanup scheduler.

---

## Configuring Expiry on the Annotation

### Fixed Duration

```java
@Failover(name = "country-by-code", expiryDuration = 24, expiryUnit = ChronoUnit.HOURS)
Country findByCode(String code);
```

Default when no expiry is configured: `1 HOUR`.

### SpEL Expression

Externalise TTL values so they can be changed without redeployment:

```yaml title="application.yml"
myapp:
  country:
    expiry-duration: 48
    expiry-unit: HOURS
```

```java
@Failover(
    name = "country-by-code",
    expiryDurationExpression = "${myapp.country.expiry-duration}",
    expiryUnitExpression     = "${myapp.country.expiry-unit}"
)
Country findByCode(String code);
```

When an expression is set it takes precedence over the plain `expiryDuration` / `expiryUnit` values.

---

## Supported ChronoUnits

Any `java.time.temporal.ChronoUnit` is valid:

`SECONDS` · `MINUTES` · `HOURS` · `DAYS` · `WEEKS` · `MONTHS` · `YEARS`

!!! tip "Align TTL with business staleness tolerance"
    Set TTL to the maximum time a stale value is acceptable to your business — not to your deployment cadence. Country codes tolerate days; real-time prices may only tolerate minutes.

---

## How Expiry is Computed

`DefaultExpiryPolicy` computes the expiry timestamp at store time:

```
expireOn = now() + expiryDuration × expiryUnit
```

At recover time, `isExpired` checks:

```
expireOn.isBefore(Instant.now())
```

If expired, the entry is deleted from the store and the exception is re-thrown (or `null` is returned, per `exception-policy`).

---

## Expiry Strategy Comparison

| Strategy | Use case | Code |
|---|---|---|
| Fixed annotation values | Simple, static TTL | `expiryDuration=24, expiryUnit=HOURS` |
| SpEL expression | Configurable TTL per environment | `expiryDurationExpression="${app.ttl}"` |
| Custom `ExpiryPolicy` bean | Business-calendar or payload-driven expiry | `expiryPolicy="myPolicy"` |

---

## Custom ExpiryPolicy

```java title="NextDayExpiryPolicy.java"
@Component("nextDayExpiryPolicy")
public class NextDayExpiryPolicy implements ExpiryPolicy<List<Price>> {

    @Override
    public Instant computeExpiry(Failover failover) {
        // expires at midnight UTC of the next business day
        return LocalDate.now(ZoneOffset.UTC)
                .plusDays(1)
                .atStartOfDay(ZoneOffset.UTC)
                .toInstant();
    }

    @Override
    public boolean isExpired(Failover failover, ReferentialPayload<List<Price>> payload) {
        return payload.getExpireOn().isBefore(Instant.now());
    }
}
```

```java
@Failover(name = "prices", expiryPolicy = "nextDayExpiryPolicy")
List<Price> findPrices(String productId);
```

---

## Cleanup Scheduler

Expired entries accumulate until cleared. `ExpiryCleanupScheduler` runs `FailoverHandler.clean()` on a cron schedule:

```yaml title="application.yml"
failover:
  scheduler:
    enabled: true
    cleanup-cron: "0 0 * * * *"   # every hour (default)
```

`clean()` calls `FailoverStore.cleanByExpiry(Instant.now())` which removes all entries whose `EXPIRE_ON` is before the current timestamp.

!!! note "On-demand cleanup"
    Inject `FailoverHandler` and call `clean()` directly when you need an immediate purge — for example, after a bulk data refresh.

---

## Next Steps

- [Key Generation](key-generation.md) — how store keys are derived from method arguments
- [Properties Reference](../configuration/properties-reference.md) — `failover.scheduler.*` configuration
- [Custom Expiry Policy](../how-to/custom-expiry-policy.md) — step-by-step implementation guide
