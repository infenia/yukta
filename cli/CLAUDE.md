# Yukta Go CLI CLAUDE.md

## Overview
A lightweight, cross-platform command-line interface (CLI) for Yukta, built in Go.

## Build & Development Commands

### Gradle Commands
```bash
# Build for current platform
yk gradle :cli:goBuild

# Build for all platforms
yk gradle :cli:goBuildAll

# Run Go tests
yk gradle :cli:goTest

# Run all Go checks (format, vet, lint, security, vuln scan) — wired into :cli:check
yk gradle :cli:check
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
- `../docs/contributing/modules/cli.mdx` (module guide); open risks, bugs and review findings: GitHub issues labelled `cli` (`gh issue list -l cli`; risks also carry `risk`); evolution path and quality lenses: `../design/assessments/cli.md`.

## Tech Stack
- **Go 1.26**
- **Cobra** (CLI framework)

## Imports
@.claude/rules/git-workflow.md
