---
icon: material/scissors-cutting
---

# Payload Splitter

Implement `PayloadSplitter` to enable scatter/gather mode — stores each entity in a collection individually under its own key and recovers them independently, enabling partial recovery.

---

## Interface

```java
public interface PayloadSplitter<T, R> {
    List<StoreContext<R>> splitOnStore(StoreContext<T> context);
    List<RecoverContext<R>> splitOnRecover(RecoverContext<T> context);
    RecoverContext<T> merge(List<RecoverContext<R>> contexts);
}
```

| Type | Meaning |
|---|---|
| `T` | Composite type — what the annotated method returns (`List<Country>`) |
| `R` | Slice type — what is stored per entity (`Country`) |

---

## Step 1 — Implement PayloadSplitter

```java title="CountrySplitter.java"
@Component("countrySplitter")
public class CountrySplitter implements PayloadSplitter<List<Country>, Country> {

    @Override
    public List<StoreContext<Country>> splitOnStore(StoreContext<List<Country>> ctx) {
        String[] codes = ((String) ctx.getArgs().get(0)).split(",");
        List<Country> countries = ctx.getPayload();

        return IntStream.range(0, countries.size())
            .mapToObj(i -> StoreContext.<Country>builder()
                .failover(ctx.getFailover())
                .args(List.of(codes[i].trim()))  // single-code args → key derivation
                .payload(countries.get(i))
                .build())
            .toList();
    }

    @Override
    public List<RecoverContext<Country>> splitOnRecover(RecoverContext<List<Country>> ctx) {
        String csv = (String) ctx.getArgs().get(0);
        return Arrays.stream(csv.split(","))
            .map(code -> RecoverContext.<Country>builder()
                .failover(ctx.getFailover())
                .args(List.of(code.trim()))
                .clazz(Country.class)
                .cause(ctx.getCause())
                .build())
            .toList();
    }

    @Override
    public RecoverContext<List<Country>> merge(List<RecoverContext<Country>> contexts) {
        List<Country> recovered = contexts.stream()
            .map(RecoverContext::getPayload)
            .filter(Objects::nonNull)
            .toList();
        return contexts.get(0).toBuilder()
            .clazz((Class) List.class)
            .payload(recovered)
            .build();
    }
}
```

The `args` list in each split `StoreContext` must produce the same key as a direct `findByCode("FR")` call so that domain sharing works correctly.

---

## Step 2 — Wire to the Annotation

```java
@Failover(
    name = "countries-by-codes",
    domain = "country",
    payloadSplitter = "countrySplitter",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
List<Country> findByCodes(@RequestParam String codes);
```

---

## Partial Recovery — Null Policy in `merge`

When several keys are recovered at once (e.g. `findByCodes("FR,DE,US")`), some keys may have a stored
entry and others may not — a **partial recovery**. Each unrecovered slice arrives at `merge` as a
`RecoverContext` with a **`null` payload** (a cache miss, an expired entry, and a timed-out slice are
all indistinguishable here — all `null`). The framework does **not** decide what to do with those
nulls; **your `merge` implementation owns the policy**. There are two sensible choices, and the right
one depends on whether the caller needs to know *which* entries are missing.

### How the slices line up

`splitOnRecover` returns one `RecoverContext` per key, in order; the gather recovers each independently
and passes the **same-ordered** list to `merge` — slot *i* in `merge`'s input corresponds to key *i*
from `splitOnRecover`. So position carries meaning: index 1 is always `"DE"`'s result, recovered or not.

```
splitOnRecover("FR,DE,US") → [ ctx(FR), ctx(DE), ctx(US) ]
recover each slice          →   FR=hit   DE=miss   US=hit
merge receives              → [ ctx(FR, payload=fr), ctx(DE, payload=null), ctx(US, payload=us) ]
```

### Option A — Keep `null` at the missing positions (positional)

Preserve the slot so the caller can see *exactly which* keys are missing. The returned list stays the
same length and order as the requested keys.

```java
@Override
public RecoverContext<List<Country>> merge(List<RecoverContext<Country>> contexts) {
    List<Country> recovered = contexts.stream()
        .map(RecoverContext::getPayload)   // keep nulls — null at index i = key i not recovered
        .toList();
    return contexts.get(0).toBuilder()
        .clazz((Class) List.class)
        .payload(recovered)                // e.g. [Country(FR), null, Country(US)]
        .build();
}
```

Use when the caller maps results back to the requested keys by index (e.g. `codes[i] → result[i]`)
and must distinguish "missing" from "present". The caller **must** be null-tolerant.

### Option B — Drop the `null`s (compact)

Return only what was recovered. The list is shorter than the requested keys; positional mapping is
lost.

```java
@Override
public RecoverContext<List<Country>> merge(List<RecoverContext<Country>> contexts) {
    List<Country> recovered = contexts.stream()
        .map(RecoverContext::getPayload)
        .filter(Objects::nonNull)          // drop missing slices
        .toList();
    return contexts.get(0).toBuilder()
        .clazz((Class) List.class)
        .payload(recovered)                // e.g. [Country(FR), Country(US)]
        .build();
}
```

Use when the caller just wants "whatever is available" and does not correlate results to input
positions. This is what the [Step 1 example](#step-1-implement-payloadsplitter) does.

### Option C — Reject the whole composite on any miss

If a partial list is unsafe (the caller cannot tell it is incomplete and might act on it), return a
`null` payload when **any** slice is missing, so the whole recovery is treated as a non-recovery
(subject to your [`ExceptionPolicy`](exception-policy.md)) rather than silently returning a short list:

```java
@Override
public RecoverContext<List<Country>> merge(List<RecoverContext<Country>> contexts) {
    boolean anyMissing = contexts.stream().anyMatch(c -> c.getPayload() == null);
    if (anyMissing) {
        return contexts.get(0).toBuilder().clazz((Class) List.class).payload(null).build();
    }
    List<Country> all = contexts.stream().map(RecoverContext::getPayload).toList();
    return contexts.get(0).toBuilder().clazz((Class) List.class).payload(all).build();
}
```

!!! note "The framework only signals partial recovery — it does not decide"
    The gather logs a `WARN` ("PARTIAL recovery, N of M slices missing") so it is never silent, but the
    *policy* is yours: keep positional nulls (A), compact (B), or reject (C). Pick the one that matches
    how your callers consume the list. If **every** slice is empty, `merge` is not called at all — the
    recovery returns `null` directly. See [Scatter/Gather — Partial Recovery Behaviour](../concepts/scatter-gather.md#partial-recovery-behaviour).

---

## Parallel Dispatch

By default, each slice's store/recover runs on a virtual-thread executor in parallel:

```yaml title="application.yml"
failover:
  scatter:
    parallel: true
```

If your operation uses thread-local state (tenant context, MDC), implement a `ContextPropagator` to propagate it across threads — see [Context Propagation](context-propagation.md).

---

## findAll() Splitter — No-ID-Args Pattern

When the annotated method has no arguments (a pure `findAll()`), standard scatter/gather cannot split args into per-entity keys. Use a **dedicated splitter** for this case whose `splitOnRecover` returns a single placeholder context pointing at the slice class.

### Implementation

```java title="CountryAllSplitter.java"
@Component("countryAllSplitter")
public class CountryAllSplitter implements PayloadSplitter<List<Country>, Country> {

    // Store path: identical to CountrySplitter — split the result into per-entity slices
    @Override
    public List<StoreContext<Country>> splitOnStore(StoreContext<List<Country>> ctx) {
        return ctx.getPayload().stream()
            .map(country -> StoreContext.<Country>builder()
                .failover(ctx.getFailover())
                .args(List.of(country.getCode()))   // entity-identity key
                .payload(country)
                .build())
            .toList();
    }

    // Recover path: return ONE placeholder — args are not entity IDs here
    @Override
    public List<RecoverContext<Country>> splitOnRecover(RecoverContext<List<Country>> ctx) {
        return List.of(RecoverContext.<Country>builder()
            .failover(ctx.getFailover())
            .args(ctx.getArgs())           // pass-through (empty for findAll)
            .clazz(Country.class)          // REQUIRED — tells delegateR what type to recover
            .cause(ctx.getCause())
            .build());
    }

    @Override
    public RecoverContext<List<Country>> merge(List<RecoverContext<Country>> contexts) {
        List<Country> recovered = contexts.stream()
            .map(RecoverContext::getPayload)
            .filter(Objects::nonNull)
            .toList();
        return contexts.get(0).toBuilder()
            .clazz((Class) List.class)
            .payload(recovered)
            .build();
    }
}
```

### Wire to the Annotation

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

No `recoverAll = true` needed — empty args trigger the recover-all path automatically.

---

## recoverAll = true — Filter-Args Pattern

When the method has arguments that are **filters** (not entity IDs), add `recoverAll = true` to force the recover-all path even though args are non-empty.

```java
@Failover(
    name = "countries-by-status",
    domain = "country",
    payloadSplitter = "countryAllSplitter",   // same as findAll — ignores args on recover
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS,
    recoverAll = true
)
List<Country> findByStatus(String status, String region);
```

`CountryAllSplitter.splitOnRecover` receives `args = ["active", "EU"]`. It ignores them and returns the single placeholder. `DefaultFailoverHandler.recoverAll` fetches all slices by name under `domain = "country"`.

!!! tip
    Do NOT reuse the ID-based `countrySplitter` here — its `splitOnRecover` reads `args.get(0)` as a CSV of entity IDs. With filter args that splitter would produce wrong keys and recover nothing.

---

## Two-Splitter Pattern — Batch Fetch + findAll on the Same Domain

Share store entries between a batch endpoint and a `findAll` endpoint by assigning both to the same `domain`:

```java
// Batch by IDs: splits CSV into per-entity keys
@Failover(
    name = "countries-by-ids",
    domain = "country",
    payloadSplitter = "countrySplitter",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
List<Country> findByIds(String csvIds);

// FindAll: no args, uses a splitter whose splitOnRecover returns one placeholder
@Failover(
    name = "all-countries",
    domain = "country",
    payloadSplitter = "countryAllSplitter",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
List<Country> findAll();
```

A successful `findByIds("FR,DE,US")` stores three slices. On failure, `findAll()` recovers all three from the same `"country"` store partition.

---

## Handling PayloadSplitterExecutionException

Any exception thrown inside `splitOnStore`, `splitOnRecover`, or `merge` is wrapped in `PayloadSplitterExecutionException` with full context. Catch it at the service layer when you need to react to splitter bugs without crashing the caller:

```java
try {
    return countryService.findAll();
} catch (PayloadSplitterExecutionException ex) {
    log.error("Splitter '{}' failed in '{}' for failover '{}': {}",
        ex.getCause().getClass().getSimpleName(),
        // parse from ex.getMessage() or subclass with structured fields
        ex.getMessage(), ex);
    return Collections.emptyList();
}
```

The message includes: splitter name, operation (`splitOnStore` / `splitOnRecover` / `merge`), failover name, expiry config, domain, and the original cause message — sufficient to diagnose the failure without a debugger.

---

## Next Steps

- [Annotation Reference](../reference/annotation.md) — `recoverAll`, `domain`, full attribute table
- [Scatter / Gather Concepts](../concepts/scatter-gather.md) — findAll path, dedup in merge, PayloadSplitterExecutionException
- [Domain Grouping](../concepts/domain.md) — sharing store entries across failovers
- [Context Propagation](context-propagation.md) — thread-local context across parallel slices
