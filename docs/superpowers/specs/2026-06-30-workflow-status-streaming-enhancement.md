# Workflow Status Streaming Enhancement Design

**Date**: 2026-06-30  
**Author**: Arun  
**Status**: Design Review

---

## Overview

Enhance the workflow status streaming API (`GET /api/workflow/{sessionId}/status/{executionId}/stream`) with two features:

1. **Auto-stop on Terminal State**: Stream automatically closes when the workflow reaches a terminal state
2. **Historical Status Buffering**: Clients receive recent status history (last N minutes) when connecting, preventing loss of status updates during the gap between workflow start and stream subscription

---

## Problem Statement

### Current Behavior
- When a client starts a workflow, it receives an `executionId`
- Client must then initiate a separate stream connection using that `executionId`
- **Gap Problem**: During the delay between workflow start and stream subscription, status updates are lost
- Stream continues indefinitely, forcing clients to implement their own termination logic

### New Behavior
- Clients receive recent status history when connecting to the stream
- Stream automatically terminates when the workflow reaches a terminal state
- Operators can configure the historical buffer window (with safe bounds)

---

## Architecture

### 1. Status History Cache Service

**New Component**: `StatusHistoryCache`

**Purpose**: Maintain a time-bounded, in-memory cache of recent workflow progress updates per execution.

**Implementation**:
- Built on Caffeine cache with TTL-based eviction
- **Key**: `executionId` (String)
- **Value**: `ConcurrentLinkedDeque<WorkflowProgress>` (ordered insertion, thread-safe)
- Configuration via `application.yaml`

**Configuration**:
```yaml
yukta:
  streaming:
    status-history-ttl-minutes: 5  # User-configurable (default: 5 minutes)
```

**Hard-coded Constraint**:
```java
private static final int MAX_TTL_MINUTES = 30;  // Application limit, not configurable
```

**API**:
```java
public interface StatusHistoryCache {
  // Record a status update for an execution
  void put(String executionId, WorkflowProgress progress);
  
  // Retrieve all cached updates for an execution (empty list if not found/expired)
  List<WorkflowProgress> get(String executionId);
}
```

**Behavior**:
- Constructor validates configured TTL against hard-coded max: `if (ttl > MAX_TTL_MINUTES) throw IllegalArgumentException`
- TTL applies per execution: when an execution expires from cache, all its history is automatically evicted
- `get()` returns an immutable copy (defensive copy of deque contents)
- Caffeine handles thread safety and TTL eviction automatically

---

### 2. Enhanced TaskTrackerService

**Modified Component**: `DefaultTaskTrackerService.getStatusStream()`

**Current Signature**:
```java
Flux<WorkflowProgress> getStatusStream(String executionId);
```

**New Signature**:
```java
Flux<WorkflowProgress> getStatusStream(String executionId, boolean includeHistory);
```

**Implementation Logic**:

1. **Fetch History** (if `includeHistory=true`):
   - Query `StatusHistoryCache.get(executionId)`
   - Returns ordered list of recent updates

2. **Build Combined Stream**:
   ```
   Flux.fromIterable(history)           // Emit history first
       .concatWith(liveStream)           // Then chain live updates
       .takeUntil(this::isTerminalState) // Auto-complete on terminal state
   ```

3. **Terminal State Detection**:
   ```java
   private boolean isTerminalState(WorkflowProgress progress) {
     String status = progress.status();
     LocalDateTime endTime = progress.endTime();
     
     Set<String> terminalStatuses = Set.of(
       "COMPLETED", "FAILED", "CANCELLED", "WORKFLOW_STOPPED"
     );
     
     // Both conditions must be true
     return terminalStatuses.contains(status) && endTime != null;
   }
   ```

4. **Update Cache on Live Emit**:
   - Each progress update flowing through the live stream is recorded:
   ```
   liveStream
     .doOnNext(progress -> statusHistoryCache.put(executionId, progress))
     .doOnError(error -> log)
   ```

**Backward Compatibility**:
- Existing calls to `getStatusStream(executionId)` are overloaded with a default parameter
- Default: `includeHistory = true` (clients get history by default, can opt-out if needed)

---

### 3. Controller Enhancement

**Modified Component**: `WorkflowController.streamWorkflowStatus()`

**Current Signature**:
```java
@GetMapping(
    value = "/workflow/{sessionId}/status/{executionId}/stream",
    produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<WorkflowProgress>> streamWorkflowStatus(
    @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
    @Parameter(description = "Execution ID") @PathVariable final String executionId);
```

**New Signature**:
```java
@GetMapping(
    value = "/workflow/{sessionId}/status/{executionId}/stream",
    produces = MediaType.TEXT_EVENT_STREAM_VALUE)
public Flux<ServerSentEvent<WorkflowProgress>> streamWorkflowStatus(
    @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
    @Parameter(description = "Execution ID") @PathVariable final String executionId,
    @Parameter(description = "Include historical status updates (last N minutes)")
    @RequestParam(defaultValue = "true") boolean includeHistory);
```

**Implementation**:
```java
log.atInfo().log(
    "streamWorkflowStatus: sessionId={}, executionId={}, includeHistory={}",
    sessionId, executionId, includeHistory);

return controlBus
    .watchExecution(executionId, includeHistory)  // Pass includeHistory flag
    .doOnNext(_ -> log.atDebug().log("Status received"))
    .map(progress -> ServerSentEvent.<WorkflowProgress>builder()
        .data(progress)
        .build())
    .doOnError(error -> log.atError().log("Stream error: {}", error.getMessage()))
    .doOnComplete(() -> log.atInfo().log("Stream completed: {}", executionId));
```

---

### 4. ControlBusGateway Enhancement

**Modified Component**: `ControlBusGateway.watchExecution()`

**Current Signature**:
```java
Flux<WorkflowProgress> watchExecution(String executionId);
```

**New Signature** (in interface and default implementation):
```java
Flux<WorkflowProgress> watchExecution(String executionId, boolean includeHistory);
```

**Default Implementation** (DefaultControlBusGateway):
```java
@Override
public Flux<WorkflowProgress> watchExecution(
    final String executionId, final boolean includeHistory) {
  log.atDebug()
      .addKeyValue("executionId", executionId)
      .addKeyValue("includeHistory", includeHistory)
      .log("Starting to watch workflow execution");
  
  return taskTracker.getStatusStream(executionId, includeHistory)
      .doOnSubscribe(_ -> log.atDebug().log("Subscribed to execution status stream"))
      .doOnNext(progress -> log.atTrace()
          .addKeyValue("status", progress.status())
          .log("Received execution progress update"))
      .doOnError(error -> log.atError().log("Stream error"))
      .doOnComplete(() -> log.atInfo().log("Stream completed"));
}
```

---

## Data Flow

```
Client: GET /api/workflow/{sessionId}/status/{executionId}/stream?includeHistory=true
    ↓
WorkflowController.streamWorkflowStatus()
    ↓
ControlBusGateway.watchExecution(executionId, true)
    ↓
TaskTrackerService.getStatusStream(executionId, true)
    ↓
[1] Fetch history from StatusHistoryCache.get(executionId)
    ↓
[2] Emit cached updates via Flux.fromIterable(history)
    Client receives: [Status1, Status2, Status3, ...]  (history)
    ↓
[3] Chain live stream: .concatWith(liveStream)
    Client receives: [Status4, Status5, ...]  (live)
    ↓
[4] Each live update: statusHistoryCache.put(executionId, progress)
    ↓
[5] Monitor for terminal: .takeUntil(isTerminalState(progress))
    When status ∈ {COMPLETED, FAILED, CANCELLED, WORKFLOW_STOPPED} AND endTime != null:
      → Stream completes
      → Client connection closes gracefully
```

---

## Terminal State Definition

A workflow has reached terminal state when **BOTH** conditions are true:

1. **Status is terminal**: `status ∈ {COMPLETED, FAILED, CANCELLED, WORKFLOW_STOPPED}`
2. **End time is set**: `endTime != null`

This dual-check ensures:
- Status field reflects actual workflow state
- Timestamp is recorded (prevents half-initialized states)
- Safety against edge cases where only one field updates

---

## Configuration

**File**: `boot/src/main/resources/application.yaml`

```yaml
yukta:
  streaming:
    status-history-ttl-minutes: 5  # Configurable: history retention window
    # Hard-coded max: 30 minutes (application constraint, prevents memory issues)
```

**Validation** (in `StatusHistoryCache` constructor):
```java
if (configuredTtl <= 0 || configuredTtl > MAX_TTL_MINUTES) {
  throw new IllegalArgumentException(
    "Invalid TTL: " + configuredTtl + 
    " (must be 0 < ttl <= " + MAX_TTL_MINUTES + ")");
}
```

---

## Components & Files

| Component | File | Change |
|-----------|------|--------|
| StatusHistoryCache (NEW) | `core/src/main/java/com/infenia/yukta/service/streaming/StatusHistoryCache.java` | Create interface |
| DefaultStatusHistoryCache (NEW) | `core/src/main/java/com/infenia/yukta/service/streaming/DefaultStatusHistoryCache.java` | Create impl |
| TaskTrackerService | `core/src/main/java/com/infenia/yukta/service/orchestrator/tracker/DefaultTaskTrackerService.java` | Enhance `getStatusStream()` |
| ControlBusGateway | `core/src/main/java/com/infenia/yukta/service/control/gateway/ControlBusGateway.java` | Add overload |
| DefaultControlBusGateway | `core/src/main/java/com/infenia/yukta/service/control/gateway/DefaultControlBusGateway.java` | Implement overload |
| WorkflowController | `web/src/main/java/com/infenia/yukta/controller/WorkflowController.java` | Add `includeHistory` param |
| Application Config | `boot/src/main/resources/application.yaml` | Add TTL config |

---

## Testing Strategy

### Unit Tests

**StatusHistoryCache**:
- ✓ `put()` and `get()` roundtrip
- ✓ TTL validation in constructor (rejects TTL > 30 min)
- ✓ Thread-safety: concurrent `put()` and `get()` calls
- ✓ Expiration: entries removed after TTL

**TaskTrackerService.getStatusStream()**:
- ✓ `includeHistory=true` emits cached history first, then live
- ✓ `includeHistory=false` skips history, goes straight to live
- ✓ Terminal state detection: `takeUntil()` fires on COMPLETED + endTime set
- ✓ Terminal state detection: `takeUntil()` fires on CANCELLED + endTime set
- ✓ Terminal state detection: `takeUntil()` does NOT fire if only status is terminal (endTime null)
- ✓ Each live update is cached via `doOnNext()`

**ControlBusGateway**:
- ✓ Overload forwards `includeHistory` to task tracker

**WorkflowController**:
- ✓ Query param `includeHistory=true` is passed through
- ✓ Query param `includeHistory=false` is passed through
- ✓ Query param defaults to `true` if omitted

### Integration Tests

- ✓ Full flow: start workflow → connect to stream (late) → receive history → receive live updates → stream auto-completes
- ✓ Early connection: connect to stream before workflow completes → see updates → stream auto-completes
- ✓ No history: connect after TTL expires → see only live updates onward

### Manual Testing (Verify skill)

- Start a workflow, note `executionId`
- Wait 2+ seconds (ensure status updates accumulate)
- Stream with `?includeHistory=true` → should see accumulated updates immediately
- Stream with `?includeHistory=false` → should see only live updates going forward
- Stream continues until workflow reaches terminal state (COMPLETED, etc. + endTime set)

---

## Error Handling

**Cache Misconfiguration**:
- If `status-history-ttl-minutes` > 30: Constructor throws `IllegalArgumentException`
- Startup fails, operator must fix config before app starts

**Cache Miss**:
- `get()` returns empty list if execution not found or expired
- Stream starts with live updates only (no history loss, just no catchup)

**Stream Errors**:
- Existing error handling in `doOnError()` applies
- Terminal state check is additive (does not break existing error flows)

---

## Performance Considerations

**Memory**:
- Caffeine cache size bounded by TTL + entry count
- Worst case: N executions × M status updates per execution × ~1KB per update
  - Example: 1000 concurrent executions × 100 updates/execution × 5 min TTL = ~100MB
  - Safe limit with default config

**Latency**:
- History fetch: O(M) where M = history size (typically <100 items)
- Negligible (<5ms) vs. network latency

**Concurrency**:
- Caffeine handles thread safety internally
- No additional locking needed in TaskTrackerService

---

## Backward Compatibility

- Existing code calling `getStatusStream(executionId)` works unchanged (uses default `includeHistory=true`)
- Existing code calling `watchExecution(executionId)` works unchanged (delegates to new overload with `includeHistory=true`)
- REST API clients can ignore `?includeHistory` param (defaults to true)
- Stream format unchanged (same `ServerSentEvent<WorkflowProgress>` structure)

---

## Success Criteria

✓ Clients connecting late to stream receive recent status updates (within configured TTL)  
✓ Stream automatically closes when workflow reaches terminal state (both status + endTime conditions met)  
✓ Configuration validates bounds (TTL ≤ 30 min)  
✓ All existing tests pass  
✓ New tests cover history + terminal detection paths  
✓ Manual verification confirms feature works end-to-end  

---

## Next Steps

1. **Implementation**: Write implementation code (files listed above)
2. **Testing**: Unit + integration tests
3. **Verification**: Manual testing with real workflow
4. **Code Review**: Review before merge
5. **Documentation**: Update API docs (OpenAPI/Swagger)
