---
icon: material/shield-check
---

# Quality & Testing

Failover ships with a layered test strategy. The everyday build stays fast and hermetic; deeper,
heavier checks are opt-in so they never slow down day-to-day development.

## Two-tier default build

| Tier | Pattern | Plugin | Context |
|---|---|---|---|
| Unit | `*Test.java` | Surefire | Pure Mockito / JUnit, no Spring context |
| Integration | `*IT.java` | Failsafe | `@SpringBootTest` against a real **H2** database |

Integration tests run with `failover.store.async=false` so writes are synchronous and assertions are
deterministic. `mvn clean verify` runs both tiers — **no Docker required**.

## Opt-in deep checks

These were added in the Phase 3 hardening pass (audit findings T-1 to T-4). Each is gated so the
default build is unaffected:

<div class="grid cards" markdown>

-   :material-database-cog: **[Dialect integration tests](integration-tests.md)**

    Real PostgreSQL / MySQL / MariaDB via Testcontainers, proving the native merge/upsert SQL.
    Profile `dialect-its`. *(T-1)*

-   :material-sync: **[Concurrency tests](concurrency-tests.md)**

    Contention coverage for multi-tenant store routing and the async executor path. Part of the
    default build. *(T-2)*

-   :material-sitemap: **[Architecture tests](architecture-tests.md)**

    ArchUnit rules that enforce the decorator invariants the compiler cannot. Part of the default
    build. *(T-3)*

-   :material-dna: **[Mutation testing](mutation-testing.md)**

    PIT on the expiry + key boundary logic. Profile `mutation`. *(T-4)*

</div>

## Command summary

```bash
# Default build — unit + H2 integration tests, no Docker
mvn clean verify

# Dialect ITs against real databases (requires Docker)
mvn -pl failover-store-jdbc -am -Pdialect-its verify

# Mutation testing on failover-core expiry/key packages
mvn -pl failover-core -am -Pmutation test
```

In CI the two heavy jobs (`dialect-its`, `mutation`) run as **advisory, non-blocking** checks; the
H2 build remains the required gate.
