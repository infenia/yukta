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
make e2e       # Run yukta-cli against a real server (YUKTA_E2E_URL, YUKTA_E2E_KEY; release/e2e-server.sh start); see "Testing tiers" in the root CLAUDE.md
make lint      # Run Go linters
make fmt       # Format code
```

**Windows:** the Makefile and the Gradle `go*` tasks need a POSIX shell with make, awk and grep (WSL or Git Bash). The released `.exe` is cross-built and needs none of them.

**Exit codes** (a contract for scripts): 0 ok, 1 other failure, 2 usage, 3 not found (404), 4 server (5xx), 5 timeout, 6 refused by a supervisor (409 `CONTROLLED_BY_SUPERVISOR` or 403 `CONTROL_REJECTED`). `-o` accepts `table` or `json`.

## Project Structure
- `cmd/yukta/main.go`: Entry point.
- `internal/commands/`: Command definitions (Cobra).
- `internal/client/`: HTTP client and types.
- `internal/app/`: Application configuration.
- `internal/notify/` runs the OS notifier (`notify-send`, `osascript`, PowerShell) and the `--exec` command through an injectable `Runner`, never through a shell; `commands/notifications` holds `notifications list|read|dismiss` and `notify`; `client.StreamNotifications` reopens the stream with the last event id forever (`followSSEFrom`).
- `internal/ptyhost` and the hidden command `pty-host --cols C --rows R -- <command>` (not in `--help`; `commands/ptyhost`): the server starts it to run an agent in a pseudo-terminal (`go-pty`, ConPTY on Windows) and talks JSON frames on stdin/stdout (`in`, `resize`, `close` in; `out`, `screen`, `exit` out). Output is gathered to 16 KB or 30 ms, a `screen` frame (virtual screen `charmbracelet/x/vt` behind the `Screen` interface) follows 400 ms of quiet when it changed, `close` or end of stdin sends SIGHUP then SIGKILL after 5 s, and the host exits with the program's code (`ExitError`, nothing on stdout). The vt emulator is never closed (it races its own `Read`); the host ends with the process.
- Go tools through `yk`: this checkout's dev server has no `GOPATH`/`HOME`, so run `yk tool -C cli env GOPATH=$HOME/go GOCACHE=$HOME/.cache/go-build HOME=$HOME go test ./...` (and `make cover-check|lint|sec|vuln|fmt-check` the same way).
- Dogfood scripts (yukta-dogfood) are thin wrappers over this CLI: if they need a server call the CLI lacks, add it here (`controlbus watch`, `workflow node retry|signal|breakpoint` came from that). `:cli:check` enforces 100% coverage per function. `controlbus watch --quiet` must keep passing `HEAL ` log lines (the self-healing check's fix reports) next to problems.
- `../docs/contributing/modules/cli.mdx` (module guide); open risks, bugs and review findings: GitHub issues labelled `cli` (`gh issue list -l cli`; risks also carry `risk`); evolution path and quality lenses: `../design/assessments/cli.md`.

## Tech Stack
- **Go 1.26**
- **Cobra** (CLI framework)

## Imports
@.claude/rules/git-workflow.md
