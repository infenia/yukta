# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Security Scanning with Trivy

This document describes the security scanning infrastructure powered by Trivy, which runs in a **separate, dedicated workflow** (`security.yml`).

## Overview

The security scanning workflow runs comprehensive security scans to detect:
- **Vulnerabilities** in dependencies (from gradle.lockfile)
- **Secrets** (API keys, tokens, credentials)
- **Misconfigurations** (Dockerfile, Kubernetes manifests, etc.)
- **SBOMs** (Software Bill of Materials for compliance)
- **License compliance** issues

## Workflow Architecture

**Two workflows, one purpose:**

```
┌─────────────────────────────────┐
│  ci.yml (Build & Test)          │
│  ─ Compile, test, quality gates  │
│  ─ Runs on: push/PR             │
└──────────────┬──────────────────┘
               │
        (workflow_run trigger)
               │
┌──────────────▼──────────────────┐
│  security.yml (Trivy Scans)     │
│  ─ Secrets, vulns, SBOMs        │
│  ─ Blocking on main             │
│  ─ Async on PRs                 │
└─────────────────────────────────┘
```

### Blocking Behavior

| Context | Trigger | Behavior | Failure Impact |
|---------|---------|----------|-----------------|
| **main branch** | Direct push | Blocking scan | **Fails push** if HIGH/CRITICAL |
| **PR branch** | After CI succeeds | Blocking scan (via `workflow_run`) | **Fails PR check** if HIGH/CRITICAL |
| **Manual** | `workflow_dispatch` | Blocking scan | **Fails** if HIGH/CRITICAL |

## Workflow Triggers

### `push` → main branch
- **When**: Direct push to main (typically via PR merge)
- **Behavior**: Blocking — security scan must pass
- **Fails on**: HIGH/CRITICAL secrets, vulnerabilities, misconfigurations
- **Artifacts**: Uploaded to `security-reports-{run_id}`

### `workflow_run` ← ci.yml completion
- **When**: After CI workflow completes on PR
- **Condition**: Only runs if CI succeeded
- **Behavior**: Mandatory — security findings block PR merge
- **Fails on**: HIGH/CRITICAL secrets, vulnerabilities, misconfigurations (like main)
- **Impact**: PR check will fail if security issues detected
- **Artifacts**: Uploaded to `security-reports-{run_id}`

### `workflow_dispatch` (manual)
- **When**: Triggered manually via GitHub Actions UI
- **Behavior**: Mandatory (same as main/PR)
- **Use case**: On-demand security audits, testing scan changes

## Scan Types

### 1. **Secrets Scanning** 🔐
Detects hardcoded secrets, API keys, tokens, credentials in the codebase.

```bash
trivy secret .
```

**Behavior**:
- Warns on all detected secrets (info/low/medium/high)
- **Fails the build** only if HIGH/CRITICAL secrets are found
- Reports in JSON format: `trivy-secrets.json`

**What triggers a failure**:
- AWS credentials, private keys (RSA, ED25519)
- GitHub/GitLab tokens
- API keys (Slack, SendGrid, etc.)
- Database connection strings with embedded passwords

**To suppress false positives**: Add to `.trivyignore` with reason

### 2. **Dependency Vulnerability Scanning** 📦
Focuses on gradle.lockfile to detect known vulnerabilities in direct and transitive dependencies.

```bash
trivy fs --severity HIGH,CRITICAL --file-patterns "gradle.lockfile" .
```

**Behavior**:
- Fails on HIGH or CRITICAL vulnerabilities
- Skips test-only and build-time dependencies (not in runtime)
- Reports in JSON: `trivy-deps.json`

**What triggers a failure**:
- Any HIGH/CRITICAL CVE in production dependencies
- Known exploitable vulnerabilities with no patch available

**Resolution**:
1. Update the vulnerable dependency to a patched version
2. Run `./gradlew lockfile` to regenerate gradle.lockfile
3. If a patch isn't available, add to `.trivyignore` with justification

### 3. **Filesystem Vulnerability Scanning** 🔍
Broad scan for vulnerabilities and misconfigurations across the repository.

```bash
trivy fs --severity HIGH,CRITICAL --skip-dirs ".git,.github,build,*/build" .
```

**Behavior**:
- Scans all files (Java, YAML, Docker, shell scripts, etc.)
- Fails on HIGH/CRITICAL issues
- Generates SARIF report for GitHub Security dashboard
- Reports in JSON: `trivy-fs.json`

**What it detects**:
- Vulnerable base images in Dockerfiles
- Insecure Kubernetes configurations
- Hard-coded credentials or API endpoints
- Deprecated dependencies

### 4. **SBOM Generation** 📋
Creates a Software Bill of Materials from compiled JAR artifacts for compliance/audit.

```bash
trivy image --format cyclonedx --output sbom-{module}.json file://{jar}
```

**Output**: CycloneDX format (standardized SBOM)
**Use case**: Supply chain transparency, license compliance, incident response

### 5. **License Compliance Scanning** ⚖️ (non-blocking)
Scans for license compliance issues without failing the build.

```bash
trivy fs --scanners license --severity CRITICAL .
```

**Behavior**:
- Reports all detected licenses in JSON: `trivy-licenses.json`
- Does NOT fail the build (informational only)
- Useful for license audit and compliance review

## Performance Optimizations

### Caching
The CI pipeline caches Trivy's vulnerability database (`~/.cache/trivy`) to avoid re-downloading on every run:

```yaml
- name: Cache Trivy vulnerability database
  uses: actions/cache@v6
  with:
    path: ~/.cache/trivy
    key: trivy-db-${{ runner.os }}
```

**Benefit**: ~30-50% faster scans on repeated runs.

## Managing False Positives

### Using `.trivyignore`

Create a `.trivyignore` file in the repo root to suppress known false positives or accepted risks:

```
# Ignore a specific CVE with expiration
CVE-2024-12345 2026-12-31 # Will be patched in Q4 2026 spring-boot release

# Ignore with reason only
CVE-2024-99999 # Known false positive, misidentified by scanner
```

**Guidelines**:
- Always include a reason (who approved, why, when to revisit)
- Use expiration dates for temporary ignores
- Document security decisions in PR comments
- Never ignore CRITICAL vulnerabilities without explicit approval

### When to Ignore
✅ **OK to ignore**:
- Test-only dependencies (though these should fail the build first)
- False positives proven not to apply
- Known vulnerabilities with accepted risk and compensating controls

❌ **Do NOT ignore**:
- Production dependency vulnerabilities without a plan to patch
- Secrets detected in code (remove them or regenerate if compromised)

## Reports & Artifacts

All scan results are uploaded to GitHub Artifacts and available for download:

```
security-reports/
├── trivy-fs-results.sarif      # SARIF for GitHub Security dashboard
├── trivy-fs.json                # Full filesystem scan (JSON)
├── trivy-deps.json              # Dependency scan (JSON)
├── trivy-secrets.json           # Secrets scan (JSON)
├── trivy-licenses.json          # License scan (JSON)
└── sbom-{module}.json           # SBOMs for each module (CycloneDX)
```

### GitHub Security Integration

The SARIF report is automatically uploaded to GitHub's Security tab:
- **Path**: Repository → Security → Code scanning alerts
- **Shows**: Trivy findings with source file, line, and fix recommendations

## How Blocking Works

All Trivy scans are **mandatory** across all contexts. The workflow fails if HIGH/CRITICAL findings are detected, regardless of whether it's main, PR, or manual trigger.

**Example Scenarios:**

| Scenario | Event | Secrets Found | Result |
|----------|-------|---------------|--------|
| Push to main with critical secret | push | HIGH | ❌ FAIL — push blocked |
| PR review finds critical secret | workflow_run | HIGH | ❌ FAIL — PR merge blocked |
| Manual dispatch | workflow_dispatch | HIGH | ❌ FAIL — audit fails |

### GitHub Status Checks

- **Main (push)**: Shows as ❌ **Required status check** — must pass before merge
- **PR (workflow_run)**: Shows as ❌ **Required status check** — must pass before merge  
- **Manual**: Shows as ❌ in Actions tab — must pass to complete run

## Local Testing

Run Trivy scans locally before pushing:

```bash
# Run all security scans
trivy fs --severity HIGH,CRITICAL .

# Secrets only
trivy secret .

# Dependencies only
trivy fs --severity HIGH,CRITICAL --file-patterns "gradle.lockfile" .

# Generate JSON for analysis
trivy fs --severity HIGH,CRITICAL --format json --output results.json .

# Check cache status
trivy image --download-db-only

# Clear cache if needed
rm -rf ~/.cache/trivy
```

## Common Issues

### Build fails with "which is not part of the dependency lock state"

This is **not Trivy** — it's a Gradle lock file verification error. The build fails because a new dependency was added without updating gradle.lockfile:

```bash
./gradlew lockfile  # or boot:compileAotJava --write-locks
git add gradle.lockfile
git commit -m "chore: update dependency lock files"
```

### "Exit code 1" but no output

Trivy DB cache may be stale:

```bash
rm -rf ~/.cache/trivy
trivy image --download-db-only
```

### Secrets scan reports false positives

Add the false positive to `.trivyignore` with a clear reason:

```
# Example: build property that looks like a secret but isn't
SECRET-ID-12345 # Not a real API key, just a build timestamp
```

## Compliance & Audit

### For Compliance Reviews
Export all security reports and SBOMs:

```bash
# Download from GitHub Actions artifacts
gh run download <run-id> -n security-reports

# Or generate locally
trivy fs --format json --output compliance-report.json .
trivy image --format cyclonedx --output sbom-full.json file://./build/libs/boot-*.jar
```

## Configuring Branch Protection

To enforce security scans on main, set up GitHub Branch Protection:

**Steps:**
1. Go to **Settings** → **Branches** → **Branch protection rules**
2. Add rule for `main` branch
3. Check ✅ **Require status checks to pass before merging**
4. Search for and select:
   - `Java CI with Gradle / Build & Check` (from ci.yml)
   - `Security Scanning (Trivy) / Trivy Security Scan` (from security.yml)
5. Check ✅ **Require branches to be up to date before merging**
6. Click **Create**

**Result**: Both CI and security scans must pass before merging to main.

## GitHub Actions Setup Notes

### Permissions

The `security.yml` workflow requires these permissions:

```yaml
permissions:
  contents: read              # Read repo files
  checks: write              # Post check results
  security-events: write     # Upload SARIF to Security tab
```

These are already configured in `security.yml`.

### Artifact Management

- Artifacts named `security-reports-{run_id}` for each run
- Retained for 30 days
- Downloaded via: **Actions** → select workflow run → **Artifacts** section

## Troubleshooting

### Security scan runs after CI on PRs

This is expected behavior. The scan runs **after** CI completes via `workflow_run` trigger:
- ✅ CI checks show immediately
- ⏳ Security scan runs in background
- The PR **cannot be merged** while security scan is running — must wait for it to complete and pass

To see security findings: Check **Artifacts** in the Actions tab or GitHub Security dashboard.

### SARIF upload fails silently

The workflow uses `continue-on-error: true` for SARIF uploads. This is intentional — a failed SARIF upload shouldn't fail the entire security scan. Check the job log for details.

## References

- [Trivy Documentation](https://aquasecurity.github.io/trivy/)
- [Trivy Secrets Scanner](https://aquasecurity.github.io/trivy/latest/docs/secret/scanning/)
- [CycloneDX SBOM Standard](https://cyclonedx.org/)
- [SARIF Format](https://sarifweb.azurewebsites.net/)
- [GitHub Branch Protection](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)
- [GitHub Workflow Triggers](https://docs.github.com/en/actions/using-workflows/events-that-trigger-workflows)
