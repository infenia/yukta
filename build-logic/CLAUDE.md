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
- **OpenGrep**: runs with `--config=auto` in `check` only if the `opengrep` CLI is installed; findings don't fail the build and `config/opengrep/` doesn't exist yet (see `docs/build-logic-improvements.md` L1)
- **License**: `config/license/`

## Docs
- `docs/build-logic-module-guide.html` (interactive guide) and `docs/build-logic-improvements.md` (review findings).

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md
