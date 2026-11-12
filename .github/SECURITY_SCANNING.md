# Security Scanning with Trivy

This document describes the security scanning infrastructure powered by Trivy, which runs in a **separate, dedicated workflow** (`security.yml`).

## Overview

The security scanning workflow runs comprehensive security scans to detect:
- **Vulnerabilities** in dependencies (from the Gradle build files)
- **Secrets** (API keys, tokens, credentials)
- **Misconfigurations** (Dockerfile, Kubernetes manifests, etc.)
- **SBOMs** (Software Bill of Materials for compliance)
- **License compliance** issues

## Workflow Architecture

`security.yml` is one job (`Trivy Security Scan`) that runs on its own events. It does not wait for
`ci.yml`: both start together and both are required checks on `main`.

### Triggers and blocking

| Trigger | When | Secrets | HIGH/CRITICAL vulnerabilities and misconfigurations |
|---------|------|---------|------------------------------------------------------|
| `pull_request`, `merge_group` | PR to `main`, merge queue | **blocking** | **blocking when a fix exists** (`--ignore-unfixed`); an unfixed CVE cannot be acted on |
| `push` | `main` | **blocking** | **blocking**, fixed or not |
| `schedule` | Monday 6:30 AM IST | **blocking** | **blocking**: rescans `main` for newly published CVEs |
| `workflow_dispatch` | manual | **blocking** | **blocking** |

The SBOM (CycloneDX) is generated on every run except PRs. New vulnerable dependencies in a PR are also
blocked by `dependency-review` in `ci.yml`. Artifacts: `security-reports-{run_id}` (SARIF and SBOM, 30 days).
SARIF goes to the Security tab, except for fork PRs (their token cannot upload).

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
Scans the repository for known vulnerabilities in dependencies. Trivy reads Go (`cli/go.mod`), npm (`ui/pnpm-lock.yaml`) and the Dockerfiles. It does **not** scan Gradle: no lock file is committed, so it cannot resolve the Java dependencies. Java is covered by GitHub's dependency graph instead: the `dependency-submission` job in `ci.yml` (push to `main` and pull requests) submits the resolved Gradle graph, the **Dependency Review** job fails a pull request that adds a dependency with a HIGH or CRITICAL advisory, and Dependabot alerts report the ones already in the tree.

```bash
trivy fs --scanners vuln --severity HIGH,CRITICAL .
```

**Behavior**:
- On `main`, the weekly schedule and manual runs: fails on any HIGH or CRITICAL finding
- On pull requests: fails only on findings that have a fix (`--ignore-unfixed`); an unfixed CVE cannot be acted on, so it is left to `main` and the schedule
- Reports in JSON: `trivy-deps.json`

**What triggers a failure**:
- A HIGH/CRITICAL CVE with a fixed version in a Go, npm or Docker dependency (any CVE on `main`)
- A new Gradle dependency with a HIGH/CRITICAL advisory (Dependency Review)

**Resolution**:
1. Update the vulnerable dependency to a patched version
2. If a patch isn't available, add to `.trivyignore` with justification

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

The vulnerability step is a separate "Enforce vulnerability gate" step, after the SARIF and table reports, so a
failing run still uploads its findings.

| Scenario | Event | Result |
|----------|-------|--------|
| Secret in a PR | `pull_request` | FAIL, PR cannot merge |
| Fixable HIGH CVE in a PR | `pull_request` | FAIL |
| Unfixable HIGH CVE already in `main` | `pull_request` | pass (reported) |
| Same CVE | `push`, `schedule` | FAIL: fix, or add a justified `.trivyignore` entry |

## Local Testing

Run Trivy scans locally before pushing:

```bash
# Run all security scans
trivy fs --severity HIGH,CRITICAL .

# Secrets only
trivy secret .

# Dependencies only
trivy fs --scanners vuln --severity HIGH,CRITICAL .

# Generate JSON for analysis
trivy fs --severity HIGH,CRITICAL --format json --output results.json .

# Check cache status
trivy image --download-db-only

# Clear cache if needed
rm -rf ~/.cache/trivy
```

## Common Issues

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

## Branch protection

`Trivy Security Scan` is one of the nine checks the `main-branch` ruleset requires; the full list is in
[WORKFLOWS.md](WORKFLOWS.md#status-checks-branch-protection).

## Permissions

`security.yml` has `contents: read` at the top and `security-events: write` on the job (SARIF upload).

## Troubleshooting

### A PR fails the scan for a CVE I did not add

Only CVEs with a fix block a PR. Bump the dependency, or add a justified, dated entry to `.trivyignore`.

### SARIF upload fails

It does not fail the scan; see the upload step's log. Fork PRs skip the upload on purpose.

## References

- [Trivy Documentation](https://aquasecurity.github.io/trivy/)
- [Trivy Secrets Scanner](https://aquasecurity.github.io/trivy/latest/docs/secret/scanning/)
- [CycloneDX SBOM Standard](https://cyclonedx.org/)
- [SARIF Format](https://sarifweb.azurewebsites.net/)
- [GitHub Branch Protection](https://docs.github.com/en/repositories/configuring-branches-and-merges-in-your-repository/managing-protected-branches/about-protected-branches)
- [GitHub Workflow Triggers](https://docs.github.com/en/actions/using-workflows/events-that-trigger-workflows)
