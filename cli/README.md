# SPDX-License-Identifier: Apache-2.0
# SPDX-FileCopyrightText: 2026 Infenia Private Limited

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

The compiled binary will be placed in `cli/build/` and named according to your OS:
- Linux: `yukta`
- macOS: `yukta-darwin` (Intel) or `yukta-darwin-arm64` (Apple Silicon)
- Windows: `yukta.exe`

### Build for All Platforms

To compile binaries for all supported platforms (Linux, macOS, Windows):

```bash
./gradlew :cli:goBuildAll
```

All compiled binaries will be available in `cli/build/`:
- `yukta` (Linux)
- `yukta-darwin` (macOS Intel)
- `yukta-darwin-arm64` (macOS Apple Silicon)
- `yukta.exe` (Windows)

### Development Build

For development with verbose output and debug symbols:

```bash
./gradlew :cli:goBuildDev
```

## Usage

### Session Operations

List all active sessions:

```bash
./yukta session list
```

Get details of a specific session:

```bash
./yukta session get <session-id>
```

Create or update a session using JSON:

```bash
./yukta session apply '{"name":"my-session","config":{}}'
```

Apply a session from a file:

```bash
./yukta session apply --file session.json
```

### Workflow Operations

Get workflow details within a session:

```bash
./yukta session workflow get <session-id> <workflow-id>
```

### Command Structure

All commands follow this pattern:

```
yukta [flags] <resource> <action> [arguments]
```

**Global Flags:**
- `--server-url <url>` - Override the Yukta server URL
- `--timeout <duration>` - Set request timeout (default: 30s)
- `--verbose` - Enable verbose logging

## Configuration

### Environment Variables

The CLI can be configured using environment variables:

- **YUKTA_SERVER_URL** - The base URL of the Yukta server (e.g., `http://localhost:8080`)

Example:

```bash
export YUKTA_SERVER_URL=http://production-yukta.example.com:8080
./yukta session list
```

### Configuration Priority

The CLI resolves the server URL using the following priority (highest to lowest):

1. **Command-line flag** (`--server-url`)
2. **Environment variable** (`YUKTA_SERVER_URL`)
3. **Default value** (`http://localhost:8080`)

### Configuration File (Optional)

For persistent configuration, create a `.yukta/config` file in your home directory:

```yaml
server_url: http://yukta.example.com:8080
timeout: 60s
default_session: my-session
```

## Extensibility

### Adding New Endpoints

The Yukta Go CLI is designed to be extensible. To add support for new endpoints:

1. **Define the API interface** in `pkg/api/client.go`:
   - Add a method to the `Client` interface
   - Implement the HTTP request logic

2. **Create a command handler** in `cmd/commands/`:
   - Create a new file (e.g., `config.go`)
   - Define the command structure and flags using Cobra
   - Implement the command execution logic

3. **Register the command** in `cmd/root.go`:
   - Add the new command to the root command's subcommands

**Example: Adding a Configuration Command**

```go
// pkg/api/client.go
type Client interface {
    // ... existing methods ...
    GetConfig(ctx context.Context, configID string) (*Config, error)
}

// cmd/commands/config.go
var configGetCmd = &cobra.Command{
    Use:   "get <config-id>",
    Short: "Get configuration by ID",
    RunE: func(cmd *cobra.Command, args []string) error {
        // Implementation
    },
}

// cmd/root.go
var configCmd = &cobra.Command{
    Use:   "config",
    Short: "Manage Yukta configurations",
}

func init() {
    configCmd.AddCommand(configGetCmd)
    rootCmd.AddCommand(configCmd)
}
```

### Project Structure

```
cli/
├── main.go                  # Application entry point
├── cmd/
│   ├── root.go             # Root command definition
│   └── commands/
│       ├── session.go      # Session command handlers
│       ├── workflow.go     # Workflow command handlers
│       └── ...
├── pkg/
│   ├── api/
│   │   ├── client.go       # HTTP client interface
│   │   └── types.go        # API data types
│   ├── config/
│   │   └── config.go       # Configuration management
│   └── logger/
│       └── logger.go       # Logging utilities
├── build/                  # Compiled binaries (after build)
└── README.md               # This file
```

### API Client Interface

The CLI uses a modular API client pattern. To extend functionality:

1. Extend the `Client` interface in `pkg/api/client.go`
2. Implement new methods in the HTTP client implementation
3. Use dependency injection to pass the client to command handlers

```go
type Client interface {
    SessionList(ctx context.Context) ([]Session, error)
    SessionGet(ctx context.Context, id string) (*Session, error)
    SessionApply(ctx context.Context, session *Session) (*Session, error)
    WorkflowGet(ctx context.Context, sessionID, workflowID string) (*Workflow, error)
    // Add new methods here
}
```

## API Reference

The Yukta Go CLI communicates with the following Yukta server endpoints:

### Sessions API

- `GET /api/v1/sessions` - List all sessions
- `GET /api/v1/sessions/{id}` - Get a specific session
- `POST /api/v1/sessions` - Create a new session
- `PUT /api/v1/sessions/{id}` - Update a session

### Workflows API

- `GET /api/v1/sessions/{sessionId}/workflows/{workflowId}` - Get workflow details

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
   curl -v http://your-server-url/health
   ```

3. Check if the Yukta server is running and accessible

### Command Not Found

Ensure the binary is in your PATH or use the full path:

```bash
/path/to/yukta session list
```

### Timeout Errors

Increase the timeout value:

```bash
./yukta --timeout 60s session list
```

## Development

### Prerequisites

- Go 1.21 or later
- Gradle 9.0+
- Java 25 (for the build system)

### Running Tests

```bash
./gradlew :cli:goTest
```

### Code Quality

The Go CLI follows standard Go conventions and includes:
- `gofmt` for formatting
- `golint` for linting
- `go vet` for static analysis

These checks are integrated into the Gradle build:

```bash
./gradlew :cli:goCheck
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
