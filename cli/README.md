# Yukta Go CLI

A lightweight, cross-platform command-line interface (CLI) for Yukta, built in Go. The Yukta Go CLI provides seamless access to Yukta server functionality with minimal dependencies and fast startup times.

## Overview

The Yukta Go CLI is a thin client that communicates with the Yukta server via REST APIs. It enables developers and CI/CD systems to interact with sessions, workflows, and other Yukta resources without requiring a full Java runtime.

**Key Features:**
- Lightweight binary (single executable, no external dependencies)
- Cross-platform support (Linux, macOS, Windows)
- Fast startup and command execution
- Type-safe API interactions
- Environment-based and flag-based configuration

## Building

### Build for Current Platform

To compile the Yukta Go CLI for your current operating system:

```bash
./gradlew :cli:goBuild
```

The compiled binary is placed in `cli/build/` and named `yukta-cli-<os>-<arch>`, with `.exe` on Windows, for example `yukta-cli-linux-amd64` or `yukta-cli-darwin-arm64`.

### Build for All Platforms

To compile binaries for all supported platforms (Linux, macOS, Windows):

```bash
./gradlew :cli:goBuildAll
```

All compiled binaries are available in `cli/build/`:
- `yukta-cli-linux-amd64`, `yukta-cli-linux-arm64`
- `yukta-cli-darwin-amd64` (macOS Intel), `yukta-cli-darwin-arm64` (macOS Apple Silicon)
- `yukta-cli-windows-amd64.exe`

The same builds are available from `cli/` with `make build` (current platform) and `make build-all`.

## Usage

### Session Operations

List all active sessions:

```bash
yukta-cli session list
```

Get details of a specific session:

```bash
yukta-cli session get <session-id>
```

Create or update a session using JSON:

```bash
yukta-cli session apply '{"name":"my-session","config":{}}'
```

Apply a session from a file:

```bash
yukta-cli session apply session.json
```

### Workflow Operations

Start a workflow, optionally passing a trigger payload:

```bash
yukta-cli workflow start <session-id> <workflow-id> --payload '{"message":"hi"}'
yukta-cli workflow start <session-id> <workflow-id> --payload-file payload.json
```

Read-only views: `yukta-cli session summaries`, `yukta-cli session workflow graph|executions <session-id> <workflow-id>`,
and `yukta-cli logs <session-id> <execution-id> --entries` for structured log entries.

Get workflow details within a session:

```bash
yukta-cli session workflow get <session-id> <workflow-id>
```

### Command Structure

All commands follow this pattern:

```
yukta-cli [flags] <resource> <action> [arguments]
```

**Global Flags:**
- `--url <url>` - Override the Yukta server URL (env: `YUKTA_SERVER_URL`)
- `--token <token>` - Bearer token for authenticated servers (env: `YUKTA_TOKEN`)
- `--timeout <duration>` - Timeout for non-streaming requests (default: 30s, `0` disables)
- `-o, --output <table|json>` - Output format

## Configuration

### Environment Variables

The CLI can be configured using environment variables:

- **YUKTA_SERVER_URL** - The base URL of the Yukta server (e.g., `http://localhost:8080`)
- **YUKTA_TOKEN** - The API key to send as `Authorization: Bearer <key>` to a server that has keys set

Example:

```bash
export YUKTA_SERVER_URL=http://production-yukta.example.com:8080
yukta-cli session list
```

### Configuration Priority

The CLI resolves the server URL using the following priority (highest to lowest):

1. **Command-line flag** (`--url`)
2. **Environment variable** (`YUKTA_SERVER_URL`)
3. **Default value** (`http://localhost:8080`)

### No configuration file

The CLI reads no configuration file. The server URL, the token, the timeout and the output format come from the flags and environment variables above.

## Extensibility

### Adding New Endpoints

1. **Add a client method** in `internal/client/` (see "API Client" below).
2. **Add a command** in `internal/commands/<resource>/` with Cobra; a simple "call and print" action uses `commands.Action`.
3. **Register it** with its parent command (for example in `internal/commands/workflow/`), which `internal/commands/root.go` already adds.

### Project Structure

```
cli/
├── cmd/yukta/main.go        # Application entry point
├── internal/
│   ├── app/                 # Wiring: builds the client and the root command
│   ├── client/              # HTTP client and API types
│   ├── commands/            # Cobra commands, one folder per resource
│   ├── output/              # Table and JSON printing
│   └── testutil/            # Fake server and output capture for tests
├── test/                    # Integration test
├── build/                   # Compiled binaries (after build)
├── Makefile                 # Build, test and quality targets (the Gradle tasks call it)
└── README.md                # This file
```

### API Client

Commands take a `*client.Client` (`internal/client`). To add an endpoint:

1. Add a method on `Client` that builds its path with `apiPath("/api/…/%s", id)` (this escapes IDs).
2. Add a command; simple "call and print" actions use `commands.Action`.
3. Test it against `testutil.Server` (an `httptest` server), not a mock.

## API Reference

The CLI calls these server endpoints (paths under `/api`):

- Sessions: `/api/sessions`, `/api/sessions/{id}`, `/api/sessions/summaries`, `/api/sessions/{s}/workflows/{w}` (and `/graph`, `/executions`)
- Starting and listing: `POST /api/workflow/start`, `POST /api/workflow/{s}/{w}/stop`, `GET /api/workflow/{s}/history`
- Run control and status: `/api/executions/{id}` (status, `/pause`, `/resume`, `/stop`, `/restart[/{node}]`, `/status/stream`) and `/api/executions/{id}/nodes/{node}/...` (`pause`, `resume`, `stop`, `skip`, `step`, `retry`, `signal`, `breakpoint`)
- Logs and results: `/api/sessions/{s}/executions/{e}/logs`, `/logs/entries` and `/results`
- Control bus and events: `/api/control/...`, `/api/events/stream`
- Plugins: `/api/plugins`, `/api/plugins/{type}`

For the complete API documentation, refer to the main Yukta documentation.

## Troubleshooting

### Connection Issues

If you encounter connection errors:

1. Verify the server URL is correct:
   ```bash
   echo $YUKTA_SERVER_URL
   ```

2. Test connectivity:
   ```bash
   curl -v http://your-server-url/actuator/health
   ```

3. Check if the Yukta server is running and accessible

### Command Not Found

Ensure the binary is in your PATH or use the full path:

```bash
/path/to/yukta-cli session list
```

### Timeout Errors

Increase the timeout value:

```bash
yukta-cli --timeout 60s session list
```

## Development

### Prerequisites

- Go 1.26 or later
- Gradle 9.0+
- Java 25 (for the build system)

### Running Tests

```bash
./gradlew :cli:goTest
```

### Code Quality

The Go CLI follows standard Go conventions and includes:
- `gofmt` for formatting
- `golangci-lint` (pinned version) for linting, plus `gosec` and `govulncheck`
- `go vet` for static analysis

These checks are integrated into the Gradle build:

```bash
./gradlew :cli:check
```

## License

Yukta Go CLI is licensed under the Apache License 2.0. See the LICENSE file in the project root for details.

## Contributing

Contributions are welcome! Please ensure:

1. Code follows Go conventions and passes all quality checks
2. Tests are included for new functionality
3. API changes are documented
4. Commit messages follow the Conventional Commits format

For more information, see the main Yukta project contribution guidelines.
