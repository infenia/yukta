# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Build Logic CLAUDE.md

## Overview
The `build-logic` directory defines reusable convention plugins for the Yukta project.

## Convention Plugins
1. **java-conventions**: Java toolchain (25), Lombok, repositories, JUnit 5.
2. **quality-conventions**: Spotless, Checkstyle, PMD, SpotBugs, OpenGrep, license headers.
3. **jacoco-conventions**: Code coverage tracking.
4. **library-conventions**: `java-library` + spring-conventions (used by every library module).
5. **spring-conventions**: Base conventions for Spring Boot modules.
6. **node-conventions**: pnpm/Node.js toolchain wiring for frontend modules (e.g. `ui`).

All modules apply these plugins via `plugins { id 'com.infenia.yukta.xxx-conventions' }`.

## Quality Configuration
- **Checkstyle**: `config/checkstyle/checkstyle.xml`
- **PMD**: `config/pmd/ruleset.xml`
- **OpenGrep**: runs in `check` only if the `opengrep` CLI is installed, with the local ruleset `config/opengrep/.semgrep.yml`; any finding fails the build (`--error`)
- **License**: `config/license/`

## Docs
- `docs/build-logic-module-guide.html` (interactive guide) and `docs/build-logic-improvements.md` (review findings).

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md

## Coverage policy
Defaults are LINE 90% / BRANCH 85%. Lower a class with `coverageConfig { exception(pattern, reason, mapOf("BRANCH" to 0.7)) }`; below 0.80 a reason is required. Stale patterns and disabled `jacocoTestCoverageVerification` fail the build. Trivy is not part of `check` (CI runs it; `./gradlew trivy` on demand).
