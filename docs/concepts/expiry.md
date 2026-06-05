---
icon: material/timer-outline
---

# Expiry Policies

Every stored entry has an expiry timestamp. Expired entries are never served on recovery — they are deleted on first access or by the cleanup scheduler.

---

## Configuring Expiry on the Annotation

### Fixed Duration

```java
@Failover(name = "country-by-code", expiryDuration = 24, expiryUnit = ChronoUnit.HOURS)
Country findByCode(String code);
```

The default is `1 HOUR` when neither `expiryDuration` nor `expiryDurationExpression` is set.

### SpEL Expression

Externalise expiry values so they can be changed without redeployment:

```yaml title="application.yml"
myapp:
  failover:
    country:
      duration: 48
      unit: DAYS
```

```java
@Failover(
    name = "country-by-code",
    expiryDurationExpression = "${myapp.failover.country.duration}",
    expiryUnitExpression     = "${myapp.failover.country.unit}"
)
Country findByCode(String code);
```

When an expression is set it takes precedence over the plain `expiryDuration` / `expiryUnit` values.

---

## Supported ChronoUnits

Any `java.time.temporal.ChronoUnit` value is valid:

`SECONDS`, `MINUTES`, `HOURS`, `DAYS`, `WEEKS`, `MONTHS`, `YEARS`

Align the unit with the business's acceptable staleness window, not with your deployment cadence.

---

## How Expiry is Computed

`ExpiryPolicy` computes the expiry timestamp at store time:

```
expireOn = now() + expiryDuration × expiryUnit
```

At recover time, the handler checks `expireOn > now()`. If the check fails, the entry is deleted and the exception is re-thrown (or `null` returned, per `ExceptionPolicy`).

---

## Per-Annotation Expiry Policy

Every `@Failover` can name a custom `ExpiryPolicy` bean:

```java
@Failover(name = "pricing", expiryPolicy = "myPricingExpiryPolicy")
List<Price> findPrices(String productId);
```

```java
@Component("myPricingExpiryPolicy")
public class PricingExpiryPolicy implements ExpiryPolicy<List<Price>> {

    @Override
    public LocalDateTime computeExpiry(Failover failover) {
        // expire at next business day midnight
        return LocalDate.now().plusDays(1).atStartOfDay();
    }

    @Override
    public boolean isExpired(Failover failover, ReferentialPayload<List<Price>> payload) {
        return payload.getExpireOn().isBefore(LocalDateTime.now());
    }
}
```

---

## Cleanup Scheduler

Expired entries are removed by `ExpiryCleanupScheduler` on a cron schedule. Configure it in `application.yml`:

```yaml
failover:
  scheduler:
    enabled: true
    cleanup-cron: "0 0 * * * *"   # every hour (default)
```

The cleanup calls `FailoverHandler.clean()` which in turn calls `FailoverStore.cleanByExpiry(now)`.

!!! note "On-demand cleanup"
    You can also call `FailoverHandler.clean()` directly from your code — for example, after a bulk data refresh.
