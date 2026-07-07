# Yukta API Testing with Bruno

Comprehensive Bruno collection for testing all Yukta API endpoints. 20 requests organized across 8 functional areas.

## Quick Start (3 Steps)

1. **Download & Open Bruno** from [usebruno.com](https://www.usebruno.com/)
2. **Import Collection**: Drag the `/bruno` folder into Bruno
3. **Select Environment**: Click Environment dropdown (top-right) → Select `local`
4. **Start Testing**: Run `SessionConfigController/Apply Session Configuration.bru`

That's it! All variables auto-load from the environment file.

## Environment Configuration

### Auto-Configuration (No Manual Setup Needed)

Two pre-configured environments are included:

**Local Development** (`environments/local.bru`)
```
base_url   = http://localhost:8080
sessionId  = session-001
workflowId = workflow-001
executionId = exec-001
pluginType = gradle
```

**Production** (`environments/production.bru`)
```
base_url   = https://api.yukta.production.com
sessionId  = session-001
workflowId = workflow-001
executionId = exec-001
pluginType = gradle
```

### How to Select Environment in Bruno

1. Look for **Environment** dropdown in top-right corner
2. Click the dropdown arrow
3. Select `local` (for development) or `production`
4. Variables automatically populate - ready to test!

### Manual Environment Configuration (If Needed)

To create or edit an environment:

1. Click **Environment dropdown** (top-right)
2. Select **Manage Environments**
3. Click **Add New Environment** or **Edit** existing
4. Set these variables:
   - `base_url` - Your API URL
   - `sessionId` - Session identifier (e.g., session-001)
   - `workflowId` - Workflow identifier (e.g., workflow-001)
   - `executionId` - Execution identifier (auto-set by requests, can be empty initially)
   - `pluginType` - Plugin type (e.g., gradle)
   - `logFilename` - Leave empty (auto-set by List Logs request)
   - `nodeId` - Leave empty (auto-set by requests)
5. Click **Save** and select from Environment dropdown

### Variable Reference

| Variable | Purpose | Auto-Set? | Example |
|----------|---------|-----------|---------|
| `base_url` | API base URL | No | http://localhost:8080 |
| `sessionId` | Session identifier | No | session-001 |
| `workflowId` | Workflow identifier | No | workflow-001 |
| `executionId` | Execution identifier | Yes* | exec-12345 |
| `pluginType` | Plugin type | No | gradle |
| `logFilename` | Log filename | Yes** | session-001-2026-06-13.log |
| `nodeId` | Node identifier | Yes*** | node-1 |

*Auto-set by: Trigger Workflow request  
**Auto-set by: List Logs request  
***Auto-set by: Various requests

### Verify Current Variables

In Bruno, click the **Variables** tab at the bottom to see all active variables and their current values.

## Collection Structure

Bruno collections are now organized to match the backend controller structure for easier maintenance and API tracking:

```
bruno/
├── environments/
│   ├── local.bru                         (pre-configured for localhost:8080)
│   └── production.bru                    (pre-configured for production)
├── SessionConfigController/              (4 requests)
│   ├── List Sessions.bru
│   ├── Get Session Details.bru
│   ├── Get Workflow.bru
│   └── Apply Session Configuration.bru
├── WorkflowController/                   (4 requests)
│   ├── Start Workflow.bru
│   ├── Stop Workflow.bru
│   ├── Get Workflow Status.bru
│   ├── Stream Workflow Status.bru
│   └── Get Workflow History.bru
├── LogManagementController/              (3 requests)
│   ├── List Logs.bru
│   ├── Get Log Content.bru
│   └── Get Raw Log Content.bru
├── ControlBusController/                 (4 requests)
│   ├── Get All Active Nodes.bru
│   ├── Get Active Nodes in Workflow.bru
│   ├── Get Node Heartbeat.bru
│   └── Send Command to Node.bru
├── PluginController/                     (2 requests)
│   ├── List Plugins.bru
│   └── Get Plugin Details.bru
├── Streaming/                            (2 requests)
│   ├── Stream Execution Progress.bru
│   └── Stream Execution Logs.bru
├── bruno.json                            (collection config)
└── README.md                             (this file)
```

### Why This Structure?

- **Controller Mapping**: Each folder corresponds directly to a backend REST controller
- **Easy Navigation**: Find API requests by controller name, not by functionality
- **Maintenance**: Track which controller owns which requests
- **Scalability**: As new controllers are added, new folders follow the same pattern

## API Endpoints Summary

### SessionConfigController (`/api/sessions`)

| Request | Method | Folder | Purpose |
|---------|--------|--------|---------|
| List Sessions | GET | SessionConfigController | List all available sessions |
| Get Session Details | GET | SessionConfigController | Retrieve session config and available workflows |
| Get Workflow | GET | SessionConfigController | Get workflow definition with nodes and edges |
| Apply Session Configuration | POST | SessionConfigController | Initialize or update session configuration |

### WorkflowController (`/api/workflow`)

| Request | Method | Folder | Purpose |
|---------|--------|--------|---------|
| Start Workflow | POST | WorkflowController | Start workflow execution (returns executionId) |
| Stop Workflow | POST | WorkflowController | Stop active workflow execution |
| Get Workflow Status | GET | WorkflowController | Get execution status and progress |
| Stream Workflow Status | GET | WorkflowController | Real-time workflow status updates (SSE) |
| Get Workflow History | GET | WorkflowController | Get execution history for a session |

### LogManagementController (`/api/logs`)

| Request | Method | Folder | Purpose |
|---------|--------|--------|---------|
| List Logs | GET | LogManagementController | List available log files for a session |
| Get Log Content | GET | LogManagementController | Get formatted log content |
| Get Raw Log Content | GET | LogManagementController | Get raw text log content |

### ControlBusController (`/api/control`)

| Request | Method | Folder | Purpose |
|---------|--------|--------|---------|
| Get All Active Nodes | GET | ControlBusController | List all active nodes globally |
| Get Active Nodes in Workflow | GET | ControlBusController | List active nodes in a specific workflow |
| Get Node Heartbeat | GET | ControlBusController | Get node's most recent heartbeat |
| Send Command to Node | POST | ControlBusController | Send administrative command to a node |

### PluginController (`/api/plugins`)

| Request | Method | Folder | Purpose |
|---------|--------|--------|---------|
| List Plugins | GET | PluginController | List all registered workflow plugins |
| Get Plugin Details | GET | PluginController | Get detailed info for a specific plugin |

### Streaming API (Real-time SSE)

| Request | Method | Folder | Purpose |
|---------|--------|--------|---------|
| Stream Execution Progress | GET | Streaming | Real-time execution progress updates |
| Stream Execution Logs | GET | Streaming | Real-time log streaming |

## Testing Workflows

### Basic Execution Flow

1. **SessionConfigController/Apply Session Configuration** → Create/initialize session
2. **SessionConfigController/Get Session Details** → Verify configuration loaded
3. **SessionConfigController/Get Workflow** → View workflow definition
4. **WorkflowController/Start Workflow** → Start execution (returns executionId)
5. **WorkflowController/Get Workflow Status** → Check execution progress
6. **WorkflowController/Get Workflow History** → View all executions

### Real-Time Monitoring

1. **WorkflowController/Start Workflow** → Start execution
2. **WorkflowController/Stream Workflow Status** → Watch real-time updates
3. **Streaming/Stream Execution Progress** → Monitor detailed progress
4. **Streaming/Stream Execution Logs** → View logs as written

### Plugin Discovery

1. **PluginController/List Plugins** → See all available plugins
2. **PluginController/Get Plugin Details** → Learn about specific plugins
3. Review workflow definition to see which plugins are used

### Debugging Failed Execution

1. **WorkflowController/Get Workflow Status** → Check current state
2. **LogManagementController/Get Log Content** → Read execution logs
3. **ControlBusController/Get Active Nodes in Workflow** → Check node status
4. **ControlBusController/Get Node Heartbeat** → Verify node health
5. **ControlBusController/Send Command to Node** → Send diagnostic commands

## Response Format

### Success Response (200)

```json
{
  "statusCode": 200,
  "message": "Success message",
  "data": {
    "key": "value"
  },
  "timestamp": "2026-06-13T10:30:00Z"
}
```

### Error Response (4xx/5xx)

```json
{
  "statusCode": 400,
  "message": "Error message",
  "error": "ERROR_CODE",
  "path": "/api/endpoint",
  "details": []
}
```

## Test Assertions

Every request includes automated tests that verify:

- Correct HTTP status codes
- Expected response structure
- Data type validation
- Conditional checks for optional fields

Click the **Test** tab in Bruno to run assertions after each request.

## HTTP Status Codes

| Code | Status | Meaning |
|------|--------|---------|
| 200 | OK | Request succeeded |
| 202 | Accepted | Async request accepted (workflow trigger) |
| 404 | Not Found | Resource doesn't exist |
| 400 | Bad Request | Invalid input data |
| 500 | Internal Server Error | Server-side failure |

## Streaming Endpoints (SSE)

For Server-Sent Events endpoints:

1. Connection stays open until workflow completes
2. Real-time updates stream to Response panel
3. Bruno handles SSE automatically
4. Use for monitoring long-running executions

## Common Issues & Solutions

| Issue | Solution |
|-------|----------|
| Connection Refused | Ensure Yukta is running: `./gradlew bootRun` |
| Session Not Found (404) | Run `Apply Session Configuration` first |
| Execution Not Found (404) | Use executionId from `Trigger Workflow` response |
| No Log Files | Run `Trigger Workflow` first to generate logs |
| Variables Not Loading | Click Environment dropdown, select environment again |
| Wrong Deployment Tested | Check Environment dropdown (top-right) before running |

## Advanced Features

### Auto-Variables

Bruno automatically extracts and stores variables from responses:

- `executionId` - Captured from Trigger Workflow response
- `logFilename` - Captured from List Logs response
- Custom variables via test assertions: `bru.setVar("name", value)`

### Sequential Testing

1. Run requests in documented order
2. Chained requests automatically use previously set variables
3. Example: Trigger Workflow sets `executionId` for Status/Stream requests

### Environment Switching

During testing, switch between environments:

1. Click Environment dropdown (top-right)
2. Select different environment
3. All variables instantly change to new environment's values
4. Ready to test with different deployment

### Override Variables Temporarily

While using an environment:

1. Click Environment dropdown
2. Select **Manage Environments**
3. Edit a variable directly
4. Changes apply only for current session

## Keyboard Shortcuts

- **Ctrl/Cmd + Enter** - Send request
- **Ctrl/Cmd + B** - Open in browser
- **Variables Tab** - View all current variables
- **Test Tab** - View test results

## Typical Request Flow

```
1. POST /api/sessions                              (SessionConfigController/Apply Configuration)
2. GET  /api/sessions/{sessionId}                  (SessionConfigController/Get Session Details)
3. GET  /api/sessions/{sessionId}/workflows/{wfId} (SessionConfigController/Get Workflow)
4. GET  /api/plugins                               (PluginController/List Plugins)
5. POST /api/workflow/start                        (WorkflowController/Start Workflow)
6. GET  /api/workflow/{sessionId}/status/{execId}  (WorkflowController/Get Workflow Status)
7. GET  /api/logs/{sessionId}                      (LogManagementController/List Logs)
8. GET  /api/logs/{sessionId}/{filename}           (LogManagementController/Get Log Content)
9. GET  /api/control/workflows/{wfId}/nodes        (ControlBusController/Get Active Nodes)
```

## Setup by Deployment Type

### Local Development

1. Select `local` environment (pre-configured)
2. Run `./gradlew bootRun` to start Yukta
3. Execute requests - all point to `http://localhost:8080`

### Production Deployment

1. Select `production` environment (pre-configured)
2. Or create custom environment with your production URL
3. Execute requests - all point to your production API

### Staging / Custom Deployment

1. Click Environment dropdown → **Manage Environments**
2. Click **Add New Environment**
3. Name it (e.g., "staging")
4. Set `base_url` to your staging URL
5. Keep other variables same as local
6. Save and select from dropdown

## Tips & Tricks

1. **Export Results** - Bruno can export test results for CI/CD pipelines
2. **Collection Sharing** - Export collection as JSON to share with team
3. **Verify Before Testing** - Always check Environment dropdown before running requests
4. **Response Parsing** - Use test scripts to extract and store data from responses
5. **Compare Environments** - Test same request across local/staging/production to compare results

## Related Resources

- **Swagger UI**: `http://localhost:8080/swagger-ui.html`
- **Source Code**: `web/src/main/java/com/infenia/yukta/controller/`
  - SessionConfigController.java
  - WorkflowController.java
  - LogManagementController.java
  - ControlBusController.java
  - PluginController.java
- **Project Documentation**: CLAUDE.md (architecture & conventions)

## Collection Summary

| Metric | Count |
|--------|-------|
| Total API Requests | 20 |
| Functional Areas | 8 |
| Pre-configured Environments | 2 |
| Request Folders | 7 |
| Documentation | 1 (this file) |

## Getting Help

1. **Environment Issues?** Check Variables tab (click Variables → see current values)
2. **API Questions?** Check Swagger UI: `http://localhost:8080/swagger-ui.html`
3. **Request Failing?** Review the test assertions (click Test tab) for details
4. **Connection Issues?** Verify Yukta is running: `./gradlew bootRun`

---

**Start here**: Import collection → Select `local` environment → Run `SessionConfigController/Apply Session Configuration.bru`

**Time to first test**: ~1 minute (environment already configured!)

## Collection Reorganization

**As of v2.0**: Collections are now organized by controller name to match the backend REST controller structure. This makes it easier to:

- Track which controller owns which endpoints
- Maintain API requests alongside controller changes
- Discover related endpoints by controller
- Scale with new controllers

Old folder names → New controller-based names:
- `Sessions/` → `SessionConfigController/`
- `Execution/` → `WorkflowController/`
- `Logging/` → `LogManagementController/`
- `Control Bus/` + `Observability/` → `ControlBusController/`
- `Plugins/` → `PluginController/`
- `Streaming/` → `Streaming/` (unchanged)
