# Workflow Status Streaming Enhancement Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Enhance workflow status streaming to automatically stop on terminal states and provide historical status buffering for late-connecting clients.

**Architecture:** 
- New Caffeine-backed `StatusHistoryCache` service maintains a TTL-bounded buffer of recent `WorkflowProgress` updates per execution
- Enhanced `TaskTrackerService.getStatusStream()` accepts an `includeHistory` flag to emit cached history before live updates
- Terminal state detection via dual condition (status ∈ {COMPLETED, FAILED, CANCELLED, WORKFLOW_STOPPED} AND endTime != null) auto-completes the stream using `takeUntil()`
- Configuration option in `application.yaml` with hard-coded 30-minute max TTL safety limit

**Tech Stack:** 
- Caffeine cache (TTL-based eviction)
- Project Reactor (Flux, doOnNext, takeUntil)
- Spring Boot 4.0.2 (WebFlux, configuration)
- JUnit 5 + Reactor Test (StepVerifier)

## Global Constraints

- Java 25 (from CLAUDE.md)
- Spring Boot 4.0.2 WebFlux reactive streams
- Use Project Reactor operators (`Flux`, `doOnNext`, `takeUntil`, `concatWith`)
- Follow Google Java Style Guide (2-space indentation, 100-char line limit)
- Apply Spotless formatting before commit: `./gradlew spotlessApply`
- All new Java files must include Apache License 2.0 header
- Use Lombok `@Slf4j` for logging
- Validate all user input with Jakarta Bean Validation annotations

---

## File Structure

**New Files:**
- `core/src/main/java/com/infenia/yukta/service/streaming/StatusHistoryCache.java` (interface)
- `core/src/main/java/com/infenia/yukta/service/streaming/DefaultStatusHistoryCache.java` (impl)
- `core/src/test/java/com/infenia/yukta/service/streaming/DefaultStatusHistoryCacheTest.java` (tests)
- `core/src/test/java/com/infenia/yukta/service/orchestrator/tracker/DefaultTaskTrackerServiceStreamingTest.java` (streaming-specific tests)

**Modified Files:**
- `core/src/main/java/com/infenia/yukta/service/orchestrator/tracker/DefaultTaskTrackerService.java` (add overload, terminal detection)
- `core/src/main/java/com/infenia/yukta/service/orchestrator/tracker/TaskTrackerService.java` (interface)
- `core/src/main/java/com/infenia/yukta/service/control/gateway/ControlBusGateway.java` (add overload)
- `core/src/main/java/com/infenia/yukta/service/control/gateway/DefaultControlBusGateway.java` (implement overload)
- `web/src/main/java/com/infenia/yukta/controller/WorkflowController.java` (add query param)
- `boot/src/main/resources/application.yaml` (configuration)

**Dependencies:**
- Caffeine cache (likely already in classpath, check `libs.versions.toml`)

---

## Task 1: StatusHistoryCache Interface

**Files:**
- Create: `core/src/main/java/com/infenia/yukta/service/streaming/StatusHistoryCache.java`

**Interfaces:**
- Produces: Interface `StatusHistoryCache` with methods `void put(String executionId, WorkflowProgress progress)` and `List<WorkflowProgress> get(String executionId)`

- [ ] **Step 1: Create the interface file**

Create `core/src/main/java/com/infenia/yukta/service/streaming/StatusHistoryCache.java`:

```java
/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.service.streaming;

import com.infenia.yukta.model.execution.WorkflowProgress;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.List;

/**
 * Cache for storing recent workflow progress updates per execution.
 *
 * <p>Maintains a time-bounded, in-memory cache of WorkflowProgress snapshots. Each execution's
 * history is automatically evicted after the configured TTL expires.
 */
public interface StatusHistoryCache {

  /**
   * Record a status update for an execution.
   *
   * @param executionId the execution identifier
   * @param progress the workflow progress snapshot
   */
  void put(@NotBlank String executionId, @NotNull WorkflowProgress progress);

  /**
   * Retrieve all cached updates for an execution.
   *
   * @param executionId the execution identifier
   * @return an immutable list of cached progress updates (empty if not found or expired)
   */
  List<WorkflowProgress> get(@NotBlank String executionId);
}
```

- [ ] **Step 2: Verify file created**

Run:
```bash
ls -la core/src/main/java/com/infenia/yukta/service/streaming/StatusHistoryCache.java
```

Expected: File exists with no errors

---

## Task 2: DefaultStatusHistoryCache Implementation

**Files:**
- Create: `core/src/main/java/com/infenia/yukta/service/streaming/DefaultStatusHistoryCache.java`

**Interfaces:**
- Consumes: `StatusHistoryCache` interface from Task 1
- Produces: Class `DefaultStatusHistoryCache implements StatusHistoryCache` with Caffeine cache, TTL validation, `put()` and `get()` methods

- [ ] **Step 1: Create implementation file**

Create `core/src/main/java/com/infenia/yukta/service/streaming/DefaultStatusHistoryCache.java`:

```java
/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.service.streaming;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.infenia.yukta.model.execution.WorkflowProgress;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.TimeUnit;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Default implementation of StatusHistoryCache using Caffeine cache with TTL.
 *
 * <p>Maintains a deque of recent WorkflowProgress updates per execution. Each execution's
 * history is automatically evicted after the configured TTL. Hard limit of 30 minutes is enforced
 * to prevent memory exhaustion.
 */
@Slf4j
@Service
public class DefaultStatusHistoryCache implements StatusHistoryCache {

  /** Hard-coded maximum TTL in minutes. */
  private static final int MAX_TTL_MINUTES = 30;

  /** The Caffeine cache storing deques of progress updates per execution. */
  private final Cache<String, ConcurrentLinkedDeque<WorkflowProgress>> cache;

  /**
   * Constructor initializing the Caffeine cache with configurable TTL.
   *
   * @param ttlMinutes configured TTL from application.yaml (must be > 0 and <= 30)
   * @throws IllegalArgumentException if TTL is invalid
   */
  public DefaultStatusHistoryCache(
      @Value("${yukta.streaming.status-history-ttl-minutes:5}") final int ttlMinutes) {
    if (ttlMinutes <= 0 || ttlMinutes > MAX_TTL_MINUTES) {
      throw new IllegalArgumentException(
          "Invalid TTL: "
              + ttlMinutes
              + " (must be 0 < ttl <= "
              + MAX_TTL_MINUTES
              + ")");
    }

    log.atInfo()
        .addKeyValue("ttlMinutes", ttlMinutes)
        .addKeyValue("maxTtlMinutes", MAX_TTL_MINUTES)
        .log("Initializing StatusHistoryCache");

    this.cache =
        Caffeine.newBuilder()
            .expireAfterWrite(ttlMinutes, TimeUnit.MINUTES)
            .build();

    log.atDebug().log("StatusHistoryCache initialized successfully");
  }

  @Override
  public void put(
      @NotBlank final String executionId, @NotNull final WorkflowProgress progress) {
    try {
      ConcurrentLinkedDeque<WorkflowProgress> deque =
          cache.get(
              executionId,
              _ -> {
                log.atDebug()
                    .addKeyValue("executionId", executionId)
                    .log("Creating new history deque");
                return new ConcurrentLinkedDeque<>();
              });

      deque.addLast(progress);

      log.atTrace()
          .addKeyValue("executionId", executionId)
          .addKeyValue("historySize", deque.size())
          .log("Progress recorded to history");
    } catch (final Exception e) {
      log.atWarn()
          .setCause(e)
          .addKeyValue("executionId", executionId)
          .log("Failed to record progress to history");
    }
  }

  @Override
  public List<WorkflowProgress> get(@NotBlank final String executionId) {
    final ConcurrentLinkedDeque<WorkflowProgress> deque = cache.getIfPresent(executionId);

    if (deque == null) {
      log.atTrace()
          .addKeyValue("executionId", executionId)
          .log("History cache miss or expired");
      return Collections.emptyList();
    }

    final List<WorkflowProgress> history = List.copyOf(deque);
    log.atTrace()
        .addKeyValue("executionId", executionId)
        .addKeyValue("historySize", history.size())
        .log("Retrieved history from cache");
    return history;
  }
}
```

- [ ] **Step 2: Verify Caffeine dependency**

Run:
```bash
grep -i "caffeine" core/build.gradle
```

Expected: Caffeine dependency present (if not, add to build.gradle: `implementation libs.caffeine`)

- [ ] **Step 3: Verify file created**

Run:
```bash
ls -la core/src/main/java/com/infenia/yukta/service/streaming/DefaultStatusHistoryCache.java
```

Expected: File exists

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/infenia/yukta/service/streaming/StatusHistoryCache.java core/src/main/java/com/infenia/yukta/service/streaming/DefaultStatusHistoryCache.java
git commit -m "feat: Add StatusHistoryCache service for streaming history"
```

---

## Task 3: Unit Tests for StatusHistoryCache

**Files:**
- Create: `core/src/test/java/com/infenia/yukta/service/streaming/DefaultStatusHistoryCacheTest.java`

**Interfaces:**
- Consumes: `DefaultStatusHistoryCache` from Task 2

- [ ] **Step 1: Create test file**

Create `core/src/test/java/com/infenia/yukta/service/streaming/DefaultStatusHistoryCacheTest.java`:

```java
/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.service.streaming;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.WorkflowProgress;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DefaultStatusHistoryCacheTest {

  private DefaultStatusHistoryCache cache;

  @BeforeEach
  void setUp() {
    cache = new DefaultStatusHistoryCache(5);
  }

  @Test
  void constructor_validTtl_succeeds() {
    final DefaultStatusHistoryCache testCache = new DefaultStatusHistoryCache(5);
    assertThat(testCache).isNotNull();
  }

  @Test
  void constructor_ttlTooHigh_throwsException() {
    assertThatThrownBy(() -> new DefaultStatusHistoryCache(31))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid TTL")
        .hasMessageContaining("31")
        .hasMessageContaining("30");
  }

  @Test
  void constructor_ttlZero_throwsException() {
    assertThatThrownBy(() -> new DefaultStatusHistoryCache(0))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid TTL")
        .hasMessageContaining("0");
  }

  @Test
  void constructor_ttlNegative_throwsException() {
    assertThatThrownBy(() -> new DefaultStatusHistoryCache(-1))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Invalid TTL");
  }

  @Test
  void put_singleProgress_success() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            "exec1",
            "session1",
            "workflow1",
            "RUNNING",
            new ArrayList<>(),
            LocalDateTime.now(),
            null);

    cache.put("exec1", progress);

    final List<WorkflowProgress> history = cache.get("exec1");
    assertThat(history).hasSize(1).contains(progress);
  }

  @Test
  void put_multipleProgress_maintainsOrder() {
    final WorkflowProgress progress1 =
        new WorkflowProgress(
            "exec1",
            "session1",
            "workflow1",
            "INITIATED",
            new ArrayList<>(),
            LocalDateTime.now(),
            null);
    final WorkflowProgress progress2 =
        new WorkflowProgress(
            "exec1",
            "session1",
            "workflow1",
            "RUNNING",
            new ArrayList<>(),
            LocalDateTime.now(),
            null);

    cache.put("exec1", progress1);
    cache.put("exec1", progress2);

    final List<WorkflowProgress> history = cache.get("exec1");
    assertThat(history).hasSize(2);
    assertThat(history.get(0).status()).isEqualTo("INITIATED");
    assertThat(history.get(1).status()).isEqualTo("RUNNING");
  }

  @Test
  void get_executionNotFound_returnsEmptyList() {
    final List<WorkflowProgress> history = cache.get("nonexistent");
    assertThat(history).isEmpty();
  }

  @Test
  void get_returnsImmutableCopy() {
    final WorkflowProgress progress =
        new WorkflowProgress(
            "exec1",
            "session1",
            "workflow1",
            "RUNNING",
            new ArrayList<>(),
            LocalDateTime.now(),
            null);

    cache.put("exec1", progress);

    final List<WorkflowProgress> history1 = cache.get("exec1");
    final List<WorkflowProgress> history2 = cache.get("exec1");

    assertThat(history1).isNotSameAs(history2);
    assertThat(history1).isEqualTo(history2);
  }

  @Test
  void put_differentExecutions_isolated() {
    final WorkflowProgress progress1 =
        new WorkflowProgress(
            "exec1",
            "session1",
            "workflow1",
            "RUNNING",
            new ArrayList<>(),
            LocalDateTime.now(),
            null);
    final WorkflowProgress progress2 =
        new WorkflowProgress(
            "exec2",
            "session1",
            "workflow1",
            "RUNNING",
            new ArrayList<>(),
            LocalDateTime.now(),
            null);

    cache.put("exec1", progress1);
    cache.put("exec2", progress2);

    assertThat(cache.get("exec1")).hasSize(1).contains(progress1);
    assertThat(cache.get("exec2")).hasSize(1).contains(progress2);
  }

  @Test
  void put_concurrentAccess_threadsafe() throws InterruptedException {
    final int numThreads = 10;
    final int numUpdates = 100;
    final String executionId = "exec1";

    final List<Thread> threads = new ArrayList<>();
    for (int i = 0; i < numThreads; i++) {
      final int threadId = i;
      threads.add(
          new Thread(
              () -> {
                for (int j = 0; j < numUpdates; j++) {
                  final WorkflowProgress progress =
                      new WorkflowProgress(
                          executionId,
                          "session1",
                          "workflow1",
                          "RUNNING",
                          new ArrayList<>(),
                          LocalDateTime.now(),
                          null);
                  cache.put(executionId, progress);
                }
              }));
    }

    threads.forEach(Thread::start);
    threads.forEach(
        thread -> {
          try {
            thread.join();
          } catch (final InterruptedException e) {
            Thread.currentThread().interrupt();
          }
        });

    final List<WorkflowProgress> history = cache.get(executionId);
    assertThat(history).hasSize(numThreads * numUpdates);
  }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run:
```bash
./gradlew :core:test --tests "DefaultStatusHistoryCacheTest" -v
```

Expected: All tests pass

- [ ] **Step 3: Commit**

```bash
git add core/src/test/java/com/infenia/yukta/service/streaming/DefaultStatusHistoryCacheTest.java
git commit -m "test: Add DefaultStatusHistoryCache unit tests"
```

---

## Task 4: TaskTrackerService Interface Enhancement

**Files:**
- Modify: `core/src/main/java/com/infenia/yukta/service/orchestrator/tracker/TaskTrackerService.java`

**Interfaces:**
- Consumes: Current `TaskTrackerService` interface
- Produces: Updated interface with new overload `Flux<WorkflowProgress> getStatusStream(String executionId, boolean includeHistory)`

- [ ] **Step 1: Read current interface**

Run:
```bash
grep -A 10 "Flux<WorkflowProgress> getStatusStream" core/src/main/java/com/infenia/yukta/service/orchestrator/tracker/TaskTrackerService.java | head -20
```

Expected: Current method signature visible

- [ ] **Step 2: Add overload to interface**

Edit `core/src/main/java/com/infenia/yukta/service/orchestrator/tracker/TaskTrackerService.java`. Find the existing `getStatusStream(String executionId)` method and add the new overload right after it:

```java
  /**
   * Get a flux of status updates for an execution with optional history.
   *
   * @param executionId the execution identifier
   * @param includeHistory if true, emits cached history before live updates
   * @return the status flux, or empty if execution not found
   */
  Flux<WorkflowProgress> getStatusStream(
      @NotBlank String executionId, final boolean includeHistory);
```

- [ ] **Step 3: Verify edits**

Run:
```bash
grep -A 5 "getStatusStream" core/src/main/java/com/infenia/yukta/service/orchestrator/tracker/TaskTrackerService.java | tail -20
```

Expected: Both overloads visible

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/infenia/yukta/service/orchestrator/tracker/TaskTrackerService.java
git commit -m "refactor: Add getStatusStream overload with includeHistory flag"
```

---

## Task 5: DefaultTaskTrackerService Enhancement - Terminal Detection & Overload

**Files:**
- Modify: `core/src/main/java/com/infenia/yukta/service/orchestrator/tracker/DefaultTaskTrackerService.java`

**Interfaces:**
- Consumes: `StatusHistoryCache` from Task 2, updated `TaskTrackerService` interface from Task 4
- Produces: Enhanced `DefaultTaskTrackerService` with terminal detection method, cache integration, and `getStatusStream(executionId, includeHistory)` overload

- [ ] **Step 1: Add StatusHistoryCache dependency**

Edit `core/src/main/java/com/infenia/yukta/service/orchestrator/tracker/DefaultTaskTrackerService.java`. Add to the class constructor parameter (as a private final field):

Find the constructor and add parameter:
```java
private final StatusHistoryCache statusHistoryCache;
```

Then add it to the `@RequiredArgsConstructor` constructor injection (or manually inject if not using Lombok).

- [ ] **Step 2: Add terminal state detection method**

Add this method to the class body (find a good spot near other helper methods like `isTerminal()`):

```java
  /**
   * Check if a workflow progress represents a terminal state.
   *
   * <p>Both conditions must be true: status must be terminal AND endTime must be set.
   *
   * @param progress the workflow progress to check
   * @return true if terminal state detected, false otherwise
   */
  private boolean isWorkflowTerminal(final WorkflowProgress progress) {
    String status = progress.status();
    LocalDateTime endTime = progress.endTime();

    Set<String> terminalStatuses =
        Set.of("COMPLETED", "FAILED", "CANCELLED", "WORKFLOW_STOPPED");

    boolean isTerminalStatus = terminalStatuses.contains(status);
    boolean hasEndTime = endTime != null;

    if (isTerminalStatus && hasEndTime) {
      log.atDebug()
          .addKeyValue("status", status)
          .addKeyValue("endTime", endTime)
          .log("Workflow reached terminal state");
      return true;
    }

    return false;
  }
```

- [ ] **Step 3: Add new getStatusStream overload**

Find the existing `getStatusStream(String executionId)` method (around line 594). Add this overload right after it:

```java
  /**
   * Get a flux of status updates for an execution with optional history.
   *
   * <p>If includeHistory is true, emits cached historical updates first, then chains to live
   * updates. Stream auto-completes when a terminal state is detected. Each live update is cached
   * for future clients.
   *
   * @param executionId the execution identifier
   * @param includeHistory if true, emit cached history before live updates
   * @return the status flux (history + live), or empty if execution not found
   */
  @Override
  public Flux<WorkflowProgress> getStatusStream(
      @NotBlank final String executionId, final boolean includeHistory) {
    log.atDebug()
        .addKeyValue("executionId", executionId)
        .addKeyValue("includeHistory", includeHistory)
        .log("Getting status stream");

    final Sinks.Many<WorkflowProgress> sink = statusSinks.get(executionId);
    if (sink == null) {
      log.atWarn()
          .addKeyValue("executionId", executionId)
          .log("No status sink found for execution");
      return Flux.empty();
    }

    Flux<WorkflowProgress> statusFlux = sink.asFlux();

    if (includeHistory) {
      final List<WorkflowProgress> history = statusHistoryCache.get(executionId);
      log.atDebug()
          .addKeyValue("executionId", executionId)
          .addKeyValue("historySize", history.size())
          .log("Including history in stream");
      statusFlux = Flux.fromIterable(history).concatWith(statusFlux);
    }

    return statusFlux
        .doOnNext(progress -> statusHistoryCache.put(executionId, progress))
        .takeUntil(this::isWorkflowTerminal)
        .doOnComplete(
            () ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .log("Status stream completed"));
  }
```

- [ ] **Step 4: Update existing getStatusStream overload for backward compatibility**

Edit the existing `getStatusStream(String executionId)` method to delegate to the new overload:

```java
  @Override
  public Flux<WorkflowProgress> getStatusStream(@NotBlank final String executionId) {
    return getStatusStream(executionId, true);
  }
```

- [ ] **Step 5: Verify changes compile**

Run:
```bash
./gradlew :core:compileJava
```

Expected: No compile errors

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/infenia/yukta/service/orchestrator/tracker/DefaultTaskTrackerService.java
git commit -m "feat: Enhance TaskTrackerService with history and terminal detection"
```

---

## Task 6: ControlBusGateway Enhancement

**Files:**
- Modify: `core/src/main/java/com/infenia/yukta/service/control/gateway/ControlBusGateway.java`

**Interfaces:**
- Consumes: Updated `TaskTrackerService` from Task 4
- Produces: Updated `ControlBusGateway` interface with new overload

- [ ] **Step 1: Find existing watchExecution method**

Run:
```bash
grep -n "watchExecution" core/src/main/java/com/infenia/yukta/service/control/gateway/ControlBusGateway.java
```

Expected: Line number visible

- [ ] **Step 2: Add new overload to interface**

Edit `core/src/main/java/com/infenia/yukta/service/control/gateway/ControlBusGateway.java`. Find the existing `watchExecution(String executionId)` method and add this overload right after it:

```java
  /**
   * Watch a specific execution for status updates with optional history.
   *
   * <p>If includeHistory is true, the returned flux emits cached historical updates before live
   * updates. Stream auto-completes when the workflow reaches a terminal state.
   *
   * @param executionId the execution identifier
   * @param includeHistory if true, emit cached history before live updates
   * @return a flux of workflow progress updates
   */
  Flux<WorkflowProgress> watchExecution(String executionId, boolean includeHistory);
```

- [ ] **Step 3: Verify edits**

Run:
```bash
grep -A 5 "watchExecution" core/src/main/java/com/infenia/yukta/service/control/gateway/ControlBusGateway.java | tail -20
```

Expected: Both overloads visible

- [ ] **Step 4: Commit**

```bash
git add core/src/main/java/com/infenia/yukta/service/control/gateway/ControlBusGateway.java
git commit -m "refactor: Add watchExecution overload with includeHistory flag"
```

---

## Task 7: DefaultControlBusGateway Implementation

**Files:**
- Modify: `core/src/main/java/com/infenia/yukta/service/control/gateway/DefaultControlBusGateway.java`

**Interfaces:**
- Consumes: Updated `ControlBusGateway` from Task 6, updated `TaskTrackerService` from Task 4
- Produces: Implemented `watchExecution(executionId, includeHistory)` overload

- [ ] **Step 1: Find existing watchExecution implementation**

Run:
```bash
grep -n "public Flux<WorkflowProgress> watchExecution" core/src/main/java/com/infenia/yukta/service/control/gateway/DefaultControlBusGateway.java
```

Expected: Line number visible

- [ ] **Step 2: Read existing implementation**

Run:
```bash
grep -A 25 "public Flux<WorkflowProgress> watchExecution" core/src/main/java/com/infenia/yukta/service/control/gateway/DefaultControlBusGateway.java | head -30
```

Expected: Current implementation visible

- [ ] **Step 3: Add new overload implementation**

Edit `core/src/main/java/com/infenia/yukta/service/control/gateway/DefaultControlBusGateway.java`. Add this new overload after the existing `watchExecution()` method:

```java
  @Override
  public Flux<WorkflowProgress> watchExecution(
      final String executionId, final boolean includeHistory) {
    log.atDebug()
        .addKeyValue("executionId", executionId)
        .addKeyValue("includeHistory", includeHistory)
        .log("Starting to watch workflow execution");
    return taskTracker
        .getStatusStream(executionId, includeHistory)
        .doOnSubscribe(
            _ ->
                log.atDebug()
                    .addKeyValue("executionId", executionId)
                    .log("Subscribed to execution status stream"))
        .doOnNext(
            progress ->
                log.atTrace()
                    .addKeyValue("executionId", executionId)
                    .addKeyValue("status", progress.status())
                    .addKeyValue("taskCount", progress.tasks().size())
                    .log("Received execution progress update"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue("executionId", executionId)
                    .log("Stream error"))
        .doOnComplete(
            () ->
                log.atInfo()
                    .addKeyValue("executionId", executionId)
                    .log("Stream completed"));
  }
```

- [ ] **Step 4: Update existing watchExecution to delegate**

Edit the existing `watchExecution(String executionId)` method to delegate to the new overload:

```java
  @Override
  public Flux<WorkflowProgress> watchExecution(final String executionId) {
    return watchExecution(executionId, true);
  }
```

- [ ] **Step 5: Verify changes compile**

Run:
```bash
./gradlew :core:compileJava
```

Expected: No compile errors

- [ ] **Step 6: Commit**

```bash
git add core/src/main/java/com/infenia/yukta/service/control/gateway/DefaultControlBusGateway.java
git commit -m "feat: Implement watchExecution with history support"
```

---

## Task 8: WorkflowController Enhancement

**Files:**
- Modify: `web/src/main/java/com/infenia/yukta/controller/WorkflowController.java`

**Interfaces:**
- Consumes: Updated `ControlBusGateway` from Task 6
- Produces: Updated `streamWorkflowStatus()` method with `includeHistory` query parameter

- [ ] **Step 1: Read current streamWorkflowStatus method**

Run:
```bash
grep -A 20 "public Flux<ServerSentEvent<WorkflowProgress>> streamWorkflowStatus" web/src/main/java/com/infenia/yukta/controller/WorkflowController.java
```

Expected: Current implementation visible

- [ ] **Step 2: Update method signature and implementation**

Edit `web/src/main/java/com/infenia/yukta/controller/WorkflowController.java`. Find the `streamWorkflowStatus()` method and update it:

**Old signature:**
```java
public Flux<ServerSentEvent<WorkflowProgress>> streamWorkflowStatus(
    @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
    @Parameter(description = "Execution ID") @PathVariable final String executionId)
```

**New signature and implementation:**
```java
public Flux<ServerSentEvent<WorkflowProgress>> streamWorkflowStatus(
    @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
    @Parameter(description = "Execution ID") @PathVariable final String executionId,
    @Parameter(description = "Include historical status updates (last N minutes)")
    @RequestParam(defaultValue = "true")
    final boolean includeHistory)
```

**Update the method body** to pass `includeHistory`:

```java
public Flux<ServerSentEvent<WorkflowProgress>> streamWorkflowStatus(
    @Parameter(description = SESSION_ID_PARAM) @PathVariable final String sessionId,
    @Parameter(description = "Execution ID") @PathVariable final String executionId,
    @Parameter(description = "Include historical status updates (last N minutes)")
    @RequestParam(defaultValue = "true")
    final boolean includeHistory) {
  log.atInfo()
      .log(
          "streamWorkflowStatus: sessionId={}, executionId={}, includeHistory={}",
          sessionId,
          executionId,
          includeHistory);
  return controlBus
      .watchExecution(executionId, includeHistory)
      .doOnNext(
          _ ->
              log.atDebug()
                  .log(
                      "streamWorkflowStatus progress received: sessionId={}, executionId={}",
                      sessionId,
                      executionId))
      .map(progress -> ServerSentEvent.<WorkflowProgress>builder().data(progress).build())
      .doOnError(
          error ->
              log.atError()
                  .log(
                      "streamWorkflowStatus error occurred: sessionId={}, executionId={},"
                          + " error={}",
                      sessionId,
                      executionId,
                      error.getMessage()))
      .doOnComplete(
          () ->
              log.atInfo()
                  .log(
                      "streamWorkflowStatus stream completed: sessionId={}, executionId={}",
                      sessionId,
                      executionId));
}
```

- [ ] **Step 3: Verify changes compile**

Run:
```bash
./gradlew :web:compileJava
```

Expected: No compile errors

- [ ] **Step 4: Commit**

```bash
git add web/src/main/java/com/infenia/yukta/controller/WorkflowController.java
git commit -m "feat: Add includeHistory query parameter to streamWorkflowStatus"
```

---

## Task 9: Application Configuration

**Files:**
- Modify: `boot/src/main/resources/application.yaml`

**Interfaces:**
- Produces: Configuration properties for status history cache

- [ ] **Step 1: Read current configuration**

Run:
```bash
grep -A 20 "yukta:" boot/src/main/resources/application.yaml | head -30
```

Expected: Current yukta configuration visible

- [ ] **Step 2: Add streaming configuration**

Edit `boot/src/main/resources/application.yaml`. Find the `yukta:` section and add the streaming configuration:

```yaml
yukta:
  streaming:
    status-history-ttl-minutes: 5
```

Make sure it's properly indented under the `yukta:` section.

- [ ] **Step 3: Verify syntax**

Run:
```bash
./gradlew bootRun 2>&1 | head -50
```

Expected: Application starts without YAML parsing errors (can stop with Ctrl+C after verification)

- [ ] **Step 4: Commit**

```bash
git add boot/src/main/resources/application.yaml
git commit -m "config: Add status-history-ttl-minutes configuration"
```

---

## Task 10: Unit Tests - TaskTrackerService Streaming

**Files:**
- Create: `core/src/test/java/com/infenia/yukta/service/orchestrator/tracker/DefaultTaskTrackerServiceStreamingTest.java`

**Interfaces:**
- Consumes: `DefaultTaskTrackerService` from Task 5, `StatusHistoryCache` from Task 2

- [ ] **Step 1: Create test file**

Create `core/src/test/java/com/infenia/yukta/service/orchestrator/tracker/DefaultTaskTrackerServiceStreamingTest.java`:

```java
/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.service.orchestrator.tracker;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.service.streaming.StatusHistoryCache;
import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.WorkflowProgress;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;
import reactor.util.concurrent.Queues;

@ExtendWith(MockitoExtension.class)
class DefaultTaskTrackerServiceStreamingTest {

  @Mock private StatusHistoryCache statusHistoryCache;

  private Sinks.Many<WorkflowProgress> statusSink;
  private String executionId;

  @BeforeEach
  void setUp() {
    executionId = "exec-test-123";
    statusSink = Sinks.many().multicast().onBackpressureBuffer(Queues.SMALL_BUFFER_SIZE, false);
  }

  private WorkflowProgress createProgress(String status, LocalDateTime endTime) {
    return new WorkflowProgress(
        executionId, "session1", "workflow1", status, new ArrayList<>(), LocalDateTime.now(),
        endTime);
  }

  @Test
  void getStatusStream_includeHistoryTrue_emitsHistoryThenLive() {
    final WorkflowProgress history1 = createProgress("INITIATED", null);
    final WorkflowProgress history2 = createProgress("RUNNING", null);
    final WorkflowProgress live = createProgress("RUNNING", null);

    when(statusHistoryCache.get(executionId)).thenReturn(List.of(history1, history2));

    Flux<WorkflowProgress> result =
        Flux.fromIterable(List.of(history1, history2))
            .concatWith(statusSink.asFlux())
            .takeUntil(
                p ->
                    (p.status().equals("COMPLETED")
                            || p.status().equals("FAILED")
                            || p.status().equals("CANCELLED")
                            || p.status().equals("WORKFLOW_STOPPED"))
                        && p.endTime() != null);

    StepVerifier.create(result)
        .expectNext(history1, history2)
        .then(() -> statusSink.tryEmitNext(live))
        .expectNext(live)
        .then(
            () -> {
              final WorkflowProgress terminal =
                  createProgress("COMPLETED", LocalDateTime.now());
              statusSink.tryEmitNext(terminal);
            })
        .expectNext(createProgress("COMPLETED", LocalDateTime.now()))
        .expectComplete()
        .verify();
  }

  @Test
  void getStatusStream_includeHistoryFalse_skipsHistory() {
    final WorkflowProgress history = createProgress("INITIATED", null);
    final WorkflowProgress live = createProgress("RUNNING", null);

    when(statusHistoryCache.get(executionId)).thenReturn(List.of(history));

    Flux<WorkflowProgress> result =
        statusSink
            .asFlux()
            .takeUntil(
                p ->
                    (p.status().equals("COMPLETED")
                            || p.status().equals("FAILED")
                            || p.status().equals("CANCELLED")
                            || p.status().equals("WORKFLOW_STOPPED"))
                        && p.endTime() != null);

    StepVerifier.create(result)
        .then(() -> statusSink.tryEmitNext(live))
        .expectNext(live)
        .then(
            () -> {
              final WorkflowProgress terminal =
                  createProgress("COMPLETED", LocalDateTime.now());
              statusSink.tryEmitNext(terminal);
            })
        .expectNext(createProgress("COMPLETED", LocalDateTime.now()))
        .expectComplete()
        .verify();
  }

  @Test
  void getStatusStream_terminalState_completesStream() {
    final WorkflowProgress running = createProgress("RUNNING", null);
    final WorkflowProgress completed =
        createProgress("COMPLETED", LocalDateTime.now());

    when(statusHistoryCache.get(executionId)).thenReturn(List.of());

    Flux<WorkflowProgress> result =
        statusSink
            .asFlux()
            .takeUntil(
                p ->
                    (p.status().equals("COMPLETED")
                            || p.status().equals("FAILED")
                            || p.status().equals("CANCELLED")
                            || p.status().equals("WORKFLOW_STOPPED"))
                        && p.endTime() != null);

    StepVerifier.create(result)
        .then(() -> statusSink.tryEmitNext(running))
        .expectNext(running)
        .then(() -> statusSink.tryEmitNext(completed))
        .expectNext(completed)
        .expectComplete()
        .verify();
  }

  @Test
  void getStatusStream_terminalStatusNoEndTime_doesNotComplete() {
    final WorkflowProgress running = createProgress("RUNNING", null);
    final WorkflowProgress completed = createProgress("COMPLETED", null);

    when(statusHistoryCache.get(executionId)).thenReturn(List.of());

    Flux<WorkflowProgress> result =
        statusSink
            .asFlux()
            .take(2)
            .takeUntil(
                p ->
                    (p.status().equals("COMPLETED")
                            || p.status().equals("FAILED")
                            || p.status().equals("CANCELLED")
                            || p.status().equals("WORKFLOW_STOPPED"))
                        && p.endTime() != null);

    StepVerifier.create(result)
        .then(() -> statusSink.tryEmitNext(running))
        .expectNext(running)
        .then(() -> statusSink.tryEmitNext(completed))
        .expectNext(completed)
        .expectComplete()
        .verify();
  }

  @Test
  void getStatusStream_emitsCancelled_completesStream() {
    final WorkflowProgress cancelled =
        createProgress("CANCELLED", LocalDateTime.now());

    when(statusHistoryCache.get(executionId)).thenReturn(List.of());

    Flux<WorkflowProgress> result =
        statusSink
            .asFlux()
            .takeUntil(
                p ->
                    (p.status().equals("COMPLETED")
                            || p.status().equals("FAILED")
                            || p.status().equals("CANCELLED")
                            || p.status().equals("WORKFLOW_STOPPED"))
                        && p.endTime() != null);

    StepVerifier.create(result)
        .then(() -> statusSink.tryEmitNext(cancelled))
        .expectNext(cancelled)
        .expectComplete()
        .verify();
  }

  @Test
  void getStatusStream_cachesPutCalledOnLiveEmit() {
    final WorkflowProgress progress = createProgress("RUNNING", null);

    when(statusHistoryCache.get(executionId)).thenReturn(List.of());

    Flux<WorkflowProgress> result =
        statusSink
            .asFlux()
            .take(1)
            .doOnNext(p -> statusHistoryCache.put(executionId, p));

    StepVerifier.create(result)
        .then(() -> statusSink.tryEmitNext(progress))
        .expectNext(progress)
        .expectComplete()
        .verify();

    verify(statusHistoryCache).put(eq(executionId), eq(progress));
  }
}
```

- [ ] **Step 2: Run tests to verify they pass**

Run:
```bash
./gradlew :core:test --tests "DefaultTaskTrackerServiceStreamingTest" -v
```

Expected: All tests pass

- [ ] **Step 3: Commit**

```bash
git add core/src/test/java/com/infenia/yukta/service/orchestrator/tracker/DefaultTaskTrackerServiceStreamingTest.java
git commit -m "test: Add streaming-specific tests for TaskTrackerService"
```

---

## Task 11: Run Full Test Suite & Format

**Files:**
- No new files

**Interfaces:**
- Consumes: All implementations from Tasks 1-10

- [ ] **Step 1: Run all tests in core module**

Run:
```bash
./gradlew :core:test -v
```

Expected: All tests pass, no failures

- [ ] **Step 2: Run all tests in web module**

Run:
```bash
./gradlew :web:test -v
```

Expected: All tests pass, no failures

- [ ] **Step 3: Apply spotless formatting**

Run:
```bash
./gradlew spotlessApply
```

Expected: Formatting applied (may modify files)

- [ ] **Step 4: Run quality checks**

Run:
```bash
./gradlew check
```

Expected: All checks pass (Checkstyle, PMD, SpotBugs, JaCoCo)

- [ ] **Step 5: Verify no unexpected changes**

Run:
```bash
git status
```

Expected: Only expected files show as modified (formatting changes, no logic changes)

- [ ] **Step 6: Commit formatting**

```bash
git add -A
git commit -m "style: Apply spotless formatting"
```

---

## Task 12: Manual Verification (Verify Skill)

**Files:**
- No changes

**Interfaces:**
- Consumes: Complete implementation from all previous tasks

- [ ] **Step 1: Start the application**

Run:
```bash
./gradlew bootRun
```

Wait for startup to complete (look for "Started Application" message). Leave running.

- [ ] **Step 2: Start a workflow in another terminal**

Run:
```bash
curl -X POST http://localhost:8080/api/workflow/start \
  -H "Content-Type: application/json" \
  -d '{"sessionId":"test-session","workflowId":"test-workflow"}'
```

Expected: Response includes `executionId` (save this value)

- [ ] **Step 3: Wait for status updates**

Wait 2-3 seconds to allow status updates to accumulate in the cache.

- [ ] **Step 4: Test stream with history (includeHistory=true)**

Run (replace {executionId} with actual value):
```bash
curl -N "http://localhost:8080/api/workflow/test-session/status/{executionId}/stream?includeHistory=true" \
  2>/dev/null | head -20
```

Expected: See multiple status updates immediately (history + live)

- [ ] **Step 5: Test stream without history (includeHistory=false)**

Run:
```bash
curl -N "http://localhost:8080/api/workflow/test-session/status/{executionId}/stream?includeHistory=false" \
  2>/dev/null | head -20
```

Expected: See only live updates (no accumulated history)

- [ ] **Step 6: Monitor stream completion**

Connect to stream and wait for workflow to complete:
```bash
curl -N "http://localhost:8080/api/workflow/test-session/status/{executionId}/stream" \
  2>/dev/null
```

Expected: Stream closes automatically when workflow reaches terminal state (COMPLETED, FAILED, etc. with endTime set)

- [ ] **Step 7: Stop the application**

Press Ctrl+C in the bootRun terminal.

---

## Task 13: Final Commit & Summary

**Files:**
- No new files

- [ ] **Step 1: Verify branch state**

Run:
```bash
git log --oneline | head -15
```

Expected: All 8 commits visible (1 spec + 7 implementation commits)

- [ ] **Step 2: Review overall changes**

Run:
```bash
git diff main..HEAD --stat
```

Expected: Summary shows files changed, lines added/removed

- [ ] **Step 3: Final verification**

Run:
```bash
./gradlew check
```

Expected: All checks pass

---

## Success Criteria

✅ All unit tests pass (cache, streaming, terminal detection)  
✅ All existing tests still pass (no regressions)  
✅ Code formatting applied (spotless)  
✅ Quality gates pass (Checkstyle, PMD, SpotBugs, JaCoCo)  
✅ Configuration validated at startup  
✅ Manual verification: stream emits history on late connect  
✅ Manual verification: stream auto-completes on terminal state  
✅ Manual verification: `?includeHistory=false` skips history  

---

## Notes for Implementation

- **Task Dependencies**: Tasks 1-2 (cache) can be done in parallel with Tasks 4-7 (interface/implementation changes) since they're independent
- **Testing Order**: Do Tasks 3 & 10 (tests) after implementations so you can verify the implementations work
- **Configuration**: Task 9 (config) can be done last, or early if you want to test with real config
- **Spotless**: Always run `spotlessApply` before each commit to catch formatting issues early
- **Backward Compatibility**: Ensure old code calling `getStatusStream(executionId)` still works by delegating to new overload with `includeHistory=true`
