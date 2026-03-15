# MCP Tooling Expansion Design

**Date:** March 13, 2026
**Status:** Approved
**Objective:** Expand Yukta MCP (Model Context Protocol) tools to provide comprehensive session management, workflow orchestration, monitoring, and plugin development guidance for AI agents.

## Overview

Yukta currently provides basic MCP tools for workflow execution and plugin discovery. This design expands the MCP tooling to 6 new tools that enable AI agents to:
1. Create and manage sessions with full guidance
2. Monitor and stream logs with filtering
3. Observe system health via control bus
4. Develop new plugins with comprehensive templates and documentation

## Current State

The existing `AppMcpTools` component provides:
- `getSessionDetails()` — Get session workflow list
- `getWorkflowDetails()` — Get DAG definition
- `triggerWorkflow()` — Execute a workflow
- `getWorkflowStatus()` — Get execution summary
- `listPlugins()` — List all plugins
- `getPluginDetails()` — Get plugin specifications

**Gap:** No tools for session creation, log streaming/filtering, control bus monitoring, or plugin development guidance.

## Proposed MCP Tools

### **Category 1: Session Management (2 Tools)**

#### Tool 1a: `getSessionCreationInstructions()`
- **Type:** Documentation/Guidance tool
- **Purpose:** Provide AI agents with comprehensive instructions on session creation
- **Input:** None
- **Output:** `SessionCreationGuide` object containing:
  - Session ID naming conventions and requirements
  - Session configuration structure with examples
  - Workflow definition format (nodes, edges)
  - Available plugins and their configuration requirements
  - Example session configuration (complete JSON)
  - Common validation errors and remediation
  - Links to plugin documentation
- **Error Handling:** Always succeeds; returns guidance
- **Implementation:** Return hard-coded or config-driven guide object

#### Tool 1b: `createSession(sessionConfigJson: String)`
- **Type:** Creation tool
- **Purpose:** Create a new session with provided configuration
- **Input:** JSON string containing:
  ```json
  {
    "sessionId": "my-session-123",
    "workflows": {
      "workflow-id-1": {
        "nodes": [...],
        "edges": [...]
      }
    }
  }
  ```
- **Output:** `SessionCreationResponse` containing:
  - sessionId (confirmed)
  - List of created workflows with their IDs
  - Validation warnings (if any)
  - Success status
- **Error Handling:** Detailed validation errors with context for AI agent debugging
  - Missing required fields
  - Invalid workflow definitions (DAG cycles, missing nodes)
  - Plugin configuration mismatches
- **Implementation:** Wraps `SessionService.applyConfig()`, translates exceptions to detailed error responses

### **Category 2: Session Discovery (1 Tool)**

#### Tool 2: `listSessions()`
- **Type:** Discovery tool
- **Purpose:** List all available sessions
- **Input:** None
- **Output:** `List<SessionInfo>` where each contains:
  - sessionId
  - workflowCount (number of workflows configured)
  - createdAt (timestamp)
  - lastModified (timestamp)
  - status ("active" | "idle" | "error")
- **Error Handling:** Returns empty list if no sessions exist
- **Implementation:** Queries `SessionService.getSessionIds()` and enriches with metadata

### **Category 3: Workflow Execution (Enhancement)**

#### Tool 3: `triggerWorkflow()` — Already exists
- Enhance existing tool with optional execution options (if needed)
- Current implementation is adequate

### **Category 4: Monitoring & Logging (2 Tools)**

#### Tool 4: `streamSessionLogs(sessionId: String, workflowId?: String, executionId?: String, filterPattern?: String)`
- **Type:** Streaming tool
- **Purpose:** Stream execution logs with real-time filtering
- **Input:**
  - `sessionId` (required): Session to monitor
  - `workflowId` (optional): Filter to specific workflow
  - `executionId` (optional): Filter to specific execution
  - `filterPattern` (optional): Regex pattern (e.g., "ERROR|WARN", "plugin.*failed")
- **Output:** `Flux<String>` — Stream of log lines matching filter criteria
- **Filtering:** Apply regex matching on each log line before emitting
- **Error Handling:**
  - InvalidSessionId → error
  - InvalidFilterPattern → error
  - Session with no logs → empty stream
- **Implementation:**
  - Query session logs from `SessionService` (JSONL format)
  - Apply optional workflowId and executionId filters
  - Compile and apply regex filter if provided
  - Stream filtered lines as Flux

#### Tool 5: `getWorkflowExecutionLogs(sessionId: String, executionId: String, filterPattern?: String)`
- **Type:** Historical retrieval tool
- **Purpose:** Fetch complete logs for a past execution with filtering
- **Input:**
  - `sessionId` (required)
  - `executionId` (required)
  - `filterPattern` (optional): Regex pattern
- **Output:** `String` (complete log content, possibly filtered)
- **Error Handling:**
  - InvalidSessionId → error
  - InvalidExecutionId → error
  - InvalidFilterPattern → error
- **Implementation:** Query from `TaskTrackerService.getHistory()`, apply regex filter if provided

### **Category 5: Control Bus Monitoring (1 Tool)**

#### Tool 6: `getControlBusStatus(filterType?: String)`
- **Type:** Read-only monitoring tool
- **Purpose:** Expose system-wide state, health, and diagnostics
- **Input:**
  - `filterType` (optional): "sessions" | "plugins" | "health" | "executions" | null (defaults to all)
- **Output:** `ControlBusStatus` object containing:
  ```
  {
    "activeSessions": [
      { "sessionId": "...", "activeExecutions": 3, "totalWorkflows": 5 },
      ...
    ],
    "pluginRegistry": [
      { "type": "gradle-plugin", "category": "PROCESSOR", "status": "available" },
      ...
    ],
    "systemHealth": {
      "threadPoolUtilization": 0.45,
      "queueDepth": 12,
      "memoryUsage": { "used": "512MB", "max": "1024MB" },
      "uptime": "2h 30m"
    },
    "recentExecutions": [
      { "sessionId": "...", "executionId": "...", "status": "COMPLETED", "duration": "1.5s" },
      ...
    ]
  }
  ```
- **Filtering:** Apply filterType to return only requested sections
- **Error Handling:** Always succeeds; returns available data
- **Implementation:** Aggregates data from:
  - `SessionService.getSessionIds()` + `TaskTrackerService`
  - `WorkflowRegistry.listPlugins()`
  - Spring Boot Actuator (`MetricsEndpoint`, `ThreadDumpEndpoint`)
  - `TaskTrackerService.getHistory()`

### **Category 6: Plugin Development Guidance (1 Tool)**

#### Tool 7: `getPluginCreationGuide(templateType?: String)`
- **Type:** Documentation/Template tool
- **Purpose:** Provide comprehensive plugin development guide with templates and examples
- **Input:**
  - `templateType` (optional): "trigger" | "processor" | "terminal" | "all" (defaults to "all")
- **Output:** `PluginCreationGuide` object containing:

  **1. Architecture Overview**
  - Plugin lifecycle (registration → node execution → cleanup)
  - Three plugin types and their responsibilities
  - Execution model (reactive, non-blocking)
  - How plugins fit into DAG orchestration

  **2. Template Code** (Java scaffolding)
  - For each requested type, provide:
    - Interface signature
    - Lombok annotations for immutability
    - Required methods with JavaDoc
    - Example validation using Jakarta annotations
    - Example reactive implementation (Mono/Flux)

  **3. Integration Examples**
  - How to register plugin in `WorkflowRegistry` (Spring bean)
  - Example workflow DAG that uses the new plugin
  - How to define input/output ports
  - Configuration property examples

  **4. Configuration Reference**
  - Plugin metadata (type, category, description)
  - UI design patterns (optional)
  - Configuration properties and their types

  **5. Validation & Quality Checklist**
  - Checkstyle compliance (Google Java Style, 100-char lines)
  - PMD rules (code quality)
  - SpotBugs detection (bug patterns)
  - JaCoCo coverage requirements
  - License headers (Apache 2.0)

  **6. Testing Strategy**
  - JUnit 5 + Mockito for unit tests
  - `StepVerifier` for Reactor testing
  - Example test class
  - Mocking WorkflowRegistry and external dependencies

  **7. Deployment & Registration**
  - How to package the plugin
  - Spring Boot auto-configuration patterns
  - How to make plugin discoverable via classpath scanning

- **Error Handling:** Always succeeds; returns guide sections
- **Implementation:** Return hard-coded or config-driven guide with template code snippets

---

## Data Models

### SessionCreationGuide
```
- namingConventions: String
- configurationStructure: Map
- exampleSessionConfig: String (JSON)
- workflowDefinitionFormat: String (description)
- availablePlugins: List<PluginReference>
- commonErrors: List<ErrorExample>
```

### SessionCreationResponse
```
- sessionId: String
- createdWorkflows: List<String> (workflow IDs)
- warnings: List<String>
- success: Boolean
```

### SessionInfo
```
- sessionId: String
- workflowCount: Integer
- createdAt: LocalDateTime
- lastModified: LocalDateTime
- status: String
```

### ControlBusStatus
```
- activeSessions: List<SessionExecutionInfo>
- pluginRegistry: List<PluginRegistryEntry>
- systemHealth: SystemHealthMetrics
- recentExecutions: List<ExecutionRecord>
```

### PluginCreationGuide
```
- architectureOverview: String
- templateCode: Map<String, String> (type → code)
- integrationExamples: String (JSON + explanation)
- configurationReference: String
- validationChecklist: List<String>
- testingStrategy: String
- deploymentGuide: String
```

---

## Implementation Approach

### **Phase 1: Session Management Tools**
1. Create `SessionCreationGuide` record/class with comprehensive guidance content
2. Implement `getSessionCreationInstructions()` tool
3. Implement `createSession()` tool with detailed error translation
4. Add tests

### **Phase 2: Session Discovery & Monitoring**
1. Implement `listSessions()` tool
2. Implement `streamSessionLogs()` with regex filtering
3. Implement `getWorkflowExecutionLogs()` tool
4. Add tests

### **Phase 3: Control Bus & Plugin Guidance**
1. Implement `getControlBusStatus()` tool with metrics aggregation
2. Create `PluginCreationGuide` with template code
3. Implement `getPluginCreationGuide()` tool
4. Add tests

### **Integration Points**
- All tools added to `AppMcpTools` component
- Leverage existing services: `SessionService`, `WorkflowRegistry`, `TaskTrackerService`, `WorkflowService`
- Use Spring Boot Actuator for health/metrics data
- Maintain reactive patterns (Mono/Flux)
- Follow Yukta code style: Google Java Style, Spotless, license headers

---

## Error Handling Strategy

### Session Creation Errors
- **Invalid sessionId:** Suggest naming conventions
- **Invalid workflow definition:** Show DAG validation details (cycles, missing nodes)
- **Plugin not found:** List available plugins
- **Configuration mismatch:** Show expected vs. provided structure

### Log Streaming Errors
- **Invalid filter pattern:** Return error with regex syntax help
- **Session not found:** Return empty stream

### Control Bus Errors
- **Metrics unavailable:** Return partial status with available data
- **No active sessions:** Return empty sessions list

### Plugin Guide Errors
- **Invalid template type:** List valid options
- Always return guide data (never fails)

---

## Success Criteria

✓ All 6 new MCP tools are implemented and tested
✓ AI agents can create sessions with detailed guidance
✓ Logs are filterable with regex patterns in real-time and historically
✓ Control bus provides complete system visibility (read-only)
✓ Plugin creation guide enables self-service plugin development
✓ All tools follow Yukta code style and quality gates
✓ Comprehensive test coverage (80%+ line coverage)
✓ Tools are production-ready and documented in Swagger UI

---

## Future Enhancements

- Batch operations (create multiple sessions, trigger multiple workflows)
- Export/import session configurations
- Audit logging of session and workflow creations
- WebSocket-based real-time log streaming (vs. SSE)
- Advanced filtering (JSON path queries on structured logs)
- Performance profiling endpoints for plugins
