---
icon: material/rocket-launch
---

# Release Management

How maintainers cut and publish a new version of Failover to Maven Central.

---

## Overview

Releases are a two-stage process:

1. **Local/manual stage** — bump the version, tag it, push to `main`. Nothing is published yet.
2. **CI stage** — creating a GitHub Release from that tag triggers [`release_workflow.yml`](https://github.com/societe-generale/failover/blob/main/.github/workflows/release_workflow.yml), which builds, signs, and publishes the artifacts to Maven Central via the [Central Portal](https://central.sonatype.com).

Pushing a tag by itself does **not** publish anything — the workflow only fires on the `release: created` GitHub event.

---

## Prerequisites

- Push access to `main` and permission to create GitHub Releases.
- The following repository secrets must already be configured (done once, not per release):
  - `OSSRH_GPG_SECRET_KEY` / `GPG_PASSPHRASE` — GPG key used to sign artifacts, and its passphrase.
  - `CENTRAL_USERNAME` / `CENTRAL_PASSWORD` — a **Central Portal user token**, generated at
    [central.sonatype.com/account](https://central.sonatype.com/account).
- Confirm CI is green on `main` ([`java-maven-ci.yml`](https://github.com/societe-generale/failover/blob/main/.github/workflows/java-maven-ci.yml)) before starting.

!!! warning "Migrated off OSSRH"
    Publishing used to go through Sonatype OSSRH (`s01.oss.sonatype.org`) with
    `nexus-staging-maven-plugin`. Those hosts are decommissioned and now answer `HTTP 402`, so that
    path can no longer publish anything. The build uses
    [`central-publishing-maven-plugin`](https://central.sonatype.org/publish/publish-portal-maven/)
    against the Central Portal instead.

    A Portal **user token** is a distinct credential — the old `OSSRH_USERNAME` / `OSSRH_TOKEN` values
    will not authenticate against it, even though the namespace itself was migrated automatically.
    Generate a fresh token and store it as `CENTRAL_USERNAME` / `CENTRAL_PASSWORD`. The signing key
    itself is unchanged; its passphrase secret is now read as `GPG_PASSPHRASE`.

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
2. Sets up JDK 21 (Temurin), writing a `<server id="central">` block into `settings.xml` from the
   `CENTRAL_USERNAME` / `CENTRAL_PASSWORD` secrets. That id must match
   `<publishingServerId>central</publishingServerId>` in the `makeRelease` profile.
3. Runs:

   ```bash
   mvn --no-transfer-progress --batch-mode clean deploy -P makeRelease
   ```

   The GPG passphrase is passed as the `MAVEN_GPG_PASSPHRASE` environment variable rather than
   `-Dgpg.passphrase=…`, so it never appears in the process argument list.

The `makeRelease` Maven profile (defined in root `pom.xml`) additionally:

- Attaches a `-sources` jar (`maven-source-plugin`).
- GPG-signs every artifact during the `verify` phase (`maven-gpg-plugin`).
- Uploads the bundle to the Central Portal and **auto-publishes** it once validation passes
  (`central-publishing-maven-plugin`, `autoPublish=true`) — no manual step in the Portal UI is needed.
  `waitUntil=published` makes the build block on validation, so a rejected bundle fails the workflow
  instead of passing green with nothing published.

There is no `<distributionManagement>` in the POM: the plugin's `extensions=true` replaces the default
deploy target with the Portal upload. Adding one back would be ignored at best.

---

## Step 5 — Verify

- Check the **Actions** tab — the `release_workflow.yml` run must complete successfully.
- Check the [Central Portal deployments view](https://central.sonatype.com/publishing/deployments) — the bundle must reach `PUBLISHED`, not stall in `VALIDATING` or land in `FAILED`.
- Search [Maven Central](https://search.maven.org/) for the new `com.societegenerale.failover` coordinates. Sync typically takes 10–30 minutes after publish.
- Confirm the GitHub Release notes and tag look correct.

---

## Rollback / troubleshooting

| Problem | Fix |
|---|---|
| `release:prepare` fails mid-way (before push) | `mvn release:rollback` |
| Wrong version pushed, release not yet published on Central | Delete the GitHub Release and tag, fix the version, re-tag, re-release |
| Already synced to Maven Central | Central artifacts are immutable — cut a new patch version instead |
| Workflow fails on GPG import/signing | Verify `OSSRH_GPG_SECRET_KEY` / `GPG_PASSPHRASE` haven't expired or been rotated |
| Workflow fails on deploy with `401`/`403` | Regenerate the Central Portal user token and update `CENTRAL_USERNAME` / `CENTRAL_PASSWORD`. An old OSSRH credential will never authenticate here |
| Workflow fails on deploy with `402` | Something still points at the retired OSSRH hosts — grep the POM and workflow for `oss.sonatype.org` |
| Bundle uploads but validation fails | Open the deployment in the [Portal](https://central.sonatype.com/publishing/deployments) for the per-artifact reason — usually a missing signature, `-sources`, or `-javadoc` jar, or POM metadata (`name`/`description`/`url`/`licenses`/`developers`/`scm`) |

---

## Reference

- Release trigger workflow: `.github/workflows/release_workflow.yml`
- Version / tag / profile config: root `pom.xml` — `maven-release-plugin` config and the `makeRelease` profile
- Tag naming convention: `failover_<version>` (e.g. `failover_1.0.0`)
