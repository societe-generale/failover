---
icon: material/swap-horizontal
---

# Migrating 2.x → 3.0

Everything that needs a code or configuration change when upgrading from Failover 2.1.x to 3.0.0.
Items are ordered by how likely they are to affect you.

For the full list of changes — including the many that need no action — see the
[Changelog](../changelog/changelog.md).

---

## Before you start

3.0.0 raises two platform floors:

|             | 2.x | 3.0.0                                                                              |
|-------------|-----|------------------------------------------------------------------------------------|
| Java        | 17  | **21** (virtual threads are used for the async store and scatter/gather executors) |
| Spring Boot | 3.x | **4.0.x** (Spring Cloud `2025.1.x`)                                                |

Upgrade the platform first and get a green build on 2.x if you can — it keeps the Spring Boot 4
migration separate from the Failover one.

---

## 1. Package moves (compile errors)

Split packages were eliminated so no two JARs share a package (JPMS-friendly, audit A-1). Every move
is a pure relocation — same class, same behaviour. Fix with an IDE "organise imports" or the
find/replace below.

| Was                                                              | Now                                                     |
|------------------------------------------------------------------|---------------------------------------------------------|
| `…store.FailoverStoreInmemory`                                   | `…store.inmemory.FailoverStoreInmemory`                 |
| `…store.FailoverStoreCaffeine`                                   | `…store.caffeine.FailoverStoreCaffeine`                 |
| `…store.FailoverStoreJdbc` and its mappers/resolvers/serializers | `…store.jdbc.*` (`.mapper`, `.resolver`, `.serializer`) |
| `…store.FailoverStoreAsync`                                      | `…store.async.FailoverStoreAsync`                       |
| `…core.BeanFactory*` lookups                                     | `…lookup.BeanFactory*`                                  |
| `…core.observable.scanner.FailoverScanner`                       | `…core.scanner.FailoverScanner`                         |

`FailoverScanner` moved because it is no longer observability-specific — it is a neutral shared
component consumed by both observability reporting and store deserialization safety.

**You are unaffected** if you only use `@Failover` and the starter: none of these types appear in
zero-config application code.

---

## 2. Renamed properties

| Was                                      | Now                                           |
|------------------------------------------|-----------------------------------------------|
| `failover.store.allowed-payload-classes` | `failover.store.jdbc.allowed-payload-classes` |

The allowlist only ever applied to the serializing JDBC store, so it moved under that store's
namespace. In-memory and Caffeine hold live objects and never deserialize.

**Removed:** `failover.dashboard.cluster.shared-store.jdbc.auto-ddl`. The dashboard's JDBC snapshot
store no longer creates or alters `FAILOVER_DASHBOARD_SNAPSHOT` — schema management is the consuming
service's responsibility. Run the per-dialect DDL from
[Dashboard](../modules/dashboard.md) before upgrading, or the store will log that the table is
unreachable.

---

## 3. Deserialization now fails closed by default

`failover.store.jdbc.strict-allowlist` **defaults to `true`** (was `false`).

An empty resolved allowlist — no `@Failover` payload types discovered **and** nothing configured —
now denies all deserialization with `FailoverStoreException`, instead of silently disabling the
restriction and loading whatever class name the store row happened to carry.

**You are unaffected** if you use `@Failover` normally: the allowlist is auto-derived from the
packages of discovered payload types, so it is never empty in a working application.

**You must act** if you call `FailoverStore` **directly**, outside any `@Failover` method. There are
no annotations for the scanner to derive from, so your allowlist resolves empty and recovery fails
closed. Name the payload types:

```yaml
failover:
  store:
    jdbc:
      allowed-payload-classes:
        - com.acme.referential.Country
```

`strict-allowlist: false` restores 2.x behaviour as an escape hatch, but keeps the fail-open path.
Prefer populating the allowlist. See [Security](../support/security.md).

---

## 4. Custom `FailoverHandler` implementations

The SPI is now method-aware: `store`, `recover` and `recoverAll` carry the intercepted
`@NonNull Method` (ADR 52).

Handlers that do not need it should extend the new `AbstractFailoverHandler`, which adapts the
method-aware signatures onto the old ones. Handlers that do need it implement `FailoverHandler`
directly.

The built-in chain and zero-config users are unaffected — this only reaches you if you wrote your own
`FailoverHandler`.

---

## 5. `ScatterGatherFailoverHandler` construction

The overloaded constructors were replaced by a builder (audit A-2):

```java
ScatterGatherFailoverHandler.builder(delegate, splitterLookup)
        .

executor(myExecutor)             // optional
        .

contextPropagator(myPropagator)  // optional
        .

timeout(Duration.ofSeconds(5))   // optional
        .

observablePublisher(myPublisher) // optional
        .

build();
```

Behaviour is unchanged. Only relevant if you construct the handler yourself rather than letting
auto-configuration assemble it.

---

## 6. Required JDBC schema change

`CREATE TABLE` now mandates an index on `EXPIRE_ON`:

```sql
CREATE INDEX IDX_FAILOVER_STORE_EXPIRE_ON ON FAILOVER_STORE (EXPIRE_ON);
```

Without it the hourly expiry cleanup (`DELETE … WHERE EXPIRE_ON < ?`) is a full table scan (audit
I-13). Add the index to existing tables as part of the upgrade — nothing breaks without it, but the
cleanup job degrades as the table grows.

---

## Behaviour changes needing no code change

- **Logging volume drops.** `DefaultFailoverHandler` keeps the lifecycle event at `INFO` (name only);
  the full `ReferentialPayload` body moved to `DEBUG`, so the hot path no longer serialises a whole
  payload per call (ADR 48). Re-enable with `DEBUG` on that logger if you relied on it.
- **Store keys are fixed-length.** Key generation produces MD5/UUID-based keys to prevent
  `VARCHAR(256)` overflow. Existing rows keyed the old way will not be found and will age out by
  expiry — a cold cache after upgrade, not an error.
- **Scatter/gather partial-recovery metric is stricter.** `recover-partial` now fires only for genuine
  partial recovery (`0 < missing < total`); an all-missing recovery is reported as full non-recovery.
  Alerting on that metric may see a step change.

---

## Dashboard

The dashboard is opt-in and new enough that there is no 2.x migration path — see
[Dashboard](../modules/dashboard.md) to adopt it. If you already ran a 3.0.0 pre-release, note the
removed `auto-ddl` property in §2 and the peer-ingest hardening in the
[Changelog](../changelog/changelog.md): an ingest username with a blank password is now rejected at
startup, and peer instance ids are validated.
