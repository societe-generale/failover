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
| Target classes | `com.societegenerale.failover.core.*` (the whole core package tree) |
| Profile | `mutation` |
| Threshold | **95%** — the build **fails** below it |
| `failWhenNoMutations` | `true` — a zero-mutation misconfiguration fails loudly, so the gate can never pass vacuously |

The 95% gate is **mandated**: `mvn -Pmutation test` fails if mutation coverage drops below 95%, and
the CI `mutation` job is **blocking** (not advisory). It spans all of `failover-core` — handlers,
expiry, key generation, payload enrichment, exception policy — not just the original expiry/key
packages.

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

Over the whole `failover-core` package:

- **216** mutations generated, **206 killed (95%)**, meeting the gate
- **Test strength 98%** (of the covered mutants)
- The remaining survivors are equivalent or unreachable mutants — e.g. an unreachable
  `catch (NoSuchMethodException)` in `overridesToString` (`toString()` always exists), and a
  `setMetadata` call on a metadata instance that was already mutated in place

Reaching the gate required strengthening several assertions — asserting *returned* values rather than
just delegate invocation, pinning `castToStringValue` warn-vs-no-warn branching with log capture, a
positive `ReferentialPayload.toString` assertion, and exercising the `populateAdditionalInfoOnMetadata`
extension point. If a future change drops a covered mutant, the report points straight at the
assertion to strengthen.
