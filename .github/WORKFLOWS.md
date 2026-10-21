# GitHub Workflows Reference

Quick guide to the automated workflows in this repository.

## Overview

```
Your PR
    │
    ├─→ ci.yml ─────────────────────────┐
    │   ├─ Detect changed modules       │
    │   ├─ Build + tests + quality      │
    │   │  gates (Checkstyle, PMD,      │
    │   │  SpotBugs, OpenGrep, JaCoCo)  │
    │   └─ Dependency review            │
    │                                   │
    ├─→ license-compliance.yml ─────────┼→ compliance-summary.yml
    │   ├─ REUSE header validation      │   └─ One sticky PR comment,
    │   ├─ Licensee dependency check    │      updated in place
    │   └─ Trivy license scanning       │
    │                                   │
    ├─→ security.yml ───────────────────┘
    │   ├─ Secret scanning (blocking)
    │   └─ Trivy vuln/misconfig scan (report-only on PRs)
    │
    └─→ codeql.yml
        └─ CodeQL analysis (Java, Go, Actions)
```

On `main` (push/schedule): full builds, blocking security gates, SBOM
generation, weekly CodeQL and OpenSSF Scorecard runs.

## Workflows

### `ci.yml` — Java CI with Gradle

**Purpose**: Build, test, and enforce quality gates

| When | Branch | Behavior |
|------|--------|----------|
| PR opened/updated | any | Build the changed modules **and every module that depends on them** |
| PR with the `ci:full` label | any | Full build (add the label, then re-run or push) |
| Push | main | Full build — every module's tests + quality gates |
| Scheduled | daily @ 5:30 AM IST | Full build **including E2E**, failed tests retried to spot flaky ones; failures open an issue (`nightly-report.yml`) |
| Manual | workflow_dispatch | Same as scheduled (no issue) |

**What a PR builds**: `.github/scripts/ci-tasks.sh` reads the modules from
`settings.gradle.kts` and the edges between them from every quoted module path
(`project(":core")`, boot's optional-plugin map) in the modules' `build.gradle.kts`,
then rebuilds each changed module plus everything that depends on it, transitively.
Nothing is listed by hand, so a new module or dependency is picked up automatically.

| PR changes | Builds |
|------------|--------|
| `web/` or `mcp/` | that module + `boot` |
| `core/` | `core`, `web`, `mcp`, `internal-core`, `script-js`, `plus/*`, `boot` + both acceptance suites |
| `messaging/` or `plugin-api/` | every JVM module (not `cli`, `ui`) |
| `plugins/processors/llm-call/` | `llm-call`, `decision`, `model-control-plane`, `rules-first-control-plane`, `boot` |
| `ui/` | `ui`, `boot` + Playwright E2E |
| `cli/` | `cli` |
| `docs/`, `examples/`, `*.md` | nothing |
| `build-logic/`, `gradle/`, `config/`, `.github/workflows/`, `.github/scripts/`, root build files | everything |

PRs are gated on what they touch; a cross-module break that slips past the
graph (reflection, resources, runtime wiring) is caught by the full build on
`main` and by the nightly run, which files an issue.

**Jobs**:
1. `lint-workflows` — yamllint, actionlint, zizmor, shellcheck for `.github/scripts`, the label
   check (`sync-labels.py --check`),
   the shard-planner table test (`ci-tasks.test.sh`) and a module-graph check
   (`ci-tasks.sh --check`: every `project()` dependency is an included module).
2. `changes` — Plan the run with `ci-tasks.sh` (fast, ~5s): changed modules and
   their dependents, as above. The plan is written to the job summary.
   A change to a root/build file (`build-logic`, `gradle/`, `gradle.properties`,
   `.github/workflows`, `.github/scripts`, ...) forces a full build.
3. `lint` — Fast Lint: Spotless, Checkstyle, PMD and OpenGrep on the affected
   modules, for an early signal. Partial PR builds only: on a full run every shard's
   `build` runs the same checks, and linting all 30 modules (~7 min) was the slowest job.
4. `build` — One matrix job per shard, run in parallel (`platform`, `infra`,
   `plugins`, `cli`, `ui`, plus the `postgres-acceptance` and `plus-acceptance`
   end-to-end runs). Each runs `:module:build`, which includes `check`:
   tests, Checkstyle, PMD, SpotBugs, OpenGrep and JaCoCo verification for JVM
   modules; Go format/vet/lint/gosec/govulncheck/100% function coverage for
   `cli`; ESLint (baselined in `ui/eslint-suppressions.json`), Vitest, header and
   version checks for `ui`. On CI a missing OpenGrep binary fails the build
   instead of skipping the scan. Shards with nothing affected are not started.
5. `dependency-submission` / `dependency-review` — Submit the full dependency
   graph; fail PRs that introduce HIGH/CRITICAL vulnerable dependencies
   (skipped for fork PRs).
6. `e2e-tests` — Playwright against the UI when `ui/**` changed, and on every full run. Results, including
   flaky tests that only passed on retry, are written to the job summary.
7. `ci-gate` — Single required check for branch protection.

---

### `nightly-report.yml` — Nightly Failure Report

**Purpose**: Track failures of the scheduled full runs as GitHub issues

**How it works**: Triggered by `workflow_run` completions of CI, Security and
CodeQL; acts only on **scheduled** runs. `.github/scripts/nightly-report.sh` keeps
one open issue per workflow, titled `Nightly failure: <workflow>` and labelled
`ci-nightly`:

| Scheduled run | Action |
|---------------|--------|
| Fails / times out, no open issue | Opens one: failed jobs and steps, failed tests (from the `build-reports-*` JUnit XML), run link |
| Fails / times out, issue open | Adds the same report as a comment (one per night) |
| Succeeds, issue open | Comments "green again" and closes it |

**Flaky tests**: scheduled and manual CI runs pass `-PretryFlakyTests`, which
retries a failed test up to twice (Gradle test-retry plugin, `java-conventions`).
The build still fails, so a flaky test is never hidden; the report lists it under
*Flaky tests — failed, then passed on retry*, apart from tests that never passed.
PRs and `main` never retry.

**Shard timing**: every scheduled CI run writes the test time per shard and the
slowest test classes to the report job's summary. Use it to rebalance shards
(`group_of` in `ci-tasks.sh`) or to speed up the slowest tests.

`ci-nightly` issues are exempt from the stale bot. Fix the failure on a branch;
the next green nightly closes the issue, or run CI manually (`workflow_dispatch`)
to check sooner (manual runs don't touch the issue).

---

### `dependabot-automerge.yml` — Dependabot Auto-merge

**Purpose**: Merge Dependabot patch and minor updates without a maintainer click

Enables GitHub auto-merge (squash) on Dependabot PRs whose update type is
`semver-patch` or `semver-minor` (grouped PRs use the largest bump in the group).
GitHub merges only once branch protection passes, i.e. `CI Gate` is green and any
required review is given. Major updates stay manual.

**Requires**: Settings → General → *Allow auto-merge*, and `CI Gate` as a required
status check on `main`. If auto-merge is off, the job logs a notice and does nothing.

---

### `security.yml` — Security Scanning (Trivy)

**Purpose**: Secret, vulnerability, and misconfiguration scanning + SBOM

| When | Trigger | Behavior |
|------|---------|----------|
| PR | pull_request | Secrets **blocking**; vulns/misconfigs report-only (table + SARIF) |
| Push to main | push | All scans **blocking** (HIGH/CRITICAL fail the run) |
| Weekly (Mon 6:30 AM IST) | schedule | Blocking rescan of main for newly published CVEs |
| Manual | workflow_dispatch | Blocking scan |

New HIGH/CRITICAL vulnerabilities *introduced by a PR* are blocked by
`dependency-review` in ci.yml; the full-repo Trivy gate is enforced on main
so unrelated PRs are not blocked by pre-existing CVEs.

**Artifacts**: `security-reports-{run_id}` — SARIF + CycloneDX SBOM
(`sbom-yukta.cdx.json`, main/schedule/manual runs only). SARIF is uploaded to
the GitHub Security tab.

---

### `license-compliance.yml` — License Compliance

**Purpose**: Validate dependency licenses and REUSE compliance

| Component | Check | Purpose |
|-----------|-------|---------|
| **REUSE** | License Header Compliance | Verifies all files have SPDX license headers |
| **Plus isolation** | Apache modules never depend on Plus | Fails if any module other than `boot` depends on the AGPL `:plus` module |
| **Licensee** | Dependency License Validation | Ensures all dependencies have Apache-2.0 compatible licenses |
| **Trivy** | License Scanning | Scans lock files for forbidden/restricted licenses |

**Jobs**: `reuse-compliance`, `plus-isolation`, `gradle-license-validation`, `trivy-license-scan`

**Triggered on**: Push to main, PR, manual trigger

**Artifacts**: `licensee-reports`; Trivy license SARIF in the Security tab.
Results are summarized in the job summary and the unified PR comment.

---

### `compliance-summary.yml` — Compliance & Quality Summary

**Purpose**: One sticky PR comment aggregating CI, license, and security results

**How it works**: Triggered by `workflow_run` completions of the four
workflows above (CI, license, security, CodeQL). Resolves the PR from the run, lists every job of each
workflow's latest run for that commit, and creates **or updates** a single
comment identified by an HTML marker — no comment spam, no stale data.

---

### `codeql.yml` — CodeQL

**Purpose**: Static application security testing (SAST)

Analyzes `java-kotlin` (build-mode `none` — no Gradle build needed), `go`
(the `cli/` module, manual build), and `actions` (the workflows themselves)
on PRs, pushes to main, and weekly. Findings appear under Security → Code
scanning.

---

### `scorecard.yml` — OpenSSF Scorecard

**Purpose**: Continuously score the repo against OpenSSF supply-chain best
practices (pinned dependencies, token permissions, branch protection, …).

Runs weekly, on pushes to main, and when branch protection rules change.
Publishes to the OpenSSF API (powers the README badge) and uploads SARIF.

---

### `labeler.yml` / `stale.yml`

Auto-label PRs by touched paths; mark and close stale issues/PRs. Issues labelled `risk`,
`security`, `dogfood`, `flaky-test`, `ci-nightly`, `epic`, `needs-triage`, `blocked` or `pinned`
never go stale.

---

### `labels.yml` — Sync labels

**Purpose**: Keep the repository labels equal to `.github/labels.yml`.

On a push to `main` that changes the file (or manually), `.github/scripts/sync-labels.py --apply`
creates missing labels and updates colors and descriptions. It never deletes a label; one that is
not in the file is listed in the job summary. The `lint-workflows` job runs
`sync-labels.py --check` on every PR: the file must be valid, and every label that the labeler,
Dependabot, the stale bot and the issue forms apply must be defined in it.

Issues, issue types and the **Yukta Roadmap** project are described in
[PROJECT.md](PROJECT.md).

---

## Conventions

- **All actions are pinned to full commit SHAs** (with `# vN` comments).
  Dependabot keeps the pins updated weekly. New steps must follow suit.
- **Least-privilege permissions**: workflows default to `contents: read`;
  jobs elevate only what they need.
- **`persist-credentials: false`** on every checkout.
- **Every job has `timeout-minutes`.**
- All workflow files carry SPDX headers (REUSE-enforced).

## Status Checks (Branch Protection)

Recommended required checks on `main`:

```
✓ Java CI with Gradle / Build & Check
✓ Java CI with Gradle / Dependency Review
✓ Security Scanning (Trivy) / Trivy Security Scan
✓ License Compliance Check / REUSE License Headers
✓ License Compliance Check / Dependency License Validation (Licensee)
✓ CodeQL / Analyze (java-kotlin)
```

## One-time Setup

- **Codecov**: create the repo at [codecov.io](https://about.codecov.io/)
  (free for open source) and add the `CODECOV_TOKEN` repository secret.
  Until then the upload step is `continue-on-error` and simply skips.
- **Scorecard badge**: appears after the first `scorecard.yml` run on `main`.

## Manual Triggers

```bash
gh workflow run security.yml          # on-demand security scan
gh workflow run ci.yml                # full build
gh workflow run license-compliance.yml
```

## Troubleshooting

### "Why did CI skip entirely on my PR?"
Nothing under a module directory changed (e.g. docs-only PR). The `changes`
job summary shows the build plan.

### "Why did my one-module PR rebuild half the repo?"
Every module that depends on a changed module (directly or transitively) is
re-tested. `messaging` and `plugin-api` are consumed by every JVM module.

### "I want the whole build on my PR"
Add the `ci:full` label and re-run CI (or push). Useful for interface changes
whose effect isn't visible through Gradle dependencies (reflection, resources).

### "Security scan flags a CVE my PR didn't introduce"
On PRs the Trivy vulnerability scan is report-only. Only
`dependency-review` (new dependencies) and secret findings block PRs.
Pre-existing CVEs are enforced on `main` — fix or add a justified entry to
`.trivyignore`.

### "Why does building take so long on main?"
Full build (all modules, all gates) runs on every push to main, split into
parallel shards (`platform`, `infra`, `plugins`, `cli`, `ui`, plus the PostgreSQL
and Plus acceptance tests). `.github/scripts/ci-tasks.sh` picks the Gradle tasks per shard;
shards with nothing to do are not started. A separate `Fast Lint` job
(Checkstyle, PMD, OpenGrep) reports style failures within minutes.

To catch those before pushing, enable the pre-push hook once:
`git config core.hooksPath .githooks`. Test locally first with `./gradlew check`.

---

## File References

- **CI workflow**: `.github/workflows/ci.yml`
- **Security workflow**: `.github/workflows/security.yml`
- **License Compliance workflow**: `.github/workflows/license-compliance.yml`
- **Compliance Summary workflow**: `.github/workflows/compliance-summary.yml`
- **CodeQL workflow**: `.github/workflows/codeql.yml`
- **Scorecard workflow**: `.github/workflows/scorecard.yml`
- **Compliance reporting guide**: `.github/COMPLIANCE_REPORTING.md`
- **Security scanning docs**: `.github/SECURITY_SCANNING.md`
- **Trivy ignore list**: `.trivyignore`

## Release (`release.yml`)

Pushing a `v*` tag builds the CLI for all platforms (with `SHA256SUMS`), attaches build-provenance
attestations, and creates a GitHub release. Running it via `workflow_dispatch` is a dry run: nothing
is published. The container image (GraalVM native compile) is built locally, not in CI.

## CI additions

- `CI Gate` is the single check to require in branch protection.
- `Lint Workflows` fails if a `project()` dependency names a module missing from `settings.gradle.kts`.
- A new module needs no CI edits: the planner reads `settings.gradle.kts` and the build files.
- **Nightly report**: `.github/workflows/nightly-report.yml`
- **Dependabot auto-merge**: `.github/workflows/dependabot-automerge.yml`
- **Code owners** (review requests per area): `.github/CODEOWNERS`

---

### `cla.yml` — Contributor License Agreement

**Purpose**: Ask outside contributors to sign `CLA.md` (or `CLA-corporate.md`) and record it on the `cla-signatures` branch

**Triggered on**: Pull request (`pull_request_target`, never checks out PR code) and the signing comment
