---
icon: material/rocket-launch
---

# Release Management

How maintainers cut and publish a new version of Failover to Maven Central.

---

## Overview

Releases are a two-stage process:

1. **Local/manual stage** — bump the version, tag it, push to `main`. Nothing is published yet.
2. **CI stage** — creating a GitHub Release from that tag triggers [`release_workflow.yml`](https://github.com/societe-generale/failover/blob/main/.github/workflows/release_workflow.yml), which builds, signs, and publishes the artifacts to Maven Central via OSSRH.

Pushing a tag by itself does **not** publish anything — the workflow only fires on the `release: created` GitHub event.

---

## Prerequisites

- Push access to `main` and permission to create GitHub Releases.
- The following repository secrets must already be configured (done once, not per release):
  - `OSSRH_GPG_SECRET_KEY` / `OSSRH_GPG_SECRET_KEY_PASSWORD` — GPG key used to sign artifacts.
  - `OSSRH_USERNAME` / `OSSRH_TOKEN` — Sonatype OSSRH credentials.
- Confirm CI is green on `main` ([`java-maven-ci.yml`](https://github.com/societe-generale/failover/blob/main/.github/workflows/java-maven-ci.yml)) before starting.

---

## Step 1 — Prepare the release

The root `pom.xml` is currently on a `-SNAPSHOT` version. Use the `maven-release-plugin`, already configured in `pom.xml`:

```bash
mvn release:prepare
```

You'll be prompted for:

- **Release version** — e.g. `1.0.0`
- **SCM tag** — defaults to `failover_1.0.0` (from `tagNameFormat` in `pom.xml`)
- **Next development version** — e.g. `1.0.1-SNAPSHOT`

Because `autoVersionSubmodules=true`, every sub-module is bumped together — you're only asked once, not per-module.

Because `pushChanges=false`, this step only commits and tags **locally**. Nothing is pushed to `origin` yet.

!!! tip "Manual alternative"
    If you'd rather not use the release plugin's interactive flow:

```bash
    mvn versions:set -DnewVersion=1.0.0 -DprocessAllModules=true
    mvn versions:commit
    git commit -am "release: 1.0.0"
    git tag failover_1.0.0

    mvn versions:set -DnewVersion=1.0.1-SNAPSHOT -DprocessAllModules=true
    mvn versions:commit
    git commit -am "chore: next dev version 1.0.1-SNAPSHOT"
```

If something goes wrong before pushing, roll back cleanly:

```bash
mvn release:rollback
```

---

## Step 2 — Push the commits and tag

```bash
git push origin main
git push origin failover_1.0.0
```

The tag name must match the `failover_<version>` pattern — that's what `tagNameFormat` produces and what release notes/automation expect.

---

## Step 3 — Create the GitHub Release

Creating a GitHub Release from the pushed tag is what actually triggers the publish workflow. Either via the UI, or:

```bash
gh release create failover_1.0.0 --generate-notes
```

This fires [`release_workflow.yml`](https://github.com/societe-generale/failover/blob/main/.github/workflows/release_workflow.yml) (`on: release: types: [created]`).

---

## Step 4 — What CI does automatically

On `release: created`, the workflow:

1. Imports the GPG secret key and refreshes it against the Ubuntu keyserver.
2. Sets up JDK 21 (Temurin) with `ossrh` server credentials wired from secrets.
3. Runs:

   ```bash
   mvn --no-transfer-progress --batch-mode -Dgpg.passphrase=*** clean deploy -P makeRelease
   ```

The `makeRelease` Maven profile (defined in root `pom.xml`) additionally:

- Attaches a `-sources` jar (`maven-source-plugin`).
- GPG-signs every artifact during the `verify` phase (`maven-gpg-plugin`).
- Stages, closes, and **auto-releases** the Nexus/Sonatype staging repository (`nexus-staging-maven-plugin`, `autoReleaseAfterClose=true`) — no manual step in the Sonatype UI is needed.

---

## Step 5 — Verify

- Check the **Actions** tab — the `release_workflow.yml` run must complete successfully.
- Search [Maven Central](https://search.maven.org/) for the new `com.societegenerale.failover` coordinates. Sync typically takes 10–30 minutes after staging auto-release.
- Confirm the GitHub Release notes and tag look correct.

---

## Rollback / troubleshooting

| Problem | Fix |
|---|---|
| `release:prepare` fails mid-way (before push) | `mvn release:rollback` |
| Wrong version pushed, release not yet published on Central | Delete the GitHub Release and tag, fix the version, re-tag, re-release |
| Already synced to Maven Central | Central artifacts are immutable — cut a new patch version instead |
| Workflow fails on GPG import/signing | Verify `OSSRH_GPG_SECRET_KEY*` secrets haven't expired or been rotated |
| Workflow fails on deploy/auth | Verify `OSSRH_USERNAME` / `OSSRH_TOKEN` are still valid for the `ossrh` server id |

---

## Reference

- Release trigger workflow: `.github/workflows/release_workflow.yml`
- Version / tag / profile config: root `pom.xml` — `maven-release-plugin` config and the `makeRelease` profile
- Tag naming convention: `failover_<version>` (e.g. `failover_1.0.0`) 
