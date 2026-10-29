# OpenGrep Configuration

This directory contains the OpenGrep static analysis configuration for the Yukta project.

## Overview

OpenGrep is integrated into the Gradle build via the `quality-conventions` plugin. It runs as part of the quality checks during the build process and scans Java source code for security vulnerabilities, code quality issues, and best practice violations.

## Configuration

- **Config**: the pinned local ruleset `.semgrep.yml` in this directory (no network access at build time)
- **Output**: Scan reports are generated in `build/reports/opengrep/opengrep-report.sarif` (SARIF format)
- **Scope**: `src/main` of each module (Java and resources)

## Running OpenGrep

### Standalone Scan
```bash
./gradlew opengrep
```

### As Part of Quality Checks
```bash
./gradlew check
```

The `opengrep` task is automatically included in the `check` task and will scan `src/main` of every module.

## Rules

Custom rules live in `.semgrep.yml`:
- `yukta-spel-standard-evaluation-context` - `new StandardEvaluationContext(...)`, a subclass of it, and the `Expression.getValue` overloads that take no `EvaluationContext`
- `yukta-graalvm-host-access-all` - `HostAccess.ALL`
- `yukta-graalvm-allow-host-class-lookup` - `allowHostClassLookup` with an always-true predicate (`s -> true`, `s -> { return true; }`, `Predicates.alwaysTrue()`)
- `yukta-graalvm-context-allow-all` - `allowAllAccess(true)`, `allowCreateProcess(true)`, `allowNativeAccess(true)`, `allowIO(IOAccess.ALL)`
- `yukta-actuator-expose-all` - actuator exposure of `*` in YAML (`include: "*"`, `["*"]`), `.properties` (`exposure.include=*`) and environment form (`..._EXPOSURE_INCLUDE=*`)

The rules are tested by the files next to `.semgrep.yml` that share its name (`.semgrep.java`, `.semgrep.test.yaml`, `.semgrep.properties`,
`.semgrep.env`): a `ruleid:` comment marks a line that must be flagged, `ok:` one that must not. Run `opengrep test config/opengrep` after changing a rule
(CI does too). The scan itself only reads `src/main`, so these files are never scanned.

Known limits: a predicate hidden behind a variable or a helper method is not recognised, and a `getValue(root)` call is judged by the text of its argument
(an argument whose text contains `context` or `ctx` counts as an evaluation context).

Suppress a deliberate use with `// nosem: <rule-id> - <reason>` on the flagged line.

## Behavior

- **Exit Codes**:
  - `0` - No issues found
  - `1` - Issues found (build fails, `--error`)
  - `>1` - Scan error (build fails)

- **Excluded Paths**:
  - Test code (`*/test/*`)
  - Build directories (`*/build/*`, `*/.gradle/*`)

## Customization

To add custom rules or modify behavior:
1. Edit `.semgrep.yml` to adjust rulesets or configuration
2. Run `./gradlew opengrep` to validate changes
3. Review the generated SARIF report in `build/reports/opengrep/`

## Requirements

OpenGrep CLI must be installed and available in the `PATH`:
```bash
# Recommended: Install OpenGrep (auto-detects platform)
curl -fsSL https://raw.githubusercontent.com/opengrep/opengrep/main/install.sh | bash

# Or via Homebrew (macOS)
brew install opengrep/homebrew-opengrep/opengrep

# Or manual binary download
# Visit: https://github.com/opengrep/opengrep/releases

# Or via Docker
docker run -v /path/to/project:/workspace opengrep/opengrep:latest scan /workspace
```

## CI/CD Integration

The `opengrep` task is automatically run during:
- Local builds via `./gradlew check`
- CI pipelines via GitHub Actions (opengrep is installed automatically)

Review the generated SARIF report for detailed findings and remediation guidance.

## About OpenGrep

OpenGrep is an open-source fork of Semgrep that maintains full rule compatibility while providing additional features like restored fingerprinting and metavariable fields in output. For more information, visit [opengrep.dev](https://opengrep.dev).
