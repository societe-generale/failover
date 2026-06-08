---
icon: material/timer-cog-outline
---

# Custom Expiry Policy

Replace the default duration-based TTL with business-calendar-aware expiry, payload-driven expiry, or any other strategy.

---

## Interface

```java
public interface ExpiryPolicy<T> {
    Instant computeExpiry(Failover failover);
    boolean isExpired(Failover failover, ReferentialPayload<T> payload);
}
```

- `computeExpiry` — called at **store time** to compute `expireOn`.
- `isExpired` — called at **recover time** to check whether the stored entry is still valid.

---

## When to Use

- Expiry should align to a business deadline (e.g. end of business day)
- Different payloads of the same type have different TTLs
- Expiry depends on the actual payload content (e.g. a `validUntil` field)

For simple externalisable TTLs, prefer [SpEL expressions](../concepts/expiry.md#spel-expression) over a custom policy class.

---

## Step 1 — Implement ExpiryPolicy

```java title="EndOfDayExpiryPolicy.java"
@Component("endOfDayExpiryPolicy")
public class EndOfDayExpiryPolicy implements ExpiryPolicy<Country> {

    @Override
    public Instant computeExpiry(Failover failover) {
        // expires at end of current business day UTC
        return LocalDate.now(ZoneOffset.UTC)
                .atTime(LocalTime.of(23, 59, 59))
                .toInstant(ZoneOffset.UTC);
    }

    @Override
    public boolean isExpired(Failover failover, ReferentialPayload<Country> payload) {
        return payload.getExpireOn().isBefore(Instant.now());
    }
}
```

---

## Step 2 — Wire to the Annotation

```java
@Failover(
    name = "country-by-code",
    expiryPolicy = "endOfDayExpiryPolicy"
)
Country findByCode(String code);
```

When `expiryPolicy` is set, `expiryDuration` and `expiryUnit` (and their SpEL variants) are ignored.

---

## Example: Payload-Driven Expiry

```java title="ValidUntilExpiryPolicy.java"
@Component("validUntilExpiryPolicy")
public class ValidUntilExpiryPolicy implements ExpiryPolicy<Price> {

    @Override
    public Instant computeExpiry(Failover failover) {
        // not used — expiry is set per-payload in enrichOnStore
        return Instant.now().plus(1, ChronoUnit.HOURS);   // safe fallback
    }

    @Override
    public boolean isExpired(Failover failover, ReferentialPayload<Price> payload) {
        Price price = payload.getPayload();
        return price == null || price.getValidUntil().isBefore(Instant.now());
    }
}
```

---

## Next Steps

- [Expiry Concepts](../concepts/expiry.md) — how expiry works end-to-end
- [Custom Payload Enricher](custom-payload-enricher.md) — set `expireOn` from payload fields at store time
