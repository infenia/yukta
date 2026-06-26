# Semgrep Configuration

This directory contains the Semgrep static analysis configuration for the Yukta project.

## Overview

Semgrep is integrated into the Gradle build via the `quality-conventions` plugin. It runs as part of the quality checks during the build process and scans Java source code for security vulnerabilities, code quality issues, and best practice violations.

## Configuration

- **Config File**: `.semgrep.yml` - Defines rulesets and scan configuration
- **Output**: Scan reports are generated in `build/reports/semgrep/semgrep-report.sarif` (SARIF format)

## Running Semgrep

### Standalone Scan
```bash
./gradlew semgrep
```

### As Part of Quality Checks
```bash
./gradlew check
```

The `semgrep` task is automatically included in the `check` task and will scan all Java source files in `src/main/java`.

## Rules

The default configuration uses Semgrep's public registry rules:
- `p/security-audit` - Security vulnerability detection
- `p/java` - Java best practices and code quality rules

## Behavior

- **Exit Codes**:
  - `0` - No issues found
  - `1` - Issues found (reported but not a build failure)
  - `>1` - Scan error (build fails)

- **Excluded Paths**:
  - Test code (`*/test/*`)
  - Build directories (`*/build/*`, `*/.gradle/*`)

## Customization

To add custom rules or modify behavior:
1. Edit `.semgrep.yml` to adjust rulesets or configuration
2. Run `./gradlew semgrep` to validate changes
3. Review the generated SARIF report in `build/reports/semgrep/`

## Requirements

Semgrep CLI must be installed and available in the `PATH`:
```bash
# Install Semgrep
pip3 install semgrep

# Or via Homebrew (macOS)
brew install semgrep

# Or via Docker
docker run -v /path/to/project:/workspace returntocorp/semgrep semgrep scan /workspace
```

## CI/CD Integration

The `semgrep` task is automatically run during:
- Local builds via `./gradlew check`
- CI pipelines when quality gates are enforced

Review the generated SARIF report for detailed findings and remediation guidance.
