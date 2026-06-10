---
icon: material/at
---

# @Failover Annotation

`@Failover` is a method-level annotation that declares failover behaviour. Place it on any Spring-managed bean method.

```java
@Retention(RetentionPolicy.RUNTIME)
@Target(ElementType.METHOD)
@Documented
public @interface Failover { ... }
```

---

## Attributes

| Attribute                  | Type         | Default      | Description                                                                                                                                                                           |
|----------------------------|--------------|--------------|---------------------------------------------------------------------------------------------------------------------------------------------------------------------------------------|
| `name`                     | `String`     | *(required)* | Unique identifier for this failover. Used as `FAILOVER_NAME` in the store (unless `domain` is set). Must be unique across the application.                                            |
| `expiryDuration`           | `long`       | `1`          | Numeric part of the TTL. Combined with `expiryUnit`. Ignored when `expiryDurationExpression` is non-empty.                                                                            |
| `expiryUnit`               | `ChronoUnit` | `HOURS`      | Unit part of the TTL. Any `java.time.temporal.ChronoUnit` is valid. Ignored when `expiryUnitExpression` is non-empty.                                                                 |
| `expiryDurationExpression` | `String`     | `""`         | Spring property placeholder or SpEL expression that evaluates to a `long`. Overrides `expiryDuration` when non-empty.                                                                 |
| `expiryUnitExpression`     | `String`     | `""`         | Spring property placeholder or SpEL expression that evaluates to a `ChronoUnit` name string. Overrides `expiryUnit` when non-empty.                                                   |
| `keyGenerator`             | `String`     | `""`         | Bean name of a custom `KeyGenerator`. When empty, `DefaultKeyGenerator` is used.                                                                                                      |
| `expiryPolicy`             | `String`     | `""`         | Bean name of a custom `ExpiryPolicy`. When empty, `DefaultExpiryPolicy` is used.                                                                                                      |
| `payloadSplitter`          | `String`     | `""`         | Bean name of a `PayloadSplitter`. When set, enables scatter/gather mode. When empty, standard single-key behaviour applies.                                                           |
| `recoverAll`               | `boolean`    | `false`      | When `true`, the scatter recover-all path is taken even when `args` is non-empty. Use for `findAll`-style methods that carry filter args (e.g. status, region) that are NOT entity-identity args and should not be used to derive individual recovery keys. Ignored when `payloadSplitter` is empty. |
| `domain`                   | `String`     | `""`         | Optional logical namespace. When set, both key hashing and `FAILOVER_NAME` use `domain` instead of `name`, enabling multiple `@Failover` annotations to share the same store entries. |

---

## Attribute Details

### name

Uniquely identifies this failover definition. Used in log messages, the startup scanner report, and (when `domain` is empty) as the store namespace.

```java
@Failover(name = "country-by-code")
Country findByCode(String code);
```

### expiryDuration + expiryUnit

Fixed TTL configured directly on the annotation:

```java
@Failover(name = "country-by-code", expiryDuration = 24, expiryUnit = ChronoUnit.HOURS)
Country findByCode(String code);
```

### expiryDurationExpression + expiryUnitExpression

Externalisable TTL via Spring property placeholders:

```java
@Failover(
    name = "exchange-rates",
    expiryDurationExpression = "${app.failover.rates.duration:1}",
    expiryUnitExpression     = "${app.failover.rates.unit:HOURS}"
)
ExchangeRates fetchRates(String base);
```

```yaml
app.failover.rates.duration: 30
app.failover.rates.unit: MINUTES
```

When expression attributes are set, they take precedence over the plain numeric attributes.

### keyGenerator

Override key derivation for complex or order-sensitive arguments:

```java
@Failover(name = "entities-by-ids", keyGenerator = "sortedCsvKeyGenerator")
List<Entity> findByIds(String csvIds);
```

### expiryPolicy

Override TTL computation with a custom `ExpiryPolicy` bean:

```java
@Failover(name = "prices", expiryPolicy = "endOfDayExpiryPolicy")
List<Price> findPrices(String productId);
```

### payloadSplitter

Enable scatter/gather mode:

```java
@Failover(
    name = "countries-by-codes",
    domain = "country",
    payloadSplitter = "countrySplitter",
    expiryDuration = 24, expiryUnit = ChronoUnit.HOURS
)
List<Country> findByCodes(String codes);
```

### recoverAll

Forces scatter recover-all behaviour regardless of whether the method has arguments.

**When to use:**

| Scenario | Method signature | `recoverAll` |
|---|---|---|
| No args — pure `findAll()` | `List<T> findAll()` | Not needed — empty args trigger recover-all automatically |
| Non-ID filter args | `List<T> findAll(String status, String region)` | `true` — args exist but are not entity IDs; scatter must fetch all stored slices |
| ID args (batch by ID) | `List<T> findByIds(String csvIds)` | `false` (default) — IDs split into per-slice keys via `splitOnRecover` |

**No args — automatic (`findAll()`):**

```java
@Failover(
    name = "all-countries",
    domain = "country",
    payloadSplitter = "countrySplitter",
    expiryDuration = 24, expiryUnit = ChronoUnit.HOURS
)
List<Country> findAll();
```

Recovery automatically routes to `findAll` path because args are empty.

**Non-ID filter args — explicit `recoverAll = true`:**

```java
@Failover(
    name = "countries-by-status",
    domain = "country",
    payloadSplitter = "countryAllSplitter",
    expiryDuration = 24, expiryUnit = ChronoUnit.HOURS,
    recoverAll = true
)
List<Country> findByStatus(String status, String region);
```

`status` and `region` are filters, not entity IDs. Without `recoverAll = true`, scatter would try to split `status`/`region` into per-slice keys and look up individual entries — which would all miss because entries were stored by entity ID.

!!! warning
    `recoverAll = true` with a `PayloadSplitter` whose `splitOnRecover` reads args will throw
    `IndexOutOfBoundsException` or produce wrong slices. Use a **separate `PayloadSplitter` bean**
    for the recover-all path — one whose `splitOnRecover` returns a single placeholder context
    (ignoring or preserving filter args) and whose `delegateR.recoverAll()` fetches all slices by name.
    See [Payload Splitter How-to](../how-to/payload-splitter.md) for the full two-splitter pattern.

### domain

Share store entries across `@Failover` annotations for the same business entity:

```java
@Failover(name = "country-by-code", domain = "country")
Country findByCode(String code);

@Failover(name = "countries-by-codes", domain = "country", payloadSplitter = "countrySplitter")
List<Country> findByCodes(String codes);
```

Both annotations now read from and write to the `"country"` namespace. A scatter store populated by `findByCodes` is accessible to `findByCode`.

!!! warning "Expiry consistency"
    All `@Failover` annotations sharing a domain should use the same TTL. Mismatches are warned at startup; last writer wins.

---

## Next Steps

- [Key Interfaces](interfaces.md) — SPI contracts
- [Domain Grouping](../concepts/domain.md) — `domain` attribute deep-dive
- [Scatter / Gather](../concepts/scatter-gather.md) — `payloadSplitter` usage
- [Scatter / Gather How-to](../how-to/payload-splitter.md) — `findAll()` and `recoverAll` patterns
