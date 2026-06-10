---
icon: material/tune
---

# Configure @Failover

`@Failover` supports several configuration styles — from a single hardcoded expiry to fully expression-driven, environment-specific values. This guide covers all options with examples.

---

## 1. Fixed Expiry (Same Across All Environments)

Use `expiryDuration` and `expiryUnit` when the expiry value is the same in every environment:

```java
@Failover(
    name = "country-by-code",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
Country findByCode(String code);
```

`expiryUnit` accepts any `java.time.temporal.ChronoUnit` constant: `MINUTES`, `HOURS`, `DAYS`, `WEEKS`, `MONTHS`, `YEARS`.

---

## 2. Expression-Based Expiry (Environment-Specific Values)

Use `expiryDurationExpression` and `expiryUnitExpression` to resolve expiry from application properties, environment variables, or any Spring EL expression. When either expression is set, it takes priority over the corresponding fixed attribute.

### Property / YAML file

```java
@Failover(
    name = "country-by-code",
    expiryDurationExpression = "${failover.country.expiry-duration:24}",
    expiryUnitExpression     = "${failover.country.expiry-unit:HOURS}"
)
Country findByCode(String code);
```

```yaml title="application-prod.yml"
failover:
  country:
    expiry-duration: 72
    expiry-unit: HOURS
```

```yaml title="application-dev.yml"
failover:
  country:
    expiry-duration: 5
    expiry-unit: MINUTES
```

Each profile overrides the expiry independently. The default values (`24` / `HOURS`) apply when the property is absent.

### Environment variable

```java
@Failover(
    name = "country-by-code",
    expiryDurationExpression = "${FAILOVER_COUNTRY_EXPIRY_DURATION:24}",
    expiryUnitExpression     = "${FAILOVER_COUNTRY_EXPIRY_UNIT:HOURS}"
)
Country findByCode(String code);
```

Set environment variables for production containers:

```bash
FAILOVER_COUNTRY_EXPIRY_DURATION=48
FAILOVER_COUNTRY_EXPIRY_UNIT=HOURS
```

### Spring EL (computed value)

```java
@Failover(
    name = "country-by-code",
    expiryDurationExpression = "#{@failoverProperties.countryExpiryDuration}",
    expiryUnitExpression     = "#{@failoverProperties.countryExpiryUnit}"
)
Country findByCode(String code);
```

Any valid Spring EL expression is accepted: bean property access, arithmetic, conditionals.

---

## 3. Custom Key Generator

By default, the key is derived from the method arguments (joined and hashed to a UUID). Override it with a named bean:

```java
@Component("countryKeyGen")
public class CountryKeyGenerator implements KeyGenerator {
    @Override
    public String key(Failover failover, List<Object> args) {
        // normalise to uppercase for case-insensitive lookup
        return ((String) args.get(0)).toUpperCase(Locale.ROOT);
    }
}
```

```java
@Failover(
    name = "country-by-code",
    keyGenerator = "countryKeyGen",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
Country findByCode(String code);
```

---

## 4. Custom Expiry Policy

Override how expiry is calculated for a specific failover:

```java
@Component("countryExpiryPolicy")
public class CountryExpiryPolicy implements ExpiryPolicy<Country> {
    @Override
    public Instant computeExpiry(Failover failover) {
        // expires at midnight UTC
        return LocalDate.now(ZoneOffset.UTC).plusDays(1)
                .atStartOfDay(ZoneOffset.UTC).toInstant();
    }

    @Override
    public boolean isExpired(Failover failover, ReferentialPayload<Country> payload) {
        return payload.getExpireOn().isBefore(Instant.now());
    }
}
```

```java
@Failover(
    name = "country-by-code",
    expiryPolicy = "countryExpiryPolicy",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
Country findByCode(String code);
```

When `expiryPolicy` is set, `computeExpiry` on the custom policy is called — `expiryDuration` / `expiryUnit` are only used by `isExpired` if the custom policy delegates to the default expiry check.

---

## 5. Domain Grouping

Share store entries between a single-entity endpoint and a scatter/gather list endpoint:

```java
// Single-entity: stores under domain "country", key derived from "FR"
@Failover(
    name = "country-by-code",
    domain = "country",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
Country findByCode(String code);

// Batch: scatter-stores each entity individually under domain "country"
@Failover(
    name = "countries-by-codes",
    domain = "country",
    payloadSplitter = "countrySplitter",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
List<Country> findByCodes(String csvCodes);
```

A successful `findByCodes("FR,DE")` stores `FR` and `DE` individually. On failure, `findByCode("FR")` recovers `FR` from the same store partition without a separate `findByCodes` call ever having succeeded.

!!! warning
    All `@Failover` annotations in the same `domain` must use the same expiry configuration. Mismatched expiry causes the last writer to overwrite the stored expiry timestamp. A startup `WARN` is logged when mismatched expiry is detected.

---

## 6. PayloadSplitter + recoverAll Combinations

### 6a. Batch by IDs (standard scatter/gather)

```java
@Failover(
    name = "countries-by-ids",
    domain = "country",
    payloadSplitter = "countrySplitter",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
List<Country> findByIds(String csvIds);
```

`countrySplitter.splitOnRecover` reads `args.get(0)` (the CSV), splits on `,`, returns one context per ID. Each context drives one `delegateR.recover()` call. No `recoverAll` needed.

### 6b. findAll() — No Args

```java
@Failover(
    name = "all-countries",
    domain = "country",
    payloadSplitter = "countryAllSplitter",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
List<Country> findAll();
```

Empty args automatically trigger the recover-all path. `countryAllSplitter.splitOnRecover` returns one placeholder context. `delegateR.recoverAll` fetches all slices under `domain = "country"`.

### 6c. Filter Args — Non-ID Arguments

```java
@Failover(
    name = "countries-by-region",
    domain = "country",
    payloadSplitter = "countryAllSplitter",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS,
    recoverAll = true
)
List<Country> findByRegion(String region);
```

`region` is a filter, not an entity ID. Without `recoverAll = true`, scatter would try to split `"EU"` into entity keys (wrong). With `recoverAll = true`, the recover-all path is forced regardless of non-empty args. `countryAllSplitter.splitOnRecover` ignores args and returns one placeholder.

### Summary

| Pattern | Args type | `recoverAll` | Splitter `splitOnRecover` |
|---|---|---|---|
| Batch by IDs | Entity IDs (CSV) | `false` (default) | Split args into per-entity contexts |
| findAll() | None | Not needed | Return 1 placeholder context |
| Filter-only args | Non-ID filters | `true` | Return 1 placeholder context (ignore args) |

---

## Full Example — All Options Together

```java
@Failover(
    name                    = "countries-by-codes",
    domain                  = "country",
    expiryDurationExpression = "${failover.country.expiry-duration:24}",
    expiryUnitExpression     = "${failover.country.expiry-unit:HOURS}",
    keyGenerator            = "countryKeyGen",
    expiryPolicy            = "countryExpiryPolicy",
    payloadSplitter         = "countrySplitter"
)
List<Country> findByCodes(String csvCodes);
```

Resolution order:

1. `expiryDurationExpression` (non-blank) → overrides `expiryDuration`
2. `expiryUnitExpression` (non-blank) → overrides `expiryUnit`
3. `expiryPolicy` (non-blank) → overrides default expiry policy (but `expiryDuration`/`expiryUnit` still supply defaults when the policy delegates to them)
4. `keyGenerator` (non-blank) → overrides default key generator
5. `payloadSplitter` (non-blank) → enables scatter/gather mode

---

## Next Steps

- [Annotation Reference](../reference/annotation.md) — complete attribute table and per-attribute detail
- [Scatter / Gather Concepts](../concepts/scatter-gather.md) — how `payloadSplitter` + `recoverAll` work at runtime
- [Payload Splitter How-to](payload-splitter.md) — step-by-step splitter implementation
- [Domain Grouping](../concepts/domain.md) — sharing entries across failovers
