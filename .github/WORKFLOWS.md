# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

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
| PR opened/updated | any | Build changed modules **and their dependents** |
| Push | main | Full build — every module's tests + quality gates |
| Scheduled | daily @ 5:30 AM IST | Full build (cache refresh) |

**Jobs**:
1. `changes` — Detect which modules changed (fast, ~5s). Every Gradle
   module in `settings.gradle.kts` is listed; a module missing here would
   silently skip CI.
2. `build` — Compile, test, quality checks (`build` includes `check`:
   Checkstyle, PMD, SpotBugs, OpenGrep, JaCoCo coverage verification).
   Changed library modules run `buildDependents` so consumers are
   re-tested. Publishes a JUnit summary, uploads coverage to Codecov on
   main, submits the dependency graph (non-fork), uploads `build-reports`.
3. `dependency-review` — Fails PRs that introduce dependencies with
   known HIGH/CRITICAL vulnerabilities (skipped for fork PRs).

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
| **Licensee** | Dependency License Validation | Ensures all dependencies have Apache-2.0 compatible licenses |
| **Trivy** | License Scanning | Scans lock files for forbidden/restricted licenses |

**Jobs**: `reuse-compliance`, `gradle-license-validation`, `trivy-license-scan`

**Triggered on**: Push to main, PR, manual trigger

**Artifacts**: `licensee-reports`; Trivy license SARIF in the Security tab.
Results are summarized in the job summary and the unified PR comment.

---

### `compliance-summary.yml` — Compliance & Quality Summary

**Purpose**: One sticky PR comment aggregating CI, license, and security results

**How it works**: Triggered by `workflow_run` completions of the three
workflows above. Resolves the PR from the run, lists every job of each
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

Auto-label PRs by touched paths; mark and close stale issues/PRs.

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
Nothing under a known module path changed (e.g. docs-only PR). The `changes`
job output lists what was detected.

### "Why did my one-module PR rebuild half the repo?"
Library modules (e.g. `messaging`, `core`, `plugin-api`) run
`buildDependents` so everything that consumes them is re-tested before merge.

### "Security scan flags a CVE my PR didn't introduce"
On PRs the Trivy vulnerability scan is report-only. Only
`dependency-review` (new dependencies) and secret findings block PRs.
Pre-existing CVEs are enforced on `main` — fix or add a justified entry to
`.trivyignore`.

### "Why does building take so long on main?"
Full build (all modules, all gates) runs on every push to main. Test locally
first with `./gradlew check`.

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
