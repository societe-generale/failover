---
icon: material/timer-cog-outline
---

# Custom Expiry Policy

Use a custom `ExpiryPolicy` when:

- Expiry depends on business calendar (e.g. expire at next business day close).
- Expiry depends on payload content (e.g. longer TTL for premium data).
- You need runtime-computed expiry that cannot be expressed as a fixed duration.

---

## Implement ExpiryPolicy

```java
@Component("businessDayExpiryPolicy")
public class BusinessDayExpiryPolicy implements ExpiryPolicy<Country> {

    @Override
    public LocalDateTime computeExpiry(Failover failover) {
        // expire at midnight of the next business day
        LocalDate nextBusinessDay = nextBusinessDay(LocalDate.now());
        return nextBusinessDay.atStartOfDay();
    }

    @Override
    public boolean isExpired(Failover failover, ReferentialPayload<Country> payload) {
        return payload.getExpireOn().isBefore(LocalDateTime.now());
    }

    private LocalDate nextBusinessDay(LocalDate date) {
        LocalDate next = date.plusDays(1);
        while (next.getDayOfWeek() == DayOfWeek.SATURDAY ||
               next.getDayOfWeek() == DayOfWeek.SUNDAY) {
            next = next.plusDays(1);
        }
        return next;
    }
}
```

---

## Reference by Bean Name

```java
@Failover(
    name = "country-by-code",
    expiryPolicy = "businessDayExpiryPolicy"
)
Country findByCode(String code);
```

The `expiryPolicy` attribute accepts the Spring bean name. When set, it overrides `expiryDuration`, `expiryDurationExpression`, `expiryUnit`, and `expiryUnitExpression` for that method.

---

## Generic Expiry Policy

Make the policy generic to reuse it across different payload types:

```java
@Component("midnightExpiryPolicy")
public class MidnightExpiryPolicy<T> implements ExpiryPolicy<T> {

    @Override
    public LocalDateTime computeExpiry(Failover failover) {
        return LocalDate.now().plusDays(1).atStartOfDay();
    }

    @Override
    public boolean isExpired(Failover failover, ReferentialPayload<T> payload) {
        return payload.getExpireOn().isBefore(LocalDateTime.now());
    }
}
```

---

## Default ExpiryPolicy Behaviour

The default `ExpiryPolicy` computes:

```
expireOn = now() + expiryDuration × expiryUnit
```

And checks:

```
isExpired = payload.expireOn < now()
```

Custom policies must implement both `computeExpiry` (called at store time) and `isExpired` (called at recover time) consistently.
