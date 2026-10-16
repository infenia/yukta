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
- Dogfood scripts (`examples/11-dogfood/scripts/`) are thin wrappers over this CLI: if they need a server call the CLI lacks, add it here (`controlbus watch`, `workflow node retry|signal|breakpoint` came from that). `:cli:check` enforces 100% coverage per function. `controlbus watch --quiet` must keep passing `HEAL ` log lines (the self-healing check's fix reports) next to problems.
- `../docs/contributing/modules/cli.mdx` (module guide, published in the docs site); open risks and review findings: `../docs/internal/risks.mdx` and `../docs/internal/improvements.mdx` (section for this module).

## Tech Stack
- **Go 1.26**
- **Cobra** (CLI framework)

## Imports
@.claude/rules/git-workflow.md
