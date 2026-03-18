# Yukta Claude Code Hook Simulator 🛠️

This simulator allows you to test Yukta's client integration by mimicking how Claude Code invokes hooks. It reads a scenario defined in a JSON file and executes the corresponding hook scripts in sequence, passing the specified data via `stdin`.

## 📂 Structure

- `simulator.py`: The main simulation engine for executing hook scenarios
- `scenarios/`: Directory containing JSON scenario definitions
  - `happy_path.json`: Complete workflow with session init, tool usage, and shutdown
  - `process_executor.json`: ProcessExecutor plugin example with external command execution
  - `multi_file.json`: Multi-file processing workflow

## 🚀 Usage

### Run a scenario
```bash
python3 simulator.py scenarios/happy_path.json
```

### Overriding Server Host and Port
By default, the scripts use `localhost:8080`. You can override these using command-line arguments:
```bash
python3 simulator.py scenarios/happy_path.json --host 192.168.1.10 --port 9090
```

Or via environment variables:
```bash
YUKTA_HOST=myserver YUKTA_PORT=8888 python3 simulator.py scenarios/happy_path.json
```

## 📝 Scenario Format

A scenario is a JSON file with a `name`, optional `description`, and a list of `steps`. Each step specifies a `hook` type and the `data` payload to be sent to the hook script.

### Supported Hook Types
The simulator supports all Claude Code hook events:
- `SessionStart`: Called when a session begins (before any tools are executed)
- `PostToolUse`: Called after a tool (Edit, Write, Bash, etc.) completes successfully
- `PostToolUseFailure`: Called when a tool execution fails (optional)
- `Stop`: Called when the session ends or user stops the interaction
- `PermissionRequest`: Called when a tool requires user permission (optional)
- `UserPromptSubmit`: Called when the user submits input (optional)

### Example Scenario
```json
{
  "name": "Quality Check Workflow",
  "description": "Demonstrates a complete workflow with session management",
  "steps": [
    {
      "hook": "SessionStart",
      "data": {
        "hook_event_name": "SessionStart",
        "session_id": "my-session",
        "cwd": "/path/to/project",
        "source": "claude-code",
        "model": "claude-opus-4-6"
      }
    },
    {
      "hook": "PostToolUse",
      "data": {
        "hook_event_name": "PostToolUse",
        "session_id": "my-session",
        "cwd": "/path/to/project",
        "tool_name": "Bash",
        "tool_input": {
          "command": "./gradlew check",
          "description": "Run quality checks"
        }
      }
    },
    {
      "hook": "Stop",
      "data": {
        "hook_event_name": "Stop",
        "session_id": "my-session",
        "last_assistant_message": "Quality checks completed successfully."
      }
    }
  ]
}
```

## 🔌 Supported Plugins

Yukta supports the following plugin types for workflow execution:

### Trigger Plugins
- **API_TRIGGER**: Initiates workflow via REST API
- **CONSTANT_SOURCE**: Provides constant values for workflow triggering

### Processor Plugins
- **PROCESS_EXECUTOR**: Execute external commands/scripts
  - Supports: Linux, macOS, Windows (with auto shell wrapping)
  - Configuration: `command` (List<String>), `streamOutput` (Boolean), `environment` (Map)
- **MAPPER**: Transform data using Handlebars templates
- **SCRIPTING**: Execute shell scripts (bash/batch)

### Terminal Plugins
- **CONSOLE_TERMINAL**: Log execution results to console
- **FILE_LOGGER**: Write results to files

## 💡 How It Works

The simulator:
1. Reads the scenario JSON file
2. For each step:
   - Identifies the hook type (e.g., SessionStart, PostToolUse, Stop)
   - Pipes the `data` object as JSON into the corresponding hook script's stdin
   - Waits for the script to complete
3. Stops immediately if any step fails (non-zero exit code)

This mimics the behavior of Claude Code's hook system, allowing you to test your Yukta integration locally before deploying to production.

## 📋 Example Scenarios

### Happy Path (happy_path.json)
A complete workflow demonstrating:
- Session initialization
- Multiple tool executions (Bash, Edit)
- Graceful shutdown

### Process Executor (process_executor.json)
Shows how to use the ProcessExecutor plugin to run quality checks:
- Runs Gradle quality gates
- Demonstrates streaming output
- Proper error handling

## 🔧 Client Scripts

The simulator uses scripts from `client/scripts/`:
- `init_session.py`: Initialize a Yukta session with workflow configuration
- `trigger_workflow.py`: Trigger workflow execution and monitor status via SSE

These scripts are called by the simulator with hook data piped via stdin.
