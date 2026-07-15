# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# GitHub Workflows Reference

Quick guide to the automated workflows in this repository.

## Overview

```
Your PR
    │
    ├─→ license-compliance.yml ─────────┐
    │   ├─ REUSE header validation      │
    │   ├─ Licensee dependency check    │
    │   └─ Trivy license scanning       │
    │                                    │
    ├─→ code-quality.yml ───────────────┼→ compliance-summary.yml → Unified PR Comment
    │   ├─ JaCoCo coverage              │   ├─ Aggregates results
    │   ├─ PMD complexity               │   └─ Posts single unified comment
    │   ├─ SpotBugs analysis            │
    │   └─ Checkstyle validation        │
    │                                    │
    └─→ security.yml ───────────────────┘
        ├─ Trivy vulnerability scans
        ├─ Secret scanning
        └─ SBOM generation
```

## Workflows

### `ci.yml` — Java CI with Gradle
**Purpose**: Build, test, and enforce quality gates

| When | Branch | Behavior |
|------|--------|----------|
| PR opened/updated | any | Build changed modules only |
| Push | main | Full build (all modules) |
| Scheduled | daily @ 5:30 AM IST | Full build (cache refresh) |

**Jobs**:
1. `changes` — Detect which modules changed (fast, ~5s)
2. `build` — Compile, test, quality checks
   - Full build on push to main
   - Partial build on PR (only changed modules)

**Exits with**:
- ✅ **0** if all checks pass
- ❌ **1** if build, tests, or quality gates fail

**Artifacts**:
- `test-reports` (if failed) — JUnit XML, coverage reports

---

### `license-compliance.yml` — License Compliance

**Purpose**: Validate dependency licenses and REUSE compliance

| Component | Check | Purpose |
|-----------|-------|---------|
| **Licensee** | Dependency License Validation | Ensures all dependencies have Apache-2.0 compatible licenses |
| **REUSE** | License Header Compliance | Verifies all files have SPDX license headers |
| **Trivy** | License Scanning | Scans for known license issues |

**Jobs**:
1. `reuse-compliance` — REUSE header check
2. `gradle-license-validation` — Licensee dependency validation
3. `trivy-license-scan` — Trivy license scanning

**Triggered on**: Push to main, PR, manual trigger

**Artifacts**:
- `licensee-reports` — Detailed license reports per module
- Trivy SARIF files (uploaded to GitHub Security tab)

---

### `code-quality.yml` — Code Quality Checks

**Purpose**: Enforce code quality, coverage, and static analysis

**Jobs**:
1. `code-quality` — All quality checks in one job
   - JaCoCo coverage analysis
   - PMD complexity analysis
   - SpotBugs static analysis
   - Checkstyle format validation
   - Security vulnerability scanning

**Triggered on**: Push to main, PR, manual trigger

**Artifacts**:
- `quality-reports` — JaCoCo, SpotBugs, PMD, Checkstyle reports

---

### `compliance-summary.yml` — Compliance & Quality Aggregator

**Purpose**: Consolidate license compliance and code quality results into a single PR comment

**Jobs**:
1. `aggregate-report` — Fetches results from license and quality workflows
   - Queries GitHub API for workflow run statuses
   - Aggregates individual job results
   - Posts unified PR comment with all statuses

**Triggered on**: After license-compliance.yml and code-quality.yml complete

**Output**: Single PR comment with:
- ✅ License Compliance section (REUSE, Licensee, Trivy status)
- 📊 Code Quality section (Coverage, Complexity, SpotBugs, Security status)
- Links to detailed reports

**Example output**:

```text
📋 Compliance & Quality Summary

✅ License Compliance
| REUSE Headers              | ✅ SUCCESS |
| Dependency Licenses (Licensee) | ✅ SUCCESS |
| License Scanning (Trivy)   | ✅ SUCCESS |

📊 Code Quality
| JaCoCo Coverage       | ✅ SUCCESS |
| PMD Complexity        | ✅ SUCCESS |
| SpotBugs Analysis     | ✅ SUCCESS |
| Security Checks       | ✅ SUCCESS |
```

---

### `security.yml` — Security Scanning (Trivy)
**Purpose**: Security scanning (secrets, vulns, SBOMs)

| When | Trigger | Behavior | Fails Build? |
|------|---------|----------|--------------|
| **Push to main** | Direct push | Blocking scan | ✅ YES |
| **After CI on PR** | workflow_run | Blocking scan | ✅ YES |
| **Manual** | workflow_dispatch | Blocking scan | ✅ YES |

**Jobs**:
1. `context` — Determine if running on main/PR/manual
2. `security` — Run all Trivy scans (secrets, vulns, SBOMs, license)

**Behavior** (all contexts):
- **Main (push)**: Fails workflow if HIGH/CRITICAL findings detected
- **PR (workflow_run)**: Fails workflow if HIGH/CRITICAL findings detected
- **Manual**: Fails workflow if HIGH/CRITICAL findings detected

**Exits with**:
- ✅ **0** if no HIGH/CRITICAL findings detected
- ❌ **1** if HIGH/CRITICAL findings detected (all contexts)

**Artifacts**:
- `security-reports-{run_id}` — Trivy JSON/SARIF, SBOMs, license reports
- SARIF automatically uploaded to GitHub Security tab

---

## Key Differences

| Feature | ci.yml | security.yml |
|---------|--------|--------------|
| **Speed** | ~5-10 min | ~2-3 min |
| **Blocks main** | ✅ Always | ✅ Always |
| **Blocks PRs** | ✅ Always | ✅ Always |
| **Runs on** | Each push/PR | After CI succeeds |
| **Artifacts** | Test reports | Security reports |
| **GitHub tab** | Checks | Security + Checks |

---

## Status Checks (Branch Protection)

Once configured, GitHub shows these required status checks before merge:

```
✓ Java CI with Gradle / Build & Check         (Required — from ci.yml)
✓ Security Scanning (Trivy) / Trivy Sec Scan  (Required — from security.yml)
  (on main)
```

On **PRs**:
- ✓ `ci.yml` is blocking (must pass)
- ✓ `security.yml` is blocking (must pass) — triggered after CI succeeds

---

## Viewing Results

### Build Results (ci.yml)
- **Location**: GitHub Actions → Java CI with Gradle
- **Status check**: Shows as ✅ or ❌ in PR
- **Logs**: Click workflow run → Build & Check job
- **Artifacts**: `test-reports` (if failed)

### Security Results (security.yml)
- **Location**: GitHub Actions → Security Scanning (Trivy)
- **Logs**: Click workflow run → Trivy Security Scan job
- **Artifacts**: `security-reports-{run_id}`
- **GitHub Security tab**: Repository → Security → Code scanning alerts
  - Shows HIGH/CRITICAL issues automatically

---

## Manual Triggers

### Trigger security scan manually
```bash
# Via GitHub CLI
gh workflow run security.yml

# Via GitHub UI
Settings → Actions → General → (scroll down)
Select "Security Scanning (Trivy)" → "Run workflow" → main branch
```

### Trigger full build manually
```bash
# Via GitHub CLI
gh workflow run "ci.yml"

# Via GitHub UI
Actions → Java CI with Gradle → "Run workflow" → main branch
```

---

## Troubleshooting

### "Why is security scan still running on my PR?"
Expected. `security.yml` runs **after** `ci.yml` completes (workflow_run trigger). PR checks show immediately, but security scan runs in the background.

**Can I merge while security scan is running?**
No — security scan is blocking. You must wait for the security scan to complete and pass before merging.

### "Why didn't security scan run?"
Possible reasons:
1. `ci.yml` failed — security.yml only runs if CI succeeded
2. `workflow_run` not triggered — check Actions tab for errors
3. Filtered by path? Check `.github/workflows/security.yml` triggers

### "Why does building take so long on main?"
Full build runs on every push to main. To speed up, use git config to batch commits or test locally first.

---

## Next Steps

1. **Test locally**: Run `./gradlew check` and `trivy fs .` before pushing
2. **Set up branch protection**: See `.github/SECURITY_SCANNING.md` for details
3. **Review security findings**: Check artifacts after each security scan
4. **Update `.trivyignore`**: As needed for false positives

---

## Understanding PR Comments

When you open a PR, you'll see:

1. **Compliance & Quality Summary** (main report)
   - Shows status of all license and quality checks
   - Single consolidated comment for easy review
   - Links to detailed reports

2. **Individual workflow comments** (if issues found)
   - Only posted if specific checks fail
   - Provides context-specific guidance

For detailed explanation, see [COMPLIANCE_REPORTING.md](./COMPLIANCE_REPORTING.md).

---

## File References

- **License Compliance workflow**: `.github/workflows/license-compliance.yml`
- **Code Quality workflow**: `.github/workflows/code-quality.yml`
- **Compliance Summary workflow**: `.github/workflows/compliance-summary.yml`
- **Security workflow**: `.github/workflows/security.yml`
- **CI workflow**: `.github/workflows/ci.yml`
- **Compliance reporting guide**: `.github/COMPLIANCE_REPORTING.md`
- **Security scanning docs**: `.github/SECURITY_SCANNING.md`
- **Trivy ignore list**: `.trivyignore`
