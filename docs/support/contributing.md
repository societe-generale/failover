---
icon: material/source-pull
---

# Contributing

Contributions are welcome — bug fixes, new features, documentation improvements, and test coverage.

---

## Prerequisites

- JDK 21 or later
- Maven 3.9 or later
- Git

---

## Build

```bash
mvn clean verify
```

This compiles all modules, runs unit tests, and runs integration tests.

---

## Run Tests Only

```bash
mvn test                    # unit tests
mvn verify -P integration   # integration tests (H2 in-memory)
```

---

## Submitting a Change

1. Fork the repository.
2. Create a branch: `git checkout -b feat/my-change`.
3. Keep scope small and focused — one feature or bug fix per PR.
4. Add or update tests for every behaviour change.
5. Run `mvn verify` — all tests and Javadoc must pass with zero warnings.
6. Open a pull request against `main`. Fill in the PR template.

---

## Coding Conventions

- Java 21, Spring Boot 4.x idioms.
- Every public type and method must have a Javadoc description.
- No breaking changes to public API without a deprecation cycle and a new ADR.
- New architecture decisions → append a new ADR to `docs-old/documentation/ADR.md`.

---

## Reporting a Bug

Open a [GitHub Issue](https://github.com/societe-generale/failover/issues/new?template=bug_report.md) with:

- Spring Boot version
- Java version
- Minimal reproducible example (a test or config snippet)
- Expected vs actual behaviour
- Stack trace if applicable

---

## Proposing a Feature

Open a [GitHub Issue](https://github.com/societe-generale/failover/issues/new?template=feature_request.md) describing the use case. Large changes should start as a discussion or ADR before any code is written.

---

## Code Review

All PRs receive at least one review from a maintainer. Please respond to review comments within a reasonable time. PRs with no activity for 30 days may be closed.

---

## License

By contributing, you agree that your contributions will be licensed under the Apache License 2.0, the same license as the project.
