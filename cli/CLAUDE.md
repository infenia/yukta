# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

# Yukta Go CLI CLAUDE.md

## Overview
A lightweight, cross-platform command-line interface (CLI) for Yukta, built in Go.

## Build & Development Commands

### Gradle Commands
```bash
# Build for current platform
./gradlew :cli:goBuild

# Build for all platforms
./gradlew :cli:goBuildAll

# Run Go tests
./gradlew :cli:goTest

# Run all Go checks (format, vet, lint, security, vuln scan) — wired into :cli:check
./gradlew :cli:check
```

### Makefile Commands (from cli/ folder)
```bash
make build     # Build for current OS/arch
make build-all # Build for all platforms
make test      # Run Go tests
make lint      # Run Go linters
make fmt       # Format code
```

## Project Structure
- `cmd/yukta/main.go`: Entry point.
- `internal/commands/`: Command definitions (Cobra).
- `internal/client/`: HTTP client and types.
- `internal/app/`: Application configuration.

## Tech Stack
- **Go 1.26**
- **Cobra** (CLI framework)

## Imports
@.claude/rules/git-workflow.md
