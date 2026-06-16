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

## Testing Your Policy

The library ships a dependency-free harness, `ExpiryPolicyContractVerifier`, that checks your policy
against the contract every implementation must honour — a non-null, future `computeExpiry`, and an
`isExpired` that actually reads the stored `expireOn`. Drop it into a unit test:

```java title="EndOfDayExpiryPolicyTest.java"
@ExtendWith(MockitoExtension.class)
class EndOfDayExpiryPolicyTest {

    @Mock Failover failover;

    @Test
    void honoursTheExpiryContract() {
        given(failover.expiryDuration()).willReturn(1L);
        given(failover.expiryUnit()).willReturn(ChronoUnit.HOURS);

        ExpiryPolicyContractVerifier.forPolicy(new EndOfDayExpiryPolicy())
                .withFailover(failover)
                .withSamplePayload(new Country("FR"))
                .verify();   // throws AssertionError on the first violation
    }
}
```

The harness verifies the **standard `expireOn`-based contract**. If your policy is *payload-driven*
(it derives expiry from a field on the payload instead of `expireOn`), call `verifyComputeExpiry()`
for the `computeExpiry` checks only, and assert your bespoke `isExpired` logic directly.

---

## Next Steps

- [Expiry Concepts](../concepts/expiry.md) — how expiry works end-to-end
- [Custom Payload Enricher](custom-payload-enricher.md) — set `expireOn` from payload fields at store time
