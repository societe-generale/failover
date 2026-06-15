---
icon: material/database-cog
---

# Dialect Integration Tests

> Audit finding **T-1** · ADR **43**

## Why

`FailoverStoreJdbc` performs an atomic upsert using a **native merge dialect** selected per database
by `DefaultFailoverStoreQueryResolver` (see [ADR 13](../adr/adr.md#adr-13-jdbc-native-mergeupsert-dialect-detection-and-runtime-fallback)):

| Database | Merge strategy |
|---|---|
| H2 | `MERGE INTO … KEY (FAILOVER_NAME, FAILOVER_KEY)` |
| PostgreSQL | `INSERT … ON CONFLICT (…) DO UPDATE SET …` |
| MySQL / MariaDB | `INSERT … ON DUPLICATE KEY UPDATE …` |
| Oracle | `MERGE INTO … USING (SELECT … FROM DUAL)` |
| Other | INSERT + UPDATE fallback |

The default test suite only runs against H2. The other dialect SQL strings were previously verified
by **string assertion only** — so a typo in the PostgreSQL or MySQL grammar would not fail any test.
Worse, at runtime the first failing `store()` throws `BadSqlGrammarException`, the store permanently
flips to the INSERT/UPDATE fallback, and the broken merge is silently masked.

## What the tests do

`*DialectIT` tests (package `com.societegenerale.failover.store.dialect` in `failover-store-jdbc`)
spin up a **real database container** via [Testcontainers](https://testcontainers.com/) and run the
full round-trip against it:

1. `store()` a payload (INSERT path).
2. `store()` the same key again — exercises the **native merge/upsert**.
3. `find()` returns the overwritten value, and the table holds exactly one row.
4. `cleanByExpiry()` evicts the expired entry and keeps the live one.
5. `delete()` removes the entry.

Each test also asserts that `getMergeQuery()` is non-null and contains the dialect-specific fragment
(`ON CONFLICT` / `ON DUPLICATE KEY UPDATE`) — i.e. the **native dialect was selected, not the
fallback**.

A shared `AbstractDialectIT` holds the scenario; one subclass per database supplies the `@Container`
and the dialect-specific DDL:

| Test | Container image | Asserted dialect |
|---|---|---|
| `PostgresDialectIT` | `postgres:16-alpine` | `ON CONFLICT` |
| `MySqlDialectIT` | `mysql:8.4` | `ON DUPLICATE KEY UPDATE` |
| `MariaDbDialectIT` | `mariadb:11.4` | `ON DUPLICATE KEY UPDATE` |

!!! note "Oracle is intentionally not containerised"
    The `gvenzl/oracle-free` image is ~2 GB with slow startup and licensing caveats. The Oracle
    `MERGE USING DUAL` SQL remains string-asserted. PostgreSQL + MySQL + MariaDB already cover the two
    distinct non-H2 merge grammars.

## Running them

The ITs are named `*DialectIT` and are **excluded from the default build** (the parent POM's failsafe
config skips that suffix). They run only under the `dialect-its` profile, which requires a running
Docker daemon:

```bash
mvn -pl failover-store-jdbc -am -Pdialect-its verify
```

The Testcontainers JARs are test-scoped, so the test sources always compile — only the containers are
gated behind the profile.

## Adding a new dialect

1. Add the SQL constant and a `contains()` branch in `DefaultFailoverStoreQueryResolver`.
2. Add a `NewDbDialectIT` subclass of `AbstractDialectIT` with the matching `@Container` and DDL.

The store/merge/find/clean scenario is inherited — no test logic to rewrite.
