# Build Logic CLAUDE.md

## Overview
The `build-logic` directory defines reusable convention plugins for the Yukta project.

## Convention Plugins
1. **java-conventions**: Java toolchain (25), Lombok, repositories, JUnit 5.
2. **quality-conventions**: Spotless, Checkstyle, PMD, SpotBugs, Semgrep, license headers.
3. **jacoco-conventions**: Code coverage tracking.
4. **library-conventions**: Base conventions for non-Spring library modules.
5. **spring-conventions**: Base conventions for Spring Boot modules.
6. **node-conventions**: pnpm/Node.js toolchain wiring for frontend modules (e.g. `ui`).

All modules apply these plugins via `plugins { id 'com.infenia.yukta.xxx-conventions' }`.

## Quality Configuration
- **Checkstyle**: `config/checkstyle/checkstyle.xml`
- **PMD**: `config/pmd/ruleset.xml`
- **Semgrep**: `config/semgrep/.semgrep.yml` (runs in `check` only if the `semgrep` CLI is installed)
- **License**: `config/license/`

## Imports
@.claude/rules/coding-standards.md
@.claude/rules/git-workflow.md
