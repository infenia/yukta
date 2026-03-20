# Getting Started

This guide walks you through setting up Yukta and running your first workflow.

## Prerequisites

- **Java 25** (or Java 21 with compatible toolchain)
- **Gradle 9.0+**
- An AI Agent or HTTP client (e.g., `curl`, Claude Code) to interact with the server
- **Optional**: Python 3.7+ for client scripts

---

## 1. Start the Yukta Server

Clone the repository and run:

```bash
./gradlew bootRun
```

The server starts on `http://localhost:8080`.

**Check health**:
```bash
curl -s http://localhost:8080/swagger-ui.html
```

---

## 2. Initialize a Session

Create a session with workflows:

```bash
curl -X POST http://localhost:8080/api/config \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "hello-yukta",
    "description": "My first workflow",
    "initiator": "me",
    "projectPath": "/path/to/your/project",
    "workflows": {
      "quality-check": {
        "description": "Format, lint, test",
        "nodes": [
          {
            "nodeId": "format",
            "type": "PROCESS_EXECUTOR",
            "config": {
              "command": ["./gradlew", "spotlessCheck"],
              "timeout": 3600
            }
          },
          {
            "nodeId": "lint",
            "type": "PROCESS_EXECUTOR",
            "config": {
              "command": ["./gradlew", "checkstyleMain"],
              "timeout": 3600
            }
          },
          {
            "nodeId": "test",
            "type": "PROCESS_EXECUTOR",
            "config": {
              "command": ["./gradlew", "test"],
              "timeout": 3600
            }
          }
        ],
        "edges": [
          { "source": "format", "target": "lint" },
          { "source": "lint", "target": "test" }
        ]
      }
    }
  }'
```

**Expected response (200 OK)**:
```json
{
  "timestamp": "2026-03-20T10:00:00Z",
  "status": 200,
  "message": "Session configured successfully",
  "data": { "sessionId": "hello-yukta", "workflowCount": 1 }
}
```

---

## 3. Trigger a Workflow

Run the workflow:

```bash
curl -X POST http://localhost:8080/api/workflow/trigger \
  -H "Content-Type: application/json" \
  -d '{
    "sessionId": "hello-yukta",
    "workflowId": "quality-check",
    "payload": {}
  }'
```

**Expected response (202 Accepted)**:
```json
{
  "timestamp": "2026-03-20T10:00:00Z",
  "status": 202,
  "message": "Workflow triggered successfully",
  "data": { "executionId": "550e8400-e29b-41d4-a716-446655440000" }
}
```

Save the `executionId` for the next step.

---

## 4. Check Execution Status

Poll the status:

```bash
curl -s http://localhost:8080/api/workflow/hello-yukta/status/550e8400-e29b-41d4-a716-446655440000 | jq
```

**Response (200 OK)**:
```json
{
  "timestamp": "2026-03-20T10:00:00Z",
  "status": 200,
  "message": "Execution status retrieved",
  "data": {
    "executionId": "550e8400-e29b-41d4-a716-446655440000",
    "sessionId": "hello-yukta",
    "workflowId": "quality-check",
    "status": "SUCCESS",
    "tasks": [
      {
        "nodeId": "format",
        "module": "PROCESS_EXECUTOR",
        "status": "SUCCESS",
        "startTime": "2026-03-20T10:00:00Z",
        "endTime": "2026-03-20T10:00:05Z"
      },
      {
        "nodeId": "lint",
        "module": "PROCESS_EXECUTOR",
        "status": "SUCCESS",
        "startTime": "2026-03-20T10:00:05Z",
        "endTime": "2026-03-20T10:00:10Z"
      },
      {
        "nodeId": "test",
        "module": "PROCESS_EXECUTOR",
        "status": "SUCCESS",
        "startTime": "2026-03-20T10:00:10Z",
        "endTime": "2026-03-20T10:00:15Z"
      }
    ],
    "startTime": "2026-03-20T10:00:00Z",
    "endTime": "2026-03-20T10:00:15Z"
  }
}
```

---

## 5. Stream Status (Live Updates)

Instead of polling, stream status via Server-Sent Events:

```bash
curl -s http://localhost:8080/api/workflow/hello-yukta/status/550e8400-e29b-41d4-a716-446655440000/stream
```

This streams updates every time a task completes:

```
data: {"executionId":"550e8400-e29b-41d4-a716-446655440000","status":"RUNNING","tasks":[{"nodeId":"format","status":"RUNNING"}]}

data: {"executionId":"550e8400-e29b-41d4-a716-446655440000","status":"RUNNING","tasks":[{"nodeId":"format","status":"SUCCESS"},{"nodeId":"lint","status":"RUNNING"}]}

data: {"executionId":"550e8400-e29b-41d4-a716-446655440000","status":"SUCCESS","tasks":[...]}
```

---

## Integrating with Claude Code

Yukta is designed to work with **Claude Code** (Anthropic's CLI tool) using lifecycle hooks.

### Installation

Copy scripts from `client/scripts/` to your project:

```bash
cp client/scripts/*.py /path/to/your/project/.claude/hooks/
```

### Configuration

Add hook configuration to your project's Claude Code settings (`.claude/settings.json` or similar):

```json
{
  "hooks": {
    "SessionStart": "python3 .claude/hooks/init_session.py",
    "Stop": "python3 .claude/hooks/trigger_workflow.py"
  }
}
```

### Environment Variables

Both scripts read:
- `YUKTA_HOST` (default: `localhost`)
- `YUKTA_PORT` (default: `8080`)

Set them in your environment or `.env` file:

```bash
export YUKTA_HOST=localhost
export YUKTA_PORT=8080
```

### Hook Scripts

**SessionStart** (`init_session.py`):
- Called when Claude Code starts a session
- Reads project root and creates a session in Yukta
- Defines workflows (quality checks, linting, testing, etc.)

**Stop** (`trigger_workflow.py`):
- Called when Claude Code finishes a task
- Triggers the workflow in Yukta
- Polls for status and streams results back to Claude Code
- If workflow fails, Claude Code receives error output and can auto-fix

### How It Works

1. **SessionStart**: Claude Code initializes, `init_session.py` runs:
   ```
   init_session.py
     → Read .clauderc or environment for project details
     → POST /api/config (create session + workflows)
   ```

2. **Stop**: Claude Code finishes task, `trigger_workflow.py` runs:
   ```
   trigger_workflow.py
     → POST /api/workflow/trigger (start workflow)
     → GET /api/workflow/{sessionId}/status/{executionId}/stream (monitor)
     → On SUCCESS: Return 0 (Claude Code commits)
     → On FAILURE: Return error output (Claude Code auto-fixes, re-runs Stop hook)
   ```

### Example: Auto-Fixing Workflow

```
Claude Code Task: "Fix the failing tests"
   │
   ├─ SessionStart hook
   │  └─ init_session.py → creates session with test workflow
   │
   ├─ Claude modifies code (writes files)
   │
   ├─ Stop hook
   │  └─ trigger_workflow.py → runs tests
   │     ├─ If SUCCESS → done ✓
   │     └─ If FAILURE → return error output to Claude
   │
   └─ Claude reads error output and retries (Stop hook again)
```

---

## 4. Integrating with REST Clients

Any HTTP client can use Yukta's REST API:

**Python** (`requests` library):
```python
import requests

# Initialize session
response = requests.post(
    "http://localhost:8080/api/config",
    json={
        "sessionId": "my-session",
        "description": "My workflow",
        "initiator": "my-script",
        "workflows": { ... }
    }
)
assert response.status_code == 200

# Trigger workflow
response = requests.post(
    "http://localhost:8080/api/workflow/trigger",
    json={
        "sessionId": "my-session",
        "workflowId": "quality-check",
        "payload": {}
    }
)
execution_id = response.json()["data"]["executionId"]

# Check status
response = requests.get(
    f"http://localhost:8080/api/workflow/my-session/status/{execution_id}"
)
status = response.json()["data"]["status"]
```

**JavaScript** (`fetch`):
```javascript
// Initialize session
const configResponse = await fetch("http://localhost:8080/api/config", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    sessionId: "my-session",
    description: "My workflow",
    initiator: "my-app",
    workflows: { ... }
  })
});

// Trigger workflow
const triggerResponse = await fetch("http://localhost:8080/api/workflow/trigger", {
  method: "POST",
  headers: { "Content-Type": "application/json" },
  body: JSON.stringify({
    sessionId: "my-session",
    workflowId: "quality-check",
    payload: {}
  })
});
const { executionId } = await triggerResponse.json();

// Stream status
const eventSource = new EventSource(
  `http://localhost:8080/api/workflow/my-session/status/${executionId}/stream`
);
eventSource.onmessage = (event) => {
  const data = JSON.parse(event.data);
  console.log("Status:", data.status);
};
```

---

## Next Steps

- **[Architecture & Design](docs/architecture.md)** — Understand how Yukta works internally
- **[API Reference](docs/api-reference.md)** — All endpoints and response formats
- **[Plugin Development](docs/plugin-development.md)** — Build custom plugins
- **[Development Setup](docs/development-setup.md)** — Contribute to Yukta

---

## Troubleshooting

| Problem | Solution |
|---------|----------|
| Server won't start | Check Java version: `java -version` (need Java 25+) |
| `sessionId` rejected | Check pattern: no `../`, `/`, or `\` characters |
| Workflow fails silently | Check logs: `GET /api/logs/{sessionId}` |
| Plugin not found | Verify plugin type string matches `getType()` in implementation |
| Timeout errors | Increase `timeout` in PROCESS_EXECUTOR config |

---

## Quick Reference

| Operation | Endpoint | Method |
|-----------|----------|--------|
| Create session | `/api/config` | POST |
| Trigger workflow | `/api/workflow/trigger` | POST |
| Check status | `/api/workflow/{sessionId}/status/{executionId}` | GET |
| Stream status | `/api/workflow/{sessionId}/status/{executionId}/stream` | GET |
| List plugins | `/api/plugins` | GET |
| Get logs | `/api/logs/{sessionId}` | GET |

Happy orchestrating! 🚀
