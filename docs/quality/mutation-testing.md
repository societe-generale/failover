---
icon: material/dna
---

# Mutation Testing

> Audit finding **T-4** · ADR **46**

## Why line coverage isn't enough

Line coverage proves a line *executed*, not that an assertion would *catch a bug* in it. The
expiry-boundary logic (`isExpired`, `cleanByExpiry`, `<` vs `<=`) and key derivation are exactly the
kind of code where an off-by-one or boundary change can pass every existing test while being wrong.

[PIT (pitest)](https://pitest.org/) mutates the bytecode — flipping conditionals, changing
boundaries, replacing return values — and reruns the tests. A **surviving mutant** is a change no test
noticed: a gap in assertion strength.

## Scope and gating

PIT is scoped to the two highest-risk packages and lives in a profile so the default build is
unaffected:

| Setting | Value |
|---|---|
| Target classes | `com.societegenerale.failover.core.expiry.*`, `…core.key.*` |
| Profile | `mutation` |
| Threshold | **95%** — the build **fails** below it |
| `failWhenNoMutations` | `true` — a zero-mutation misconfiguration fails loudly, so the gate can never pass vacuously |

The 95% gate is **mandated**: `mvn -Pmutation test` fails if mutation coverage drops below 95%, and
the CI `mutation` job is **blocking** (not advisory). The one mutation that cannot be killed is an
unreachable `catch (NoSuchMethodException)` in `overridesToString` — `toString()` always exists, but
the checked exception forces the catch to compile — so the achievable ceiling is **98%** (52/53).

!!! note "JUnit Platform 6 compatibility"
    The project runs on JUnit Jupiter / Platform 6. PIT requires `pitest-maven` ≥ 1.20 and
    `pitest-junit5-plugin` ≥ 1.2.3 for the coverage minion to launch on this stack — older
    combinations exit with `UNKNOWN_ERROR`. The surefire argLine (which carries the JaCoCo agent and a
    late-bound `@{argLine}` token) is deliberately **not** inherited by the PIT minion; explicit
    `--add-opens` args are passed instead.

## Running it

```bash
mvn -pl failover-core -am -Pmutation test
```

The HTML and XML reports are written to `failover-core/target/pit-reports`. In CI the job is
**blocking** (fails the check below 95%) and uploads the report as an artifact.

## Current score

Over the expiry + key packages:

- **53** mutations generated, **52 killed (98%)**
- **Test strength 100%** (every covered mutant is killed)
- The single survivor is the unreachable `catch` branch in `overridesToString` (NO_COVERAGE)

If a future change drops a mutant below the gate, the surviving mutant in the report points straight
at the boundary or return-value assertion that needs strengthening.
