# REST API Reference

Yukta exposes a comprehensive REST API for workflow management, execution, and monitoring.

## Base URL

```
http://localhost:8080
```

## Interactive Documentation

- **Swagger UI**: http://localhost:8080/swagger-ui.html
- **OpenAPI Spec**: http://localhost:8080/v3/api-docs

---

## Unified Response Format

All endpoints return a consistent `ApiResponse<T>` envelope:

### Success Response

```json
{
  "timestamp": "2026-03-20T10:00:00Z",
  "status": 200,
  "message": "Operation successful",
  "data": { }
}
```

### Error Response

```json
{
  "timestamp": "2026-03-20T10:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/config",
  "errors": [
    {
      "field": "sessionId",
      "message": "must not be blank"
    }
  ]
}
```

---

## 1. Session Configuration

### POST /api/config

Initialize or update a session with workflows.

**Request**:
```json
{
  "sessionId": "@SessionId (required, no ../ allowed)",
  "description": "@NotBlank, max 256 (required)",
  "initiator": "@NotBlank (required)",
  "tags": {
    "key1": "value1"
  },
  "projectPath": "@ProjectPath (optional)",
  "workflows": {
    "quality-check": {
      "description": "@NotBlank, max 256 (required)",
      "nodes": [
        {
          "nodeId": "format",
          "type": "PROCESS_EXECUTOR",
          "config": {
            "command": ["./gradlew", "spotlessCheck"],
            "timeout": 3600,
            "workingDir": "/path/to/project",
            "env": { "JAVA_OPTS": "-Xmx2g" }
          }
        }
      ],
      "edges": [
        {
          "source": "format",
          "target": "lint",
          "sourcePort": "default"
        }
      ]
    }
  }
}
```

**Response (200 OK)**:
```json
{
  "timestamp": "2026-03-20T10:00:00Z",
  "status": 200,
  "message": "Session configured successfully",
  "data": {
    "sessionId": "quality-check",
    "description": "Code quality workflow",
    "workflowCount": 1
  }
}
```

**Validation**:
- `sessionId`: Pattern `^(?!.*\.\.)[ ^/\\]*$` (blocks `../`, `/`, `\`)
- `description`: Non-blank, max 256 characters
- `initiator`: Non-blank
- `projectPath`: Size-bounded, no path traversal
- Workflows: Map of `WorkflowDefinition` records

---

## 2. Workflow Execution

### POST /api/workflow/trigger

Trigger a workflow execution.

**Request**:
```json
{
  "sessionId": "quality-check",
  "workflowId": "quality-check",
  "payload": { }
}
```

**Response (202 Accepted)**:
```json
{
  "timestamp": "2026-03-20T10:00:00Z",
  "status": 202,
  "message": "Workflow triggered successfully",
  "data": {
    "executionId": "550e8400-e29b-41d4-a716-446655440000"
  }
}
```

---

### GET /api/workflow/{sessionId}/status/{executionId}

Poll workflow status.

**Response (200 OK)**:
```json
{
  "timestamp": "2026-03-20T10:00:00Z",
  "status": 200,
  "message": "Execution status retrieved",
  "data": {
    "executionId": "550e8400-e29b-41d4-a716-446655440000",
    "sessionId": "quality-check",
    "workflowId": "quality-check",
    "status": "RUNNING|SUCCESS|FAILURE|ERROR",
    "tasks": [
      {
        "nodeId": "format",
        "module": "PROCESS_EXECUTOR",
        "status": "SUCCESS",
        "startTime": "2026-03-20T10:00:00Z",
        "endTime": "2026-03-20T10:00:05Z",
        "metadata": {
          "duration": 5000,
          "exitCode": 0
        }
      }
    ],
    "startTime": "2026-03-20T10:00:00Z",
    "endTime": "2026-03-20T10:00:10Z"
  }
}
```

**Task Status Values**: `PENDING`, `RUNNING`, `SUCCESS`, `FAILURE`, `ERROR`

---

### GET /api/workflow/{sessionId}/status/{executionId}/stream

Stream execution progress via Server-Sent Events (SSE).

**Response (200 OK, text/event-stream)**:
```
data: {"executionId":"550e8400-e29b-41d4-a716-446655440000","status":"RUNNING","tasks":[...]}

data: {"executionId":"550e8400-e29b-41d4-a716-446655440000","status":"SUCCESS","tasks":[...]}
```

---

### GET /api/workflow/{sessionId}/history

List execution history for a workflow.

**Response (200 OK)**:
```json
{
  "timestamp": "2026-03-20T10:00:00Z",
  "status": 200,
  "message": "Execution history retrieved",
  "data": [
    {
      "executionId": "550e8400-e29b-41d4-a716-446655440000",
      "workflowId": "quality-check",
      "status": "SUCCESS",
      "startTime": "2026-03-20T10:00:00Z",
      "endTime": "2026-03-20T10:00:10Z",
      "taskCount": 3
    }
  ]
}
```

---

## 3. Session Management

### GET /api/sessions/{sessionId}

Get session details.

**Response (200 OK)**:
```json
{
  "timestamp": "2026-03-20T10:00:00Z",
  "status": 200,
  "message": "Session details retrieved",
  "data": {
    "sessionId": "quality-check",
    "description": "Code quality workflow",
    "initiator": "claude-code",
    "tags": { "team": "backend" },
    "projectPath": "/home/user/project",
    "createdAt": "2026-03-20T10:00:00Z",
    "workflowIds": ["quality-check", "deploy"]
  }
}
```

---

### GET /api/sessions/{sessionId}/workflows/{workflowId}

Get workflow definition.

**Response (200 OK)**:
```json
{
  "timestamp": "2026-03-20T10:00:00Z",
  "status": 200,
  "message": "Workflow definition retrieved",
  "data": {
    "workflowId": "quality-check",
    "description": "Code quality checks",
    "nodes": [ ... ],
    "edges": [ ... ]
  }
}
```

---

## 4. Logs

### GET /api/logs/{sessionId}

List log files for a session.

**Response (200 OK)**:
```json
{
  "timestamp": "2026-03-20T10:00:00Z",
  "status": 200,
  "message": "Log files listed",
  "data": [
    "550e8400-e29b-41d4-a716-446655440000.log",
    "summary.log"
  ]
}
```

---

### GET /api/logs/{sessionId}/{filename}

Get parsed log file content (JSONL parsed into objects).

**Response (200 OK)**:
```json
{
  "timestamp": "2026-03-20T10:00:00Z",
  "status": 200,
  "message": "Log content retrieved",
  "data": [
    {
      "nodeId": "format",
      "timestamp": "2026-03-20T10:00:00Z",
      "message": "Spotless check passed"
    }
  ]
}
```

---

### GET /api/logs/{sessionId}/{filename}/raw

Get raw log file content (unprocessed string).

**Response (200 OK)**:
```
{"nodeId":"format","timestamp":"2026-03-20T10:00:00Z","message":"Spotless check passed"}
{"nodeId":"lint","timestamp":"2026-03-20T10:00:05Z","message":"Checkstyle passed"}
```

**Note**: Filename is validated by `@FileName` to block OS-illegal characters and `../` traversal.

---

## 5. Plugin Discovery

### GET /api/plugins

List all available plugins.

**Response (200 OK)**:
```json
{
  "timestamp": "2026-03-20T10:00:00Z",
  "status": 200,
  "message": "Plugins listed",
  "data": [
    {
      "type": "PROCESS_EXECUTOR",
      "category": "PROCESSOR",
      "description": "Execute OS commands"
    },
    {
      "type": "BRANCH",
      "category": "PROCESSOR",
      "description": "SpEL-based conditional routing"
    }
  ]
}
```

---

### GET /api/plugins/{type}

Get detailed plugin information.

**Response (200 OK)**:
```json
{
  "timestamp": "2026-03-20T10:00:00Z",
  "status": 200,
  "message": "Plugin details retrieved",
  "data": {
    "type": "PROCESS_EXECUTOR",
    "category": "PROCESSOR",
    "description": "Execute OS commands with timeout, env, streaming",
    "usagePattern": "{ \"type\": \"PROCESS_EXECUTOR\", \"config\": { \"command\": [...], \"timeout\": 3600 } }",
    "outputPorts": ["default"],
    "requiredFields": ["command"],
    "optionalFields": ["timeout", "workingDir", "env"]
  }
}
```

---

## 6. Control Bus

### GET /api/control/nodes

Get active node IDs (currently executing).

**Response (200 OK)**:
```json
{
  "timestamp": "2026-03-20T10:00:00Z",
  "status": 200,
  "message": "Active nodes retrieved",
  "data": ["format", "lint", "test"]
}
```

---

### GET /api/control/nodes/{nodeId}/heartbeat

Get last heartbeat for a node.

**Response (200 OK)**:
```json
{
  "timestamp": "2026-03-20T10:00:00Z",
  "status": 200,
  "message": "Heartbeat retrieved",
  "data": {
    "nodeId": "format",
    "timestamp": "2026-03-20T10:00:10Z",
    "status": "RUNNING",
    "messagesProcessed": 1,
    "messagesErrored": 0
  }
}
```

---

### POST /api/control/nodes/{nodeId}/command

Send admin command to a node (e.g., pause, resume, cancel).

**Request**:
```json
{
  "command": "PAUSE|RESUME|CANCEL",
  "params": { }
}
```

**Response (200 OK)**:
```json
{
  "timestamp": "2026-03-20T10:00:00Z",
  "status": 200,
  "message": "Command sent",
  "data": {
    "nodeId": "format",
    "command": "PAUSE",
    "acked": true
  }
}
```

---

### GET /api/control/stream

Stream control signals via Server-Sent Events (SSE).

**Response (200 OK, text/event-stream)**:
```
data: {"type":"HEARTBEAT","nodeId":"format","timestamp":"2026-03-20T10:00:00Z"}

data: {"type":"HEARTBEAT","nodeId":"lint","timestamp":"2026-03-20T10:00:01Z"}

data: {"type":"STATISTICS","nodeId":"format","messagesProcessed":10}
```

**Signal Types**: `HEARTBEAT`, `STATISTICS`, `CONFIGURATION`, `ERROR`

---

## HTTP Status Codes

| Code | Meaning |
|------|---------|
| 200 | Success |
| 202 | Accepted (async operation queued) |
| 400 | Validation error |
| 404 | Not found (session, workflow, execution) |
| 500 | Internal server error |

---

## Field Validation Rules

| Field | Rule | Example |
|-------|------|---------|
| `sessionId` | Pattern `^(?!.*\.\.)[ ^/\\]*$` | "my-session" |
| `workflowId` | Same as sessionId | "quality-check" |
| `description` | Non-blank, max 256 | "Code quality workflow" |
| `initiator` | Non-blank | "claude-code" |
| `projectPath` | Size-bounded | "/home/user/project" |
| `command` (PROCESS_EXECUTOR) | List of strings | `["./gradlew", "build"]` |
| `timeout` | Positive integer (seconds) | 3600 |

---

## Error Handling

All validation errors are returned with field-level details:

```json
{
  "timestamp": "2026-03-20T10:00:00Z",
  "status": 400,
  "error": "Bad Request",
  "message": "Validation failed",
  "path": "/api/config",
  "errors": [
    {
      "field": "sessionId",
      "message": "must not be blank"
    },
    {
      "field": "projectPath",
      "message": "path traversal not allowed"
    }
  ]
}
```

---

## Rate Limiting

Currently no rate limiting is enforced. Session-based concurrency control:
- Same workflow (sessionId + workflowId) runs serially
- Different workflows run concurrently
