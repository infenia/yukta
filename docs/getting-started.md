# Getting Started

This guide will walk you through setting up Yukta and running your first quality check.

## Prerequisites

- **Java 25** (or Java 21 with a compatible toolchain).
- **Gradle 9.0+**.
- An AI Agent or Client (e.g., **Claude Code**) to interact with the server.

---

## 1. "Hello World" Walkthrough

In this walkthrough, we will start the Yukta server and manually trigger a quality check using `curl`.

### Step 1: Start the Yukta Server

Clone the repository and run the application:

```bash
./gradlew bootRun
```

The server will start on `http://localhost:8080`.

### Step 2: Initialize a Session

Send a configuration request to initialize a session for your project:

```bash
curl -X POST http://localhost:8080/api/config \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "hello-yukta",
    "projectPath": "/path/to/your/project",
    "pluginName": "gradle",
    "pluginConfig": { "gradlePath": "./gradlew" },
    "tasks": ["spotlessCheck", "checkstyleMain"],
    "workflows": []
  }'
```

### Step 3: Log a File Modification

Tell Yukta that a file has been modified:

```bash
curl -X POST http://localhost:8080/api/files \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "hello-yukta",
    "path": "src/main/java/com/example/App.java"
  }'
```

### Step 4: Trigger Quality Checks

Run the tasks and see the results:

```bash
curl -X POST http://localhost:8080/api/workflow/trigger \
  -H "Content-Type: application/json" \
  -d '{ "sessionId": "hello-yukta" }'
```

The server will return a `TaskResponse` containing the combined output of the executed tasks.

---

## 2. Integrating with Claude Code

Yukta is designed to work seamlessly with **Claude Code** (the CLI tool from Anthropic) using lifecycle hooks.

### Installation

Copy the scripts from the `client/scripts` directory to your project's local tools folder (e.g., `.claude/hooks`).

### Configuration

Claude Code can be configured to call Yukta scripts at specific events in its lifecycle. Edit your `config.json` or equivalent for Claude Code:

| Claude Event | Hook Script |
| :--- | :--- |
| **SessionStart** | `python3 init_session.py` |
| **ToolUsage** | `python3 save_file.py` |
| **Stop** | `python3 complete_task.py` |

### How it Works

1. **SessionStart**: Claude starts a session, and `init_session.py` sends the project configuration to Yukta.
2. **ToolUsage**: Whenever Claude uses a tool to modify a file (like `write_file` or `replace_text`), `save_file.py` intercepts the event and logs the file path with Yukta.
3. **Stop**: When Claude finishes its task, `complete_task.py` triggers Yukta to run the configured quality gates. Claude will wait for the results before finalizing.

### Benefits

- **Autonomous Verification**: Yukta ensures that the AI doesn't break your build or violate style rules before it commits the changes.
- **Immediate Feedback**: If a check fails, Yukta provides the exact error log back to the AI, allowing it to self-correct immediately.
- **Traceability**: All interactions and quality check results are logged in standard formats.
