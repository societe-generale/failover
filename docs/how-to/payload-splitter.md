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
