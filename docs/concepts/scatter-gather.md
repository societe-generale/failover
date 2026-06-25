---
icon: material/scatter-plot
---

# Scatter / Gather

Standard failover stores the entire method result under one key. For collection-returning methods this means a single upstream failure wipes out all cached entries at once and partial recovery is impossible. Scatter/gather solves both problems.

!!! info "When to adopt — and when not to"
    Scatter/gather is the most powerful and the most involved failover feature (a `PayloadSplitter` to
    implement, parallelism, per-slice timeouts, partial-recovery semantics). **Default to single-key
    `@Failover`** — do *not* set `payloadSplitter`/`recoverAll` — unless you specifically need
    **per-entity slicing**: independent expiry/recovery per element and partial recovery of a collection
    result. If a whole-collection cache entry is acceptable, single-key is simpler and sufficient.

    `payloadSplitter` and `recoverAll` go together: **`recoverAll` requires a `PayloadSplitter`.** Setting
    `recoverAll=true` without one has no effect — the framework logs a startup `WARN` and the call falls
    back to single-key recover.

---

## The Problem with Single-Key Collections

Without scatter/gather:

```
store: findAll() → stores ALL countries under ONE key "NO-ARG"
fail:  one country service error → ALL countries lost together
```

With scatter/gather:

```
store: findAll() → splits → stores FR, DE, US, ... each under its own key
fail:  partial failure → FR and DE recovered; US missing → partial result returned
```

---

## How It Works

```mermaid
sequenceDiagram
    participant C as Caller
    participant H as ScatterGatherHandler
    participant S as FailoverStore

    note over C,S: Store path (upstream success)
    C->>H: findByCodes("FR,DE,US") → [FR, DE, US]
    H->>H: splitOnStore → [ctx(FR), ctx(DE), ctx(US)]
    H->>S: store(key=FR, payload=FR)
    H->>S: store(key=DE, payload=DE)
    H->>S: store(key=US, payload=US)
    H-->>C: [FR, DE, US]

    note over C,S: Recover path (upstream failure)
    C->>H: findByCodes("FR,DE,US") → exception
    H->>H: splitOnRecover → [ctx(FR), ctx(DE), ctx(US)]
    H->>S: find(key=FR) → FR ✅
    H->>S: find(key=DE) → DE ✅
    H->>S: find(key=US) → null (expired)
    H->>H: merge([FR, DE, null]) → [FR, DE]
    H-->>C: [FR, DE]
```

---

## PayloadSplitter Interface

```java
public interface PayloadSplitter<T, R> {

    // splits composite result into per-entity store contexts
    List<StoreContext<R>> splitOnStore(StoreContext<T> context);

    // splits composite args into per-entity recover contexts
    List<RecoverContext<R>> splitOnRecover(RecoverContext<T> context);

    // merges per-entity recovered contexts back into composite result
    RecoverContext<T> merge(List<RecoverContext<R>> contexts);
}
```

| Type parameter | Meaning |
|---|---|
| `T` | The composite type — what the annotated method returns |
| `R` | The slice type — what is stored per individual entity |

!!! tip "Base classes do the plumbing"
    The example below implements the interface directly to show the full flow. In practice extend
    `AbstractListPayloadSplitter<T>` (for a `List<T>` result) or `AbstractPayloadSplitter<T, R>` (for a
    wrapper) and implement only the domain hooks — see the
    [Payload Splitter How-to](../how-to/payload-splitter.md#base-classes-abstractlistpayloadsplitter-abstractpayloadsplitter).

### StoreContext Fields

| Field | Type | Description |
|---|---|---|
| `failover` | `Failover` | The annotation metadata |
| `args` | `List<Object>` | Method arguments (full composite args on input; single-entity args per slice) |
| `payload` | `T` | The composite payload (full list on input; single entity per slice) |

### RecoverContext Fields

| Field | Type | Description |
|---|---|---|
| `failover` | `Failover` | The annotation metadata |
| `args` | `List<Object>` | Method arguments |
| `clazz` | `Class<T>` | The slice type |
| `cause` | `Throwable` | The upstream exception that triggered recovery |

---

## Full Implementation Example

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
                .args(List.of(codes[i].trim()))   // single-code args for key derivation
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
        List<Country> result = contexts.stream()
            .map(RecoverContext::getPayload)
            .filter(Objects::nonNull)
            .toList();
        return contexts.get(0).toBuilder()
            .clazz((Class) List.class)
            .payload(result)
            .build();
    }
}
```

### Wire to the Annotation

```java
@Failover(
    name = "countries-by-codes",
    domain = "country",                    // shares store with country-by-code
    payloadSplitter = "countrySplitter",
    expiryDuration = 24,
    expiryUnit = ChronoUnit.HOURS
)
List<Country> findByCodes(@RequestParam String codes);
```

---

## Parallel Dispatch

By default, per-entity store and recover operations run in parallel via a virtual-thread executor:

```yaml title="application.yml"
failover:
  scatter:
    parallel: true   # default — CompletableFuture per slice on virtual threads
```

Set `parallel: false` for sequential per-entity processing (useful for debugging or low-throughput scenarios).

### Per-slice timeout

On the parallel path a single hung slice (e.g. a slice store with an exhausted JDBC connection pool) would otherwise block the business thread indefinitely on `join()`. `failover.scatter.timeout` bounds each slice:

```yaml title="application.yml"
failover:
  scatter:
    parallel: true
    timeout: 10s     # default; empty/null = wait indefinitely
```

The timeout bounds the slice's underlying store work — the actual `FailoverStore.store(...)` /
`FailoverStore.find(...)` call (including its JDBC round-trip), not just task scheduling.

On timeout:

- **Recover path** — applied **per slice** (each slice future is joined with its own timeout). A timed-out slice is treated as *not recovered* (contributes a `null` payload, exactly like a cache miss), so the rest of the result still merges and the caller is never blocked.
- **Store path** — applied to the **aggregate** completion of all slice writes (`allOf(...).orTimeout(...)`): if the batch of slice stores does not finish within the timeout, the join fails with a `TimeoutException`. That failure surfaces to the failover execution layer, which isolates it per the configured `exception-policy` (the business call still returns its live result; the store failure is logged/metered). Already-completed slice writes are **not** rolled back.

The timeout is ignored when `parallel: false` — sequential slices run inline on the calling thread and cannot be interrupted this way, so a hung store call blocks the caller. Keep `parallel: true` (the default) if slice stores can stall.

---

## Partial Recovery Behaviour

When some slices are missing, expired, or timed out, `merge()` receives a mix of populated and `null` payloads. The framework does **not** filter nulls for you — it only short-circuits to `null` when *every* slice context is empty (then `merge` is not called at all). Your `merge` implementation owns the null policy:

- **Keep positionally** — preserve `null` at the slice's index (the default per-id behaviour; lets the caller see which entries are missing).
- **Drop / deduplicate** — `filter(Objects::nonNull)` to return only what is available (as the example above does).

A timed-out slice is indistinguishable from a cache miss at `merge` time — both arrive as a `null` payload.

### Partial recovery is logged

When some (but not all) slices are missing, the gather logs a `WARN` before merging:

```
Failover scatter-recover: 'countries' — PARTIAL recovery, 2 of 5 slices missing;
the merged result may be incomplete (PayloadSplitter.merge owns the policy).
```

so a partial response is never silent. The `INFO` gather line also reports the recovered/missing counts. A counter `failover.recovery.partial.total{name,method}` is also emitted — alert on a non-zero rate.

!!! danger "Partial data can be worse than no data"
    In some domains an incomplete collection (e.g. 3 of 5 countries) is more dangerous than a clean
    failure, because the caller cannot tell it is incomplete. If your callers must not act on partial
    data, make `merge` **reject the whole composite** when any slice is missing:

    ```java
    @Override
    public RecoverContext<List<Country>> merge(List<RecoverContext<Country>> contexts) {
        boolean anyMissing = contexts.stream().anyMatch(c -> c.getPayload() == null);
        if (anyMissing) {
            return RecoverContext.<List<Country>>builder()
                    .payload(null)            // null → treated as a non-recovery by the caller / ExceptionPolicy
                    .build();
        }
        // ... merge the complete set ...
    }
    ```

    This makes partial recovery behave like a full miss (subject to your `ExceptionPolicy`), rather
    than silently returning a short list.

!!! tip "Combined with `domain`"
    When scatter/gather and domain are combined, a `findByCode("FR")` call can recover from an entry previously stored by `findByCodes("FR,DE,US")`. The domain ensures both failovers share the same `FAILOVER_NAME`, and scatter stores each code under its own key. See [Domain Grouping](domain.md).

---

## findAll() — Recover All Slices

Standard scatter/gather splits method args (e.g. a CSV of IDs) into per-entity keys on both store and recover. This works well for `findByIds("1,2,3")` but breaks for two patterns:

1. **No-arg `findAll()`** — there are no args to split into per-entity keys.
2. **Filter-only args** — `findByStatus("active", "EU")` has args, but they are filters, not entity identifiers. Splitting them into entity keys produces wrong results.

For both cases, the recover path must fetch **all stored slices by failover name** rather than looking up individual keys. This is the **recover-all path**.

### `recoverAll` semantics (the four rules)

1. **Recovery path only.** `recoverAll` affects only the **recover** path. The **store** path always
   splits the result via `splitOnStore` regardless of the flag.
2. **No-arg `findAll()` (null/empty args) recovers all either way.** Empty args trigger recover-all on
   their own — marking `recoverAll` or not makes no difference.
3. **Id args (e.g. `findAll(String ids)`) — no flag needed.** With entity-id args, the splitter splits
   them into per-entity keys for recovery; leave `recoverAll=false` (the default).
4. **Filter args (no ids) — you must force `recoverAll=true`.** When the args are *filters*, not entity
   ids (e.g. `findByStatus("active","EU")`), set `recoverAll=true` so the recover-all path runs instead of
   trying to split the filters into entity keys.

In all four, `payloadSplitter` is what does the slicing/merging — so it is **always required** for
scatter/gather. The `recoverAll` flag is the deciding factor **only in rule 4**.

### Trigger conditions

This is exactly `shouldRecoverAll = args == null || args.isEmpty() || recoverAll()`:

| Condition | Recovery path taken |
|---|---|
| `args` is `null` or empty (e.g. `findAll()`) | Recover-all (automatic — flag irrelevant) |
| `args` non-empty, `recoverAll = false` (e.g. `findAll(ids)`) | Normal scatter recover (split on args) |
| `args` non-empty, `recoverAll = true` (e.g. filter args) | Recover-all forced |

!!! warning "Recover-all needs a `payloadSplitter`"
    Recover-all (and all scatter/gather) requires a configured `payloadSplitter` — it slices and merges the
    referential. `@Failover(recoverAll=true)` with **no** `payloadSplitter` does nothing: the framework
    logs a startup `WARN` and falls back to single-key recover.

### How the recover-all path works

```
recover(args=[], clazz=List<Country>)
  → doRecoverAll(splitter, compositeCtx)
    → splitter.splitOnRecover(compositeCtx)     ← returns ONE placeholder context
      → delegateR.recoverAll(failover, method, args, Country.class, cause)
        → failoverStore.findAll("country")      ← fetches all slices by name
    → [ctx(FR), ctx(DE), ctx(US)]
  → splitter.merge([ctx(FR), ctx(DE), ctx(US)])
  → List<Country>[FR, DE, US]
```

The `splitOnRecover` implementation for the recover-all path must return a **single placeholder context** carrying `clazz = Country.class` (so the store knows the slice type). The placeholder's args are forwarded verbatim to `failoverStore.findAll` — they are not used as a key.

### Two-splitter pattern

A method with ID args (batch by ID) and a separate `findAll()` should use **two different `PayloadSplitter` beans** — one for each method:

```java
// Batch fetch: splits CSV of IDs into per-entity keys
@Failover(name="countries-by-ids", domain="country",
          payloadSplitter="countrySplitter",
          expiryDuration=24, expiryUnit=ChronoUnit.HOURS)
List<Country> findByIds(String csvIds);

// FindAll: no args — uses dedicated splitter for recover-all path
@Failover(name="all-countries", domain="country",
          payloadSplitter="countryAllSplitter",
          expiryDuration=24, expiryUnit=ChronoUnit.HOURS)
List<Country> findAll();
```

Both share `domain="country"` so `findAll()` store path populates entries that `findByIds` can also recover, and vice versa.

### N-slice recover-all and deduplication

`doRecoverAll` calls `recoverSliceForAll` for **each** context returned by `splitOnRecover`. With the default `DefaultFailoverHandler`, `recoverAll` ignores args and returns all entries by name — so N contexts produce N×all-entries (duplicates).

**Rule:** when using the default handler, `splitOnRecover` must return exactly **one** placeholder. Return multiple contexts only when a custom `delegateR.recoverAll` implementation partitions results by the context's args (e.g. by tenant or region).

If duplicates reach `merge()`, deduplication is the splitter's responsibility:

```java
@Override
public RecoverContext<List<Country>> merge(List<RecoverContext<Country>> contexts) {
    List<Country> deduped = contexts.stream()
        .map(RecoverContext::getPayload)
        .filter(Objects::nonNull)
        .collect(Collectors.toMap(Country::getId, c -> c, (a, b) -> a))
        .values().stream().toList();
    // build and return composite RecoverContext...
}
```

---

## PayloadSplitterExecutionException

Any exception thrown by a user-provided `PayloadSplitter` (`splitOnStore`, `splitOnRecover`, `merge`) is wrapped in `PayloadSplitterExecutionException` with full diagnostic context:

```
PayloadSplitter 'countrySplitter' failed during 'splitOnRecover'
  for failover 'countries-by-codes'
  [payloadSplitter='countrySplitter', expiryDuration=24, expiryUnit='HOURS', domain='country']:
  Index 1 out of bounds for length 0
```

The exception includes:

- **operation** — `splitOnStore` / `splitOnRecover` / `merge`
- **splitter bean name** — from `@Failover(payloadSplitter = "...")`
- **failover name** — `@Failover.name()`
- **full annotation config** — `expiryDuration`, `expiryUnit`, `domain`
- **original cause** — the exception thrown by the splitter

This makes it straightforward to identify whether the error occurred in split or merge, which annotation triggered it, and what the splitter's configuration was.

---

## Next Steps

- [Domain Grouping](domain.md) — cross-failover store sharing
- [Payload Splitter How-to](../how-to/payload-splitter.md) — `findAll()` and `recoverAll` step-by-step
- [Context Propagation](../how-to/context-propagation.md) — propagate thread-local context across parallel slices
