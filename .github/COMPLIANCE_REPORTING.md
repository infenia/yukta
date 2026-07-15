# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Compliance & Quality Reporting

This document explains how Yukta reports on license compliance and code quality checks.

## Overview

Compliance and quality checks are organized into three workflows:

1. **License Compliance Check** (`license-compliance.yml`) — REUSE headers, dependency licenses, Trivy scans
2. **Code Quality Checks** (`code-quality.yml`) — Coverage, complexity, static analysis, security
3. **Compliance & Quality Summary** (`compliance-summary.yml`) — Aggregates and reports unified status

```
Your PR
  ├─→ license-compliance.yml ────┐
  ├─→ code-quality.yml ──────────┼→ compliance-summary.yml → Unified PR Comment
  └─→ (other CI/security) ───────┘
```

## What Gets Reported

### In PR Comments

When you open or update a PR, you'll see a **single unified comment** with:

```
📋 Compliance & Quality Summary

✅ License Compliance
| REUSE Headers             | ✅ SUCCESS |
| Dependency Licenses (Licensee) | ✅ SUCCESS |
| License Scanning (Trivy)  | ✅ SUCCESS |

📊 Code Quality
| JaCoCo Coverage       | ✅ SUCCESS |
| PMD Complexity        | ✅ SUCCESS |
| SpotBugs Analysis     | ✅ SUCCESS |
| Security Checks       | ✅ SUCCESS |

📚 Allowed Licenses
[...list...]

📎 Detailed Reports
- Link to License Compliance workflow
- Link to Code Quality workflow
```

### Status Meanings

- **✅ SUCCESS** — Check passed
- **❌ FAILURE** — Check failed; review the linked workflow run
- **⚠️ NEUTRAL** — Check ran but with warnings
- **ℹ️ NOT_RUN** — Check did not run (e.g., only affected code in that module)

### Workflow Artifacts

Each workflow produces detailed reports in its artifacts:

| Workflow | Artifacts |
|----------|-----------|
| **License Compliance** | Licensee reports, Trivy SARIF files |
| **Code Quality** | JaCoCo coverage, PMD reports, SpotBugs reports, Checkstyle reports |

Access artifacts by:
1. Go to workflow run in GitHub Actions
2. Scroll down to "Artifacts" section
3. Download the `.zip` file

## Workflow Triggers

### On Pull Request


- All three workflows trigger automatically
- `compliance-summary.yml` waits for license and quality workflows to complete
- Unified comment posted once all data is available

### On Push to `main`
- License and quality checks run in parallel
- Summary aggregates results (if applicable)

### Manual Trigger
```bash
# Run license compliance check
gh workflow run license-compliance.yml

# Run code quality checks
gh workflow run code-quality.yml
```

## Interpreting Results

### If License Compliance Fails

Check the "License Compliance" section in the unified report:

1. **REUSE Headers failed** → Some files missing Apache-2.0 license headers
   - Run `reuse addheader` to auto-fix
   - See: https://reuse.software/

2. **Licensee failed** → A dependency has an incompatible license
   - Check the Licensee report artifact
   - Update `.licenserc.json` if needed or add an exception

3. **Trivy failed** → License issue detected by Trivy scanner
   - Check Trivy SARIF report in artifacts
   - Usually a transitive dependency issue

### If Code Quality Fails

Check the "Code Quality" section in the unified report:

1. **Coverage failed** → JaCoCo coverage below threshold
   - Run `./gradlew jacocoTestReport` locally to see gap
   - Add tests for uncovered code

2. **Complexity failed** → PMD flagged complex methods
   - Run `./gradlew pmdMain pmdTest` locally
   - Refactor complex methods into smaller ones

3. **SpotBugs failed** → Bug pattern detected
   - Run `./gradlew spotbugsMain spotbugsTest` locally
   - Review the report for suggested fixes

4. **Security failed** → OWASP or best-practice violation
   - Run `./gradlew dependencyCheck` locally
   - Review and fix reported vulnerabilities

## Local Testing

Before pushing, run these checks locally to catch issues early:

```bash
# Full compliance & quality check
./gradlew check

# License headers
reuse lint

# Licenses
./gradlew licensee

# Trivy (if installed)
trivy fs .

# Coverage
./gradlew jacocoTestReport

# Code quality
./gradlew pmdMain spotbugsMain checkstyleMain
```

## Troubleshooting

### "Why is the summary not posted?"

The aggregator workflow runs after license and quality workflows complete. If they're still in progress, wait a moment or refresh the PR.

**If no comment appears after 5 minutes:**
- Check the `compliance-summary` workflow in the Actions tab
- Ensure both `license-compliance` and `code-quality` workflows ran
- If one failed to trigger, manually run it

### "Can I merge if checks are failing?"

No. Branch protection requires all quality checks to pass. Fix issues and push a new commit to re-run checks.

### "How do I ignore a license issue?"

Add to `.licenserc.json`:
```json
{
  "approvedLicenses": ["Apache-2.0", "MIT", "your-license-here"],
  "ignoredPackages": {
    "package-name": "reason"
  }
}
```

Then rerun the workflow.

### "How do I suppress a SpotBugs warning?"

Add `@SuppressFBWarnings("BUG_CODE")` to the method or class with a `justification` parameter.

---

## See Also

- [WORKFLOWS.md](./WORKFLOWS.md) — Overview of all GitHub workflows
- [SECURITY_SCANNING.md](./SECURITY_SCANNING.md) — Detailed security scanning setup
- [Coding Standards](../.claude/rules/coding-standards.md) — Code quality requirements
