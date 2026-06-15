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
| Threshold | `0` — score is **recorded, not gated** |

The baseline-not-gate choice keeps the build stable while still surfacing surviving mutants in the
report. Tightening the threshold later is a one-line change once the score stabilises.

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

The HTML and XML reports are written to `failover-core/target/pit-reports`. In CI the job runs as an
**advisory, non-blocking** check and uploads the report as an artifact.

## Current baseline

The first run over the expiry + key packages produced:

- **53** mutations generated, **85%** killed
- Test strength **87%**, line coverage of mutated classes **97%**

Surviving mutants are the actionable output — they point at boundary or return-value assertions worth
strengthening.
