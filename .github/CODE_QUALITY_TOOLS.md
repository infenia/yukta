# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Code Quality Tools & Bot Configuration

This document describes the automated code quality tools configured for the Yukta project.

## Overview

The Yukta repository integrates several automated code quality and review tools:

1. **Code Rabbit** - AI-powered code review
2. **GitHub Code Quality Bot** - Automated quality metrics and enforcement
3. **GitHub Stale Action** - Issue and PR lifecycle management
4. **GitHub Labeler** - Automatic PR labeling
5. **JaCoCo** - Code coverage analysis
6. **Checkstyle** - Code style enforcement
7. **PMD** - Code quality and complexity analysis
8. **SpotBugs** - Bug detection
9. **OpenGrep** - Security and best-practice scanning
10. **Trivy** - Vulnerability scanning

## Configuration Files

### `.coderabbit.yaml`
Code Rabbit configuration for AI-powered code review.

**Key Features:**
- Automatic review of every PR
- Security issue detection (high severity)
- Performance issue detection (medium severity)
- Best practices enforcement
- Code complexity checking (max 10)
- Method length checking (max 50 lines)
- Class length checking (max 300 lines)
- Lombok and Spring Boot annotation analysis
- JacoCo test coverage integration

**How It Works:**
- Code Rabbit automatically reviews PRs when they're created or updated
- Provides comments with inline suggestions
- Can be triggered manually or customized per-PR

**Configuration Location:** `/.coderabbit.yaml`

### `.github/code-quality-bot.yml`
GitHub Code Quality Bot configuration for quality metrics enforcement.

**Key Features:**
- Code coverage thresholds (80% for new code, 70% overall)
- Cyclomatic complexity limits (max 10 per method)
- Cognitive complexity limits (max 15 per method)
- Code duplication detection (max 3%)
- Dependency analysis and vulnerability checks
- Security issue detection
- License compatibility checking

**Quality Gates:**
- All required checks must pass for PR approval
- PRs blocked if quality gates fail
- Automatic change requests on quality issues

**Configuration Location:** `/.github/code-quality-bot.yml`

### `.github/labeler.yml`
Automatic PR labeling based on file changes and branch naming.

**Labels Applied:**
- **Module labels**: `core`, `web`, `boot`, `messaging`, `cli`, `ui`
- **Plugin labels**: `plugins`, `triggers`, `processors`, `terminals`
- **Infrastructure labels**: `build`, `documentation`, `test`, `dependencies`
- **Type labels** (from branch): `type/feature`, `type/bugfix`, `type/chore`, `type/refactor`, `type/docs`, `type/test`

**Configuration Location:** `/.github/labeler.yml`

## GitHub Actions Workflows

### 1. Code Quality Workflow (`.github/workflows/code-quality.yml`)
Runs comprehensive code quality checks on every PR and push to main.

**Triggers:**
- PR to main
- Push to main
- Manual trigger (`workflow_dispatch`)

**Steps:**
1. Runs JaCoCo coverage analysis
2. Analyzes code complexity with PMD
3. Runs SpotBugs and Checkstyle
4. Generates coverage report comments
5. Validates coverage thresholds
6. Runs security/dependency checks
7. Posts quality summary comment

**Artifacts Generated:**
- JaCoCo coverage reports
- SpotBugs reports
- Checkstyle reports
- PMD reports

### 2. Stale Issues & PRs Workflow (`.github/workflows/stale.yml`)
Automatically manages stale issues and pull requests.

**Triggers:**
- Daily at 5:30 AM IST (00:00 UTC)
- Manual trigger

**Behavior:**
- **Issues**: Marked stale after 60 days → auto-closed after 14 more days
- **PRs**: Marked stale after 30 days → auto-closed after 7 more days
- **Exemptions**: Issues/PRs with `blocked`, `pinned`, `epic`, `work-in-progress` labels
- **Reactivation**: Removes stale label when new comments or commits are added

### 3. PR Auto-Labeler Workflow (`.github/workflows/labeler.yml`)
Automatically applies labels to PRs based on file changes.

**Triggers:**
- PR opened, reopened, or synchronized
- Manual trigger

**Labels Applied:**
- Determined by files changed and branch naming convention

### 4. Existing CI Workflow (`.github/workflows/ci.yml`)
Main CI pipeline with Java builds, tests, and security scanning.

**Includes:**
- Gradle build and tests
- Checkstyle, PMD, SpotBugs quality gates
- OpenGrep security scanning
- Trivy vulnerability scanning
- SBOM generation
- Dependency graph submission

## Quality Thresholds

| Metric | Threshold | Notes |
|--------|-----------|-------|
| New Code Coverage | 80% | Minimum for new code |
| Overall Coverage | 70% | Project-wide minimum |
| Cyclomatic Complexity | ≤ 10 | Per method maximum |
| Cognitive Complexity | ≤ 15 | Per method maximum |
| Code Duplication | ≤ 3% | Maximum allowed |
| Method Length | ≤ 50 lines | Enforced by Code Rabbit |
| Class Length | ≤ 300 lines | Enforced by Code Rabbit |

## Local Setup

### Install Code Rabbit CLI (Optional)
```bash
# Code Rabbit provides a CLI for local testing
npm install -g coderabbit-cli

# Run local review
coderabbit review .
```

### Run Quality Checks Locally
```bash
# Run all quality checks (including coverage, complexity, style, bugs)
./gradlew check

# Generate JaCoCo coverage report
./gradlew jacocoTestReport

# View coverage report
open build/reports/jacoco/test/html/index.html

# Run PMD analysis
./gradlew pmdMain pmdTest

# Run SpotBugs
./gradlew spotbugsMain spotbugsTest

# Format code (Spotless)
./gradlew spotlessApply
```

## PR Review Process

1. **Create PR** → Auto-labeler applies relevant labels
2. **PR Created** → Code Rabbit automatically reviews and comments
3. **GitHub Actions** → Runs CI pipeline and quality checks
4. **Code Quality Bot** → Validates metrics against thresholds
5. **Review Comments** → Code Rabbit and GitHub provide inline suggestions
6. **Quality Gates** → PR blocked if checks fail
7. **Approve & Merge** → Once all checks pass

## Code Rabbit Features in Action

### Example PR Review
Code Rabbit will comment on:
- Security vulnerabilities (SQL injection, XSS, etc.)
- Performance issues (N+1 queries, inefficient algorithms)
- Best practices violations
- Code complexity issues
- Duplicate code
- Missing error handling
- Reactive stream patterns
- Lombok annotation usage
- Spring Boot configuration

### Custom Instructions for This Project
Code Rabbit is configured to focus on:
- ✓ Proper Lombok annotation usage
- ✓ Spring Boot WebFlux reactive patterns
- ✓ Spring AI MCP server configurations
- ✓ Plugin-api compliance
- ✓ JaCoCo coverage thresholds
- ✓ Apache License 2.0 headers
- ✓ Gradle conventions and build-logic
- ✓ Trivy security scan compliance

## FAQ

### Why are some PRs auto-closed as stale?
PRs are closed after 30 days of inactivity. Push commits or comment to keep them open. Add `blocked` or `pinned` labels to exempt from auto-closing.

### How do I bypass Code Rabbit review?
Code Rabbit runs automatically. You cannot bypass it, but you can:
- Address its findings
- Use `@coderabbit ignore` in comments (for specific lines)
- Request human review to override

### What if coverage drops below threshold?
The quality check workflow will:
1. Flag coverage regression
2. Request changes on the PR
3. Block merge until coverage is restored

### Can I ignore specific violations?
Yes, some violations can be ignored with:
- `@SuppressWarnings` annotations
- Specific `// NOPMD`, `// SpotBugs` comments
- Exclusions in `.github/code-quality-bot.yml`

### How often does stale cleanup run?
The stale workflow runs daily at 5:30 AM IST (00:00 UTC). Manual trigger available.

## Disabling/Customizing Tools

### Disable Code Rabbit
Edit `.coderabbit.yaml`:
```yaml
reviews:
  auto_review: false
```

### Disable Stale Workflow
Delete `.github/workflows/stale.yml` or disable in GitHub UI.

### Adjust Coverage Thresholds
Update in `.github/workflows/code-quality.yml` and `build-logic/`.

## Integration with IDE

### VS Code
- Install [Code Rabbit VS Code Extension](https://marketplace.visualstudio.com/items?itemName=CodeRabbit.coderabbit)
- Get AI reviews inline while coding

### IntelliJ IDEA
- Install Code Rabbit plugin from Jetbrains marketplace
- Review suggestions in real-time

## Resources

- [Code Rabbit Documentation](https://docs.coderabbit.ai/)
- [GitHub Actions Stale Action](https://github.com/actions/stale)
- [GitHub Actions Labeler](https://github.com/actions/labeler)
- [JaCoCo Code Coverage](https://www.eclemma.org/jacoco/)
- [PMD Code Quality Rules](https://pmd.github.io/)
- [SpotBugs](https://spotbugs.github.io/)
- [OpenGrep Security Scanning](https://github.com/opengrep/opengrep)
- [Trivy Vulnerability Scanner](https://github.com/aquasecurity/trivy)

## Support

For questions or issues:
1. Check the tool's documentation
2. Review GitHub Actions logs
3. Check PR comments for specific findings
4. Contact the development team
