# WorkflowOrchestrator Comprehensive Refactoring Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Refactor `WorkflowOrchestrator.java` to improve maintainability and testability by extracting cross-cutting concerns into four fluent internal builder classes while preserving all functionality and performance characteristics.

**Architecture:** Introduce four internal builder classes (`StreamBuilder`, `ExecutionContextBuilder`, `HeartbeatBuilder`, `ResourceManagementBuilder`) that encapsulate construction logic for streams, context, heartbeats, and resource cleanup. `WorkflowOrchestrator` becomes a thin orchestration layer that composes these builders. Node assemblers are dramatically simplified (5 lines each) and all three plugin types (Trigger, Processor, Terminal) use a unified `StreamBuilder` pattern.

**Tech Stack:** Java 21, Project Reactor (Mono/Flux), Spring Framework, JUnit 5, Mockito, Reactor Test

---

## Task 1: Create ExecutionContextBuilder

**Files:**
- Create: `yukta-core/src/main/java/com/infenia/yukta/service/orchestrator/ExecutionContextBuilder.java`
- Test: `yukta-core/src/test/java/com/infenia/yukta/service/orchestrator/ExecutionContextBuilderTest.java`

**Step 1: Write the test for ExecutionContextBuilder**

```java
package com.infenia.yukta.service.orchestrator;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

class ExecutionContextBuilderTest {

  @Test
  void testBuildContextWithAllFields() {
    ExecutionContextBuilder builder = new ExecutionContextBuilder();
    Context context = builder
        .sessionId("session-123")
        .workflowId("workflow-456")
        .executionId("exec-789")
        .nodeId("node-001")
        .payload(Map.of("key", "value"))
        .build();

    assertEquals("session-123", context.get("sessionId"));
    assertEquals("workflow-456", context.get("workflowId"));
    assertEquals("exec-789", context.get("executionId"));
    assertEquals("node-001", context.get("nodeId"));
    assertEquals(Map.of("key", "value"), context.get("payload"));
  }

  @Test
  void testApplyContextToMono() {
    ExecutionContextBuilder builder = new ExecutionContextBuilder()
        .sessionId("session-123")
        .workflowId("workflow-456")
        .executionId("exec-789")
        .nodeId("node-001")
        .payload(Map.of("data", "test"));

    Mono<String> mono = Mono.deferContextual(ctx ->
        Mono.just((String) ctx.get("sessionId")));

    StepVerifier.create(builder.applyContextTo(mono))
        .expectNext("session-123")
        .verifyComplete();
  }

  @Test
  void testBuildContextIsImmutable() {
    ExecutionContextBuilder builder = new ExecutionContextBuilder()
        .sessionId("session-1");
    Context ctx1 = builder.build();

    builder.sessionId("session-2");
    Context ctx2 = builder.build();

    assertEquals("session-1", ctx1.get("sessionId"));
    assertEquals("session-2", ctx2.get("sessionId"));
  }

  @Test
  void testContextBuilderConstants() {
    assertEquals("sessionId", ExecutionContextBuilder.CTX_SESSION_ID);
    assertEquals("workflowId", ExecutionContextBuilder.CTX_WORKFLOW_ID);
    assertEquals("executionId", ExecutionContextBuilder.CTX_EXECUTION_ID);
    assertEquals("nodeId", ExecutionContextBuilder.CTX_NODE_ID);
    assertEquals("payload", ExecutionContextBuilder.CTX_PAYLOAD);
  }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew :yukta-core:test --tests ExecutionContextBuilderTest -v
```

Expected: FAIL (class does not exist)

**Step 3: Write minimal ExecutionContextBuilder implementation**

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
package com.infenia.yukta.service.orchestrator;

import jakarta.annotation.Nullable;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * Fluent builder for constructing and applying execution context to reactive streams.
 * Centralizes context key management and provides a clean API for context building.
 */
public class ExecutionContextBuilder {

  public static final String CTX_SESSION_ID = "sessionId";
  public static final String CTX_WORKFLOW_ID = "workflowId";
  public static final String CTX_EXECUTION_ID = "executionId";
  public static final String CTX_NODE_ID = "nodeId";
  public static final String CTX_PAYLOAD = "payload";

  @Nullable private String sessionId;
  @Nullable private String workflowId;
  @Nullable private String executionId;
  @Nullable private String nodeId;
  @Nullable private Map<String, Object> payload;

  /**
   * Sets the session ID.
   *
   * @param sessionId the session ID
   * @return this builder
   */
  public ExecutionContextBuilder sessionId(final String sessionId) {
    this.sessionId = sessionId;
    return this;
  }

  /**
   * Sets the workflow ID.
   *
   * @param workflowId the workflow ID
   * @return this builder
   */
  public ExecutionContextBuilder workflowId(final String workflowId) {
    this.workflowId = workflowId;
    return this;
  }

  /**
   * Sets the execution ID.
   *
   * @param executionId the execution ID
   * @return this builder
   */
  public ExecutionContextBuilder executionId(final String executionId) {
    this.executionId = executionId;
    return this;
  }

  /**
   * Sets the node ID.
   *
   * @param nodeId the node ID
   * @return this builder
   */
  public ExecutionContextBuilder nodeId(final String nodeId) {
    this.nodeId = nodeId;
    return this;
  }

  /**
   * Sets the payload.
   *
   * @param payload the execution payload
   * @return this builder
   */
  public ExecutionContextBuilder payload(final Map<String, Object> payload) {
    this.payload = payload;
    return this;
  }

  /**
   * Builds the context from configured values.
   *
   * @return the Context object
   */
  public Context build() {
    Context ctx = Context.empty();
    if (sessionId != null) {
      ctx = ctx.put(CTX_SESSION_ID, sessionId);
    }
    if (workflowId != null) {
      ctx = ctx.put(CTX_WORKFLOW_ID, workflowId);
    }
    if (executionId != null) {
      ctx = ctx.put(CTX_EXECUTION_ID, executionId);
    }
    if (nodeId != null) {
      ctx = ctx.put(CTX_NODE_ID, nodeId);
    }
    if (payload != null) {
      ctx = ctx.put(CTX_PAYLOAD, payload);
    }
    return ctx;
  }

  /**
   * Applies the built context to a Mono stream.
   *
   * @param mono the Mono to contextualize
   * @param <T> the type
   * @return the contextualized Mono
   */
  public <T> Mono<T> applyContextTo(final Mono<T> mono) {
    return mono.contextWrite(build());
  }

  /**
   * Applies the built context to a Flux stream.
   *
   * @param flux the Flux to contextualize
   * @param <T> the type
   * @return the contextualized Flux
   */
  public <T> Flux<T> applyContextTo(final Flux<T> flux) {
    return flux.contextWrite(build());
  }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew :yukta-core:test --tests ExecutionContextBuilderTest -v
```

Expected: PASS (all 4 tests pass)

**Step 5: Commit**

```bash
git add yukta-core/src/main/java/com/infenia/yukta/service/orchestrator/ExecutionContextBuilder.java
git add yukta-core/src/test/java/com/infenia/yukta/service/orchestrator/ExecutionContextBuilderTest.java
git commit -m "feat: add ExecutionContextBuilder for centralized context management"
```

---

## Task 2: Create StreamBuilder

**Files:**
- Create: `yukta-core/src/main/java/com/infenia/yukta/service/orchestrator/StreamBuilder.java`
- Test: `yukta-core/src/test/java/com/infenia/yukta/service/orchestrator/StreamBuilderTest.java`
- Modify: `yukta-core/src/main/java/com/infenia/yukta/service/WorkflowOrchestrator.java` (remove helper methods, will be used later)

**Step 1: Write the test for StreamBuilder**

```java
package com.infenia.yukta.service.orchestrator;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.plugin.core.WorkflowPlugin;
import com.infenia.yukta.plugin.gateway.ControlBusGateway;
import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class StreamBuilderTest {

  @Mock private Node mockNode;
  @Mock private WorkflowPlugin mockPlugin;
  @Mock private ControlBusGateway mockControlBusGateway;
  @Mock private TaskTrackerService mockTracker;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(mockNode.nodeId()).thenReturn("node-001");
    when(mockControlBusGateway.emit(any())).thenReturn(Mono.empty());
  }

  @Test
  void testStreamBuilderWithTimeout() {
    Flux<Message<?>> sourceStream = Flux.just(
        DefaultMessage.create(Map.of("data", "value"), "test-payload"));

    StreamBuilder builder = new StreamBuilder(
        mockNode,
        mockPlugin,
        Duration.ofSeconds(5),
        1024,
        mockTracker,
        mockControlBusGateway);

    Flux<Message<?>> built = builder
        .withSource(sourceStream)
        .withTimeout()
        .build();

    StepVerifier.create(built)
        .expectNextMatches(msg -> msg.getPayload().equals("test-payload"))
        .verifyComplete();
  }

  @Test
  void testStreamBuilderWithTaskTracking() {
    Flux<Message<?>> sourceStream = Flux.just(
        DefaultMessage.create(Map.of(), "test"));

    StreamBuilder builder = new StreamBuilder(
        mockNode,
        mockPlugin,
        Duration.ofSeconds(5),
        1024,
        mockTracker,
        mockControlBusGateway);

    Flux<Message<?>> built = builder
        .withSource(sourceStream)
        .withTaskTracking("exec-001", "session-001")
        .build();

    StepVerifier.create(built)
        .expectNextCount(1)
        .verifyComplete();

    verify(mockTracker, times(1)).emitTaskStatusEvent(
        "exec-001", "node-001", "default", "RUNNING", Collections.emptyMap());
    verify(mockTracker, times(1)).emitTaskStatusEvent(
        "exec-001", "node-001", "default", "SUCCESS", Collections.emptyMap());
  }

  @Test
  void testStreamBuilderChaining() {
    Flux<Message<?>> sourceStream = Flux.just(
        DefaultMessage.create(Map.of(), "test"));

    StreamBuilder builder = new StreamBuilder(
        mockNode,
        mockPlugin,
        Duration.ofSeconds(5),
        1024,
        mockTracker,
        mockControlBusGateway);

    Flux<Message<?>> built = builder
        .withSource(sourceStream)
        .withTimeout()
        .withTaskTracking("exec-001", "session-001")
        .build();

    assertNotNull(built);
    StepVerifier.create(built).expectNextCount(1).verifyComplete();
  }

  @Test
  void testStreamBuilderErrorHandling() {
    Flux<Message<?>> sourceStream = Flux.error(new RuntimeException("Test error"));

    StreamBuilder builder = new StreamBuilder(
        mockNode,
        mockPlugin,
        Duration.ofSeconds(5),
        1024,
        mockTracker,
        mockControlBusGateway);

    Flux<Message<?>> built = builder
        .withSource(sourceStream)
        .withErrorHandling("exec-001")
        .build();

    StepVerifier.create(built)
        .expectError(RuntimeException.class)
        .verify();

    verify(mockTracker).emitTaskStatusEvent(
        "exec-001", "node-001", "default", "FAILURE", Collections.emptyMap());
  }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew :yukta-core:test --tests StreamBuilderTest -v
```

Expected: FAIL (class does not exist)

**Step 3: Write minimal StreamBuilder implementation**

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
package com.infenia.yukta.service.orchestrator;

import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.plugin.core.WorkflowPlugin;
import com.infenia.yukta.plugin.gateway.ControlBusGateway;
import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.message.control.ControlError;
import java.time.Duration;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.TimeoutException;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/**
 * Fluent builder for constructing reactive streams with consistent error handling,
 * timeout wrapping, task tracking, and context application across all plugin types.
 */
public class StreamBuilder {

  private static final String DEFAULT_TASK_ID = "default";
  private static final String STATUS_RUNNING = "RUNNING";
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_FAILURE = "FAILURE";

  private final Node node;
  private final WorkflowPlugin plugin;
  private final Duration timeout;
  private final int bufferSize;
  private final TaskTrackerService tracker;
  private final ControlBusGateway controlBusGateway;

  private Flux<Message<?>> sourceStream;
  private boolean withTimeout;
  private boolean withTaskTracking;
  private String executionId;
  private String sessionId;
  private boolean withErrorHandling;

  /**
   * Constructs a StreamBuilder.
   *
   * @param node the workflow node
   * @param plugin the workflow plugin
   * @param timeout the node timeout
   * @param bufferSize the buffer size
   * @param tracker the task tracker service
   * @param controlBusGateway the control bus gateway
   */
  public StreamBuilder(
      final Node node,
      final WorkflowPlugin plugin,
      final Duration timeout,
      final int bufferSize,
      final TaskTrackerService tracker,
      final ControlBusGateway controlBusGateway) {
    this.node = node;
    this.plugin = plugin;
    this.timeout = timeout;
    this.bufferSize = bufferSize;
    this.tracker = tracker;
    this.controlBusGateway = controlBusGateway;
  }

  /**
   * Sets the source stream.
   *
   * @param stream the source stream
   * @return this builder
   */
  public StreamBuilder withSource(final Flux<Message<?>> stream) {
    this.sourceStream = stream;
    return this;
  }

  /**
   * Enables timeout handling.
   *
   * @return this builder
   */
  public StreamBuilder withTimeout() {
    this.withTimeout = true;
    return this;
  }

  /**
   * Enables task tracking.
   *
   * @param executionId the execution ID
   * @param sessionId the session ID
   * @return this builder
   */
  public StreamBuilder withTaskTracking(final String executionId, final String sessionId) {
    this.withTaskTracking = true;
    this.executionId = executionId;
    this.sessionId = sessionId;
    return this;
  }

  /**
   * Enables error handling.
   *
   * @param executionId the execution ID
   * @return this builder
   */
  public StreamBuilder withErrorHandling(final String executionId) {
    this.withErrorHandling = true;
    this.executionId = executionId;
    return this;
  }

  /**
   * Builds the final stream with all configured transformations.
   *
   * @return the constructed Flux
   */
  public Flux<Message<?>> build() {
    Flux<Message<?>> stream = sourceStream;

    if (withTimeout) {
      stream = stream.flatMap(
          msg -> Mono.<Message<?>>just(msg)
              .timeout(timeout)
              .onErrorMap(TimeoutException.class, e -> e),
          bufferSize);
    }

    if (withTaskTracking) {
      stream = stream
          .doOnSubscribe(s -> tracker.emitTaskStatusEvent(
              executionId, node.nodeId(), DEFAULT_TASK_ID, STATUS_RUNNING, Collections.emptyMap()))
          .doOnComplete(() -> tracker.emitTaskStatusEvent(
              executionId, node.nodeId(), DEFAULT_TASK_ID, STATUS_SUCCESS, Collections.emptyMap()));
    }

    if (withErrorHandling) {
      stream = stream.doOnError(e -> {
        tracker.emitTaskStatusEvent(
            executionId, node.nodeId(), DEFAULT_TASK_ID, STATUS_FAILURE, Collections.emptyMap());
        controlBusGateway
            .emit(
                DefaultMessage.create(
                        null,
                        new ControlError(
                            node.nodeId(), executionId, "Node Failure", e.getMessage()))
                    .withSourceNodeId(node.nodeId())
                    .withControl(true)
                    .withPriority(10))
            .subscribe();
      });
    }

    return stream;
  }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew :yukta-core:test --tests StreamBuilderTest -v
```

Expected: PASS (all 5 tests pass)

**Step 5: Commit**

```bash
git add yukta-core/src/main/java/com/infenia/yukta/service/orchestrator/StreamBuilder.java
git add yukta-core/src/test/java/com/infenia/yukta/service/orchestrator/StreamBuilderTest.java
git commit -m "feat: add StreamBuilder for unified stream construction across plugin types"
```

---

## Task 3: Create HeartbeatBuilder

**Files:**
- Create: `yukta-core/src/main/java/com/infenia/yukta/service/orchestrator/HeartbeatBuilder.java`
- Test: `yukta-core/src/test/java/com/infenia/yukta/service/orchestrator/HeartbeatBuilderTest.java`

**Step 1: Write the test for HeartbeatBuilder**

```java
package com.infenia.yukta.service.orchestrator;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.infenia.yukta.plugin.gateway.ControlBusGateway;
import com.infenia.yukta.plugin.message.control.ControlHeartbeat;
import com.infenia.yukta.plugin.message.control.ControlStatistics;
import java.time.Duration;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

class HeartbeatBuilderTest {

  @Mock private ControlBusGateway mockControlBusGateway;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(mockControlBusGateway.emit(any())).thenReturn(Mono.empty());
  }

  @Test
  void testHeartbeatBuilderCreateDisposables() {
    HeartbeatBuilder builder = new HeartbeatBuilder(
        mockControlBusGateway,
        Duration.ofMillis(500),
        Schedulers.boundedElastic());

    List<Disposable> disposables = builder
        .forNodes(List.of("node-1", "node-2", "node-3"))
        .withHeartbeatInterval(Duration.ofMillis(500))
        .withStatisticsInterval(Duration.ofSeconds(1))
        .build();

    assert disposables.size() > 0;
    disposables.forEach(Disposable::dispose);
  }

  @Test
  void testHeartbeatBuilderDefaultStatisticsInterval() {
    HeartbeatBuilder builder = new HeartbeatBuilder(
        mockControlBusGateway,
        Duration.ofMillis(500),
        Schedulers.boundedElastic());

    List<Disposable> disposables = builder
        .forNodes(List.of("node-1"))
        .withHeartbeatInterval(Duration.ofMillis(500))
        .build();

    assert disposables.size() > 0;
    disposables.forEach(Disposable::dispose);
  }

  @Test
  void testHeartbeatBuilderEmitsCalls() {
    HeartbeatBuilder builder = new HeartbeatBuilder(
        mockControlBusGateway,
        Duration.ofMillis(100),
        Schedulers.boundedElastic());

    List<Disposable> disposables = builder
        .forNodes(List.of("node-1"))
        .withHeartbeatInterval(Duration.ofMillis(100))
        .build();

    // Wait for at least one heartbeat
    try {
      Thread.sleep(250);
    } catch (InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Verify that emit was called for heartbeats
    verify(mockControlBusGateway, atLeastOnce()).emit(argThat(msg ->
        msg.getPayload() instanceof ControlHeartbeat ||
        msg.getPayload() instanceof ControlStatistics));

    disposables.forEach(Disposable::dispose);
  }

  @Test
  void testHeartbeatBuilderEmptyNodeList() {
    HeartbeatBuilder builder = new HeartbeatBuilder(
        mockControlBusGateway,
        Duration.ofMillis(500),
        Schedulers.boundedElastic());

    List<Disposable> disposables = builder
        .forNodes(List.of())
        .withHeartbeatInterval(Duration.ofMillis(500))
        .build();

    assert disposables.isEmpty();
  }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew :yukta-core:test --tests HeartbeatBuilderTest -v
```

Expected: FAIL (class does not exist)

**Step 3: Write minimal HeartbeatBuilder implementation**

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
package com.infenia.yukta.service.orchestrator;

import com.infenia.yukta.plugin.gateway.ControlBusGateway;
import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.control.ControlHeartbeat;
import com.infenia.yukta.plugin.message.control.ControlStatistics;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Scheduler;

/**
 * Fluent builder for configuring and emitting heartbeats and statistics to the control bus
 * during workflow execution. Manages disposables for heartbeat and statistics subscriptions.
 */
@Slf4j
public class HeartbeatBuilder {

  private final ControlBusGateway controlBusGateway;
  private final Duration defaultInterval;
  private final Scheduler scheduler;

  private List<String> nodeIds;
  private Duration heartbeatInterval;
  private Duration statisticsInterval;

  /**
   * Constructs a HeartbeatBuilder.
   *
   * @param controlBusGateway the control bus gateway
   * @param defaultInterval the default interval
   * @param scheduler the scheduler for interval emissions
   */
  public HeartbeatBuilder(
      final ControlBusGateway controlBusGateway,
      final Duration defaultInterval,
      final Scheduler scheduler) {
    this.controlBusGateway = controlBusGateway;
    this.defaultInterval = defaultInterval;
    this.scheduler = scheduler;
  }

  /**
   * Sets the nodes to emit heartbeats for.
   *
   * @param nodes the list of node IDs
   * @return this builder
   */
  public HeartbeatBuilder forNodes(final List<String> nodes) {
    this.nodeIds = nodes;
    return this;
  }

  /**
   * Sets the heartbeat interval.
   *
   * @param interval the heartbeat interval
   * @return this builder
   */
  public HeartbeatBuilder withHeartbeatInterval(final Duration interval) {
    this.heartbeatInterval = interval;
    return this;
  }

  /**
   * Sets the statistics interval.
   *
   * @param interval the statistics interval
   * @return this builder
   */
  public HeartbeatBuilder withStatisticsInterval(final Duration interval) {
    this.statisticsInterval = interval;
    return this;
  }

  /**
   * Builds and starts heartbeat emissions, returning list of disposables.
   *
   * @return list of active disposables for heartbeat subscriptions
   */
  public List<Disposable> build() {
    final List<Disposable> disposables = new ArrayList<>();

    if (nodeIds == null || nodeIds.isEmpty()) {
      return disposables;
    }

    final Duration hbInterval = heartbeatInterval != null ? heartbeatInterval : defaultInterval;
    final Duration statsInterval = statisticsInterval != null ? statisticsInterval : hbInterval.multipliedBy(2);

    for (final String nodeId : nodeIds) {
      final long startTime = System.currentTimeMillis();

      // Heartbeat emissions
      disposables.add(
          Flux.interval(hbInterval, scheduler)
              .doOnError(e -> log.atWarn()
                  .setCause(e)
                  .addKeyValue("nodeId", nodeId)
                  .log("Heartbeat emission error"))
              .flatMap(tick -> controlBusGateway.emit(
                  DefaultMessage.create(
                          null,
                          new ControlHeartbeat(nodeId, System.currentTimeMillis() - startTime))
                      .withSourceNodeId(nodeId)
                      .withControl(true)))
              .subscribe());

      // Statistics emissions
      disposables.add(
          Flux.interval(statsInterval, scheduler)
              .doOnError(e -> log.atWarn()
                  .setCause(e)
                  .addKeyValue("nodeId", nodeId)
                  .log("Statistics emission error"))
              .flatMap(tick -> controlBusGateway.emit(
                  DefaultMessage.create(null, new ControlStatistics(nodeId, 0.0, 0.0))
                      .withSourceNodeId(nodeId)
                      .withControl(true)))
              .subscribe());
    }

    return disposables;
  }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew :yukta-core:test --tests HeartbeatBuilderTest -v
```

Expected: PASS (all 5 tests pass)

**Step 5: Commit**

```bash
git add yukta-core/src/main/java/com/infenia/yukta/service/orchestrator/HeartbeatBuilder.java
git add yukta-core/src/test/java/com/infenia/yukta/service/orchestrator/HeartbeatBuilderTest.java
git commit -m "feat: add HeartbeatBuilder for managing control bus heartbeats and statistics"
```

---

## Task 4: Create ResourceManagementBuilder

**Files:**
- Create: `yukta-core/src/main/java/com/infenia/yukta/service/orchestrator/ResourceManagementBuilder.java`
- Test: `yukta-core/src/test/java/com/infenia/yukta/service/orchestrator/ResourceManagementBuilderTest.java`

**Step 1: Write the test for ResourceManagementBuilder**

```java
package com.infenia.yukta.service.orchestrator;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class ResourceManagementBuilderTest {

  @Mock private TaskTrackerService mockTracker;
  @Mock private SessionConfigStore mockConfigService;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(mockConfigService.getExecutionTimeout("session-001"))
        .thenReturn(Mono.just(60L));
  }

  @Test
  void testResourceManagementBuilderBasic() {
    ResourceManagementBuilder builder = new ResourceManagementBuilder(
        mockTracker,
        mockConfigService,
        Schedulers.boundedElastic());

    List<Disposable> disposables = new ArrayList<>();
    List<Mono<Void>> terminals = List.of(Mono.empty());
    List<Runnable> connectors = new ArrayList<>();

    Mono<Void> execution = builder
        .withDisposables(disposables)
        .withTerminals(terminals)
        .withConnectors(connectors)
        .withExecutionTimeout("session-001", "exec-001")
        .build();

    StepVerifier.create(execution)
        .verifyComplete();
  }

  @Test
  void testResourceManagementBuilderEmitsSuccessStatus() {
    ResourceManagementBuilder builder = new ResourceManagementBuilder(
        mockTracker,
        mockConfigService,
        Schedulers.boundedElastic());

    List<Disposable> disposables = new ArrayList<>();
    List<Mono<Void>> terminals = List.of(Mono.empty());
    List<Runnable> connectors = new ArrayList<>();

    Mono<Void> execution = builder
        .withDisposables(disposables)
        .withTerminals(terminals)
        .withConnectors(connectors)
        .withExecutionTimeout("session-001", "exec-001")
        .build();

    StepVerifier.create(execution)
        .verifyComplete();

    verify(mockTracker).emitWorkflowStatusEvent("exec-001", "SUCCESS");
  }

  @Test
  void testResourceManagementBuilderDisposesResources() {
    ResourceManagementBuilder builder = new ResourceManagementBuilder(
        mockTracker,
        mockConfigService,
        Schedulers.boundedElastic());

    Disposable mockDisposable = mock(Disposable.class);
    List<Disposable> disposables = List.of(mockDisposable);
    List<Mono<Void>> terminals = List.of(Mono.empty());
    List<Runnable> connectors = new ArrayList<>();

    Mono<Void> execution = builder
        .withDisposables(disposables)
        .withTerminals(terminals)
        .withConnectors(connectors)
        .withExecutionTimeout("session-001", "exec-001")
        .build();

    StepVerifier.create(execution)
        .verifyComplete();

    verify(mockDisposable).dispose();
  }

  @Test
  void testResourceManagementBuilderRunsConnectors() {
    ResourceManagementBuilder builder = new ResourceManagementBuilder(
        mockTracker,
        mockConfigService,
        Schedulers.boundedElastic());

    Runnable mockConnector = mock(Runnable.class);
    List<Disposable> disposables = new ArrayList<>();
    List<Mono<Void>> terminals = List.of(Mono.empty());
    List<Runnable> connectors = List.of(mockConnector);

    Mono<Void> execution = builder
        .withDisposables(disposables)
        .withTerminals(terminals)
        .withConnectors(connectors)
        .withExecutionTimeout("session-001", "exec-001")
        .build();

    StepVerifier.create(execution)
        .verifyComplete();

    verify(mockConnector).run();
  }
}
```

**Step 2: Run test to verify it fails**

```bash
./gradlew :yukta-core:test --tests ResourceManagementBuilderTest -v
```

Expected: FAIL (class does not exist)

**Step 3: Write minimal ResourceManagementBuilder implementation**

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
package com.infenia.yukta.service.orchestrator;

import jakarta.annotation.Nullable;
import java.time.Duration;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Fluent builder for managing execution resources including disposables, timeouts, terminal
 * completion, and cleanup. Encapsulates the Mono.using() pattern for resource lifecycle management.
 */
@Slf4j
public class ResourceManagementBuilder {

  private static final long GLOBAL_TIMEOUT = 3600L;
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_ERROR = "ERROR";

  private final TaskTrackerService tracker;
  private final SessionConfigStore configService;
  private final Scheduler scheduler;

  @Nullable private List<Disposable> disposables;
  @Nullable private List<Mono<Void>> terminals;
  @Nullable private List<Runnable> connectors;
  @Nullable private String sessionId;
  @Nullable private String executionId;

  /**
   * Constructs a ResourceManagementBuilder.
   *
   * @param tracker the task tracker service
   * @param configService the session config store
   * @param scheduler the scheduler for timeout operations
   */
  public ResourceManagementBuilder(
      final TaskTrackerService tracker,
      final SessionConfigStore configService,
      final Scheduler scheduler) {
    this.tracker = tracker;
    this.configService = configService;
    this.scheduler = scheduler;
  }

  /**
   * Sets the disposables to manage.
   *
   * @param disposables the list of disposables
   * @return this builder
   */
  public ResourceManagementBuilder withDisposables(final List<Disposable> disposables) {
    this.disposables = disposables;
    return this;
  }

  /**
   * Sets the terminal monos to wait for.
   *
   * @param terminals the list of terminal monos
   * @return this builder
   */
  public ResourceManagementBuilder withTerminals(final List<Mono<Void>> terminals) {
    this.terminals = terminals;
    return this;
  }

  /**
   * Sets the connectors to run on subscription.
   *
   * @param connectors the list of connector runnables
   * @return this builder
   */
  public ResourceManagementBuilder withConnectors(final List<Runnable> connectors) {
    this.connectors = connectors;
    return this;
  }

  /**
   * Sets the execution timeout from session configuration.
   *
   * @param sessionId the session ID
   * @param executionId the execution ID
   * @return this builder
   */
  public ResourceManagementBuilder withExecutionTimeout(
      final String sessionId, final String executionId) {
    this.sessionId = sessionId;
    this.executionId = executionId;
    return this;
  }

  /**
   * Builds the execution Mono with resource management.
   *
   * @return the execution Mono that manages resources
   */
  public Mono<Void> build() {
    return Mono.using(
        () -> disposables != null ? disposables : List.of(),
        d -> executeWithTimeout(),
        this::cleanup);
  }

  private Mono<Void> executeWithTimeout() {
    final Mono<Long> timeoutMono = sessionId != null
        ? configService.getExecutionTimeout(sessionId).defaultIfEmpty(GLOBAL_TIMEOUT)
        : Mono.just(GLOBAL_TIMEOUT);

    return timeoutMono.flatMap(wfTimeout -> {
      log.atDebug()
          .addKeyValue("executionId", executionId)
          .addKeyValue("timeoutSeconds", wfTimeout)
          .log("Configuring workflow timeout: {} seconds", wfTimeout);

      Mono<Void> terminalMono = terminals != null && !terminals.isEmpty()
          ? Mono.whenDelayError(terminals)
          : Mono.empty();

      if (wfTimeout > 0) {
        terminalMono = terminalMono.timeout(Duration.ofSeconds(wfTimeout), scheduler);
      }

      return terminalMono
          .doOnSubscribe(s -> {
            log.atTrace()
                .addKeyValue("executionId", executionId)
                .addKeyValue("connectorCount", connectors != null ? connectors.size() : 0)
                .log("Connecting upstreams to sinks");
            if (connectors != null) {
              for (int i = connectors.size() - 1; i >= 0; i--) {
                connectors.get(i).run();
              }
            }
          })
          .doOnSuccess(v -> {
            log.atInfo()
                .addKeyValue("executionId", executionId)
                .log("All workflow terminals completed successfully");
            tracker.emitWorkflowStatusEvent(executionId, STATUS_SUCCESS);
          })
          .onErrorResume(e -> {
            log.atError()
                .setCause(e)
                .addKeyValue("executionId", executionId)
                .log("Workflow terminal execution failed");
            tracker.emitWorkflowStatusEvent(executionId, STATUS_ERROR);
            return Mono.error(e);
          });
    });
  }

  private void cleanup(final List<Disposable> d) {
    log.atTrace()
        .addKeyValue("executionId", executionId)
        .addKeyValue("disposableCount", d.size())
        .log("Disposing {} resources", d.size());
    for (final Disposable disposable : d) {
      disposable.dispose();
    }
  }
}
```

**Step 4: Run test to verify it passes**

```bash
./gradlew :yukta-core:test --tests ResourceManagementBuilderTest -v
```

Expected: PASS (all 5 tests pass)

**Step 5: Commit**

```bash
git add yukta-core/src/main/java/com/infenia/yukta/service/orchestrator/ResourceManagementBuilder.java
git add yukta-core/src/test/java/com/infenia/yukta/service/orchestrator/ResourceManagementBuilderTest.java
git commit -m "feat: add ResourceManagementBuilder for unified resource lifecycle management"
```

---

## Task 5: Refactor createNodeAssembler to use StreamBuilder

**Files:**
- Modify: `yukta-core/src/main/java/com/infenia/yukta/service/WorkflowOrchestrator.java`
- Modify: `yukta-core/src/test/java/com/infenia/yukta/service/WorkflowOrchestratorTest.java` (add tests for simplified assemblers)

**Step 1: Update createTriggerAssembler to use StreamBuilder**

In `WorkflowOrchestrator.java`, replace the `createTriggerAssembler` method (lines 615-680) with:

```java
private NodeAssembler createTriggerAssembler(
    final Node node,
    final TriggerPlugin trigger,
    final Duration timeout,
    final int index,
    final int bufferSize) {

  return (execId, sessId, wfId, pld, strms, terms, disps, conns) -> {
    Flux<Message<?>> stream = trigger.start(node.config());
    if (trigger.isBlocking()) {
      stream = stream.subscribeOn(virtualThreadScheduler);
    }

    final ExecutionContextBuilder contextBuilder = new ExecutionContextBuilder()
        .sessionId(sessId)
        .workflowId(wfId)
        .executionId(execId)
        .nodeId(node.nodeId())
        .payload(pld);

    Flux<Message<?>> built = new StreamBuilder(
            node, trigger, timeout, bufferSize, tracker, controlBusGateway)
        .withSource(stream)
        .withTimeout()
        .withTaskTracking(execId, sessId)
        .withErrorHandling(execId)
        .build();

    built = contextBuilder.applyContextTo(built);
    strms[index] = applyLoggingAndBroadcasting(execId, node.nodeId(), built, bufferSize, disps, conns);
  };
}
```

**Step 2: Update createProcessorAssembler to use StreamBuilder**

Replace the `createProcessorAssembler` method (lines 683-751) with:

```java
@SuppressWarnings("PMD.UseVarargs")
private NodeAssembler createProcessorAssembler(
    final Node node,
    final ProcessorPlugin processor,
    final Duration timeout,
    final int index,
    final int bufferSize,
    final ParentEdgeInfo[] parentEdges) {

  return (execId, sessId, wfId, pld, strms, terms, disps, conns) -> {
    final Flux<Message<?>> mergedInput = mergeParentStreams(strms, parentEdges);
    Flux<Message<?>> stream = processor.process(mergedInput, node.config());
    if (processor.isBlocking()) {
      stream = stream.subscribeOn(virtualThreadScheduler);
    }

    final ExecutionContextBuilder contextBuilder = new ExecutionContextBuilder()
        .sessionId(sessId)
        .workflowId(wfId)
        .executionId(execId)
        .nodeId(node.nodeId())
        .payload(pld);

    Flux<Message<?>> built = new StreamBuilder(
            node, processor, timeout, bufferSize, tracker, controlBusGateway)
        .withSource(stream)
        .withTimeout()
        .withTaskTracking(execId, sessId)
        .withErrorHandling(execId)
        .build();

    built = contextBuilder.applyContextTo(built);
    strms[index] = applyLoggingAndBroadcasting(execId, node.nodeId(), built, bufferSize, disps, conns);
  };
}
```

**Step 3: Update createTerminalAssembler to use StreamBuilder**

Replace the `createTerminalAssembler` method (lines 753-827) with:

```java
@SuppressWarnings("PMD.UseVarargs")
private NodeAssembler createTerminalAssembler(
    final Node node,
    final TerminalPlugin terminal,
    final Duration timeout,
    final ParentEdgeInfo[] parentEdges) {

  return (execId, sessId, wfId, pld, strms, terms, disps, conns) -> {
    final Flux<Message<?>> mergedInput = mergeParentStreams(strms, parentEdges);
    final Flux<Message<?>> inputToTerminal = mergedInput
        .flatMap(
            msg -> Mono.<Message<?>>just(msg)
                .timeout(timeout, virtualThreadScheduler)
                .onErrorMap(TimeoutException.class, e -> e),
            BUFFER_SIZE)
        .transformDeferredContextual(
            (flux, ctx) -> {
              final ResultCollector collector = ctx.getOrDefault("resultCollector", null);
              return collector != null ? flux.doOnNext(collector::add) : flux;
            });

    final ExecutionContextBuilder contextBuilder = new ExecutionContextBuilder()
        .sessionId(sessId)
        .workflowId(wfId)
        .executionId(execId)
        .nodeId(node.nodeId())
        .payload(pld);

    Mono<Void> completion = terminal.consume(inputToTerminal, node.config());
    if (terminal.isBlocking()) {
      completion = completion.subscribeOn(virtualThreadScheduler);
    }

    completion = completion
        .doOnSubscribe(s -> tracker.emitTaskStatusEvent(
            execId, node.nodeId(), "default", "RUNNING", Collections.emptyMap()))
        .doOnSuccess(v -> tracker.emitTaskStatusEvent(
            execId, node.nodeId(), "default", "SUCCESS", Collections.emptyMap()))
        .doOnError(e -> {
          tracker.emitTaskStatusEvent(
              execId, node.nodeId(), "default", "FAILURE", Collections.emptyMap());
          controlBusGateway
              .emit(
                  DefaultMessage.create(
                          null,
                          new ControlError(
                              node.nodeId(), execId, "Node Failure", e.getMessage()))
                      .withSourceNodeId(node.nodeId())
                      .withControl(true)
                      .withPriority(10))
              .subscribe();
        })
        .then();

    completion = contextBuilder.applyContextTo(completion);
    terms.add(completion);
  };
}
```

**Step 4: Run tests to verify refactoring doesn't break functionality**

```bash
./gradlew :yukta-core:test --tests WorkflowOrchestratorTest -v
```

Expected: PASS (all existing tests pass)

**Step 5: Run full quality checks**

```bash
./gradlew :yukta-core:check
```

Expected: PASS (spotlessApply, checkstyle, PMD, tests all pass)

**Step 6: Commit**

```bash
git add yukta-core/src/main/java/com/infenia/yukta/service/WorkflowOrchestrator.java
git commit -m "refactor: simplify node assemblers using StreamBuilder pattern"
```

---

## Task 6: Refactor executeTemplate to use HeartbeatBuilder and ResourceManagementBuilder

**Files:**
- Modify: `yukta-core/src/main/java/com/infenia/yukta/service/WorkflowOrchestrator.java`

**Step 1: Replace executeTemplate heartbeat and resource management logic**

In `WorkflowOrchestrator.java`, replace the `executeTemplate` method (lines 409-544) with:

```java
private Mono<Void> executeTemplate(
    final String executionId,
    final Map<String, Object> payload,
    final int nodeCount,
    final NodeAssembler[] assemblers,
    final String sessionId,
    final String workflowId,
    final List<String> nodeIds) {

  log.atDebug()
      .addKeyValue(LOG_KEY_EXECUTION_ID, executionId)
      .addKeyValue(LOG_KEY_NODE_COUNT, nodeCount)
      .addKeyValue(LOG_KEY_NODE_IDS, nodeIds)
      .log("Executing workflow template with {} nodes", nodeCount);

  @SuppressWarnings("unchecked")
  final Flux<Message<?>>[] streams = new Flux[nodeCount];
  final List<Mono<Void>> terminals = new ArrayList<>(nodeCount);
  final List<Disposable> disposables = new ArrayList<>(nodeCount);
  final List<Runnable> connectors = new ArrayList<>(nodeCount);

  for (final NodeAssembler assembler : assemblers) {
    assembler.assemble(
        executionId, sessionId, workflowId, payload, streams, terminals, disposables, connectors);
  }

  log.atTrace()
      .addKeyValue(LOG_KEY_EXECUTION_ID, executionId)
      .addKeyValue(LOG_KEY_TERMINAL_COUNT, terminals.size())
      .log("Node assembly complete");

  // Setup heartbeats and statistics
  log.atDebug()
      .addKeyValue(LOG_KEY_EXECUTION_ID, executionId)
      .addKeyValue(LOG_KEY_NODE_COUNT, nodeIds.size())
      .addKeyValue(LOG_KEY_HEARTBEAT_INTERVAL, heartbeatInterval.toMillis())
      .log("Starting heartbeat and statistics emission for {} nodes", nodeIds.size());

  HeartbeatBuilder heartbeatBuilder = new HeartbeatBuilder(
      controlBusGateway, heartbeatInterval, virtualThreadScheduler);
  List<Disposable> heartbeatDisposables = heartbeatBuilder
      .forNodes(nodeIds)
      .withHeartbeatInterval(heartbeatInterval)
      .withStatisticsInterval(heartbeatInterval.multipliedBy(2))
      .build();
  disposables.addAll(heartbeatDisposables);

  // Execute with resource management
  return new ResourceManagementBuilder(tracker, configService, virtualThreadScheduler)
      .withDisposables(disposables)
      .withTerminals(terminals)
      .withConnectors(connectors)
      .withExecutionTimeout(sessionId, executionId)
      .build();
}
```

**Step 2: Run tests to verify refactoring**

```bash
./gradlew :yukta-core:test --tests WorkflowOrchestratorTest -v
```

Expected: PASS (all existing tests pass)

**Step 3: Run full quality checks**

```bash
./gradlew :yukta-core:check
```

Expected: PASS

**Step 4: Commit**

```bash
git add yukta-core/src/main/java/com/infenia/yukta/service/WorkflowOrchestrator.java
git commit -m "refactor: simplify executeTemplate using HeartbeatBuilder and ResourceManagementBuilder"
```

---

## Task 7: Remove unused helper methods from WorkflowOrchestrator

**Files:**
- Modify: `yukta-core/src/main/java/com/infenia/yukta/service/WorkflowOrchestrator.java`

**Step 1: Identify and remove unused methods**

Remove the following methods from `WorkflowOrchestrator` that are now handled by builders:
- The two nested loops in original `executeTemplate` for heartbeat/stats (lines 447-487) — now in `HeartbeatBuilder`
- Parts of `applyLoggingAndBroadcasting` related to context — now in `ExecutionContextBuilder`
- Task status tracking boilerplate in assemblers — now in `StreamBuilder`

Keep these methods as they are still used:
- `applyLoggingAndBroadcasting()` (still needed for wire tap and broadcasting)
- `getMessageFlux()` (still needed for message logging)
- `mergeParentStreams()` (still needed for parent merging)
- `applyEdgeRouting()` (still needed for edge routing)
- `getBufferSize()` (still needed for node config)
- `getNodeTimeout()` (still needed for timeout resolution)

Run spotlessApply to ensure formatting:

```bash
./gradlew spotlessApply
```

**Step 2: Run tests**

```bash
./gradlew :yukta-core:test --tests WorkflowOrchestratorTest -v
```

Expected: PASS

**Step 3: Run full quality checks**

```bash
./gradlew :yukta-core:check
```

Expected: PASS

**Step 4: Commit**

```bash
git add yukta-core/src/main/java/com/infenia/yukta/service/WorkflowOrchestrator.java
git commit -m "refactor: clean up WorkflowOrchestrator by removing duplicated logic now in builders"
```

---

## Task 8: Add comprehensive unit tests for refactored orchestrator

**Files:**
- Modify: `yukta-core/src/test/java/com/infenia/yukta/service/WorkflowOrchestratorTest.java`

**Step 1: Write test for node assembler simplification**

Add to `WorkflowOrchestratorTest.java`:

```java
@Test
void testCreateTriggerAssemblerUsesStreamBuilder() {
  // Mock all dependencies
  WorkflowPlugin triggerPlugin = mock(TriggerPlugin.class);
  when(triggerPlugin.start(any())).thenReturn(Flux.just(
      DefaultMessage.create(Map.of(), "test")));
  when(triggerPlugin.isBlocking()).thenReturn(false);

  Node node = mock(Node.class);
  when(node.nodeId()).thenReturn("trigger-node");
  when(node.config()).thenReturn(Collections.emptyMap());

  // Create and test the assembler
  NodeAssembler assembler = orchestrator.createTriggerAssembler(
      node, (TriggerPlugin) triggerPlugin, Duration.ofSeconds(5), 0, 1024);

  Flux<Message<?>>[] streams = new Flux[1];
  List<Mono<Void>> terminals = new ArrayList<>();
  List<Disposable> disposables = new ArrayList<>();
  List<Runnable> connectors = new ArrayList<>();

  assembler.assemble(
      "exec-001", "session-001", "workflow-001",
      Map.of("key", "value"),
      streams, terminals, disposables, connectors);

  assertNotNull(streams[0]);
  disposables.forEach(Disposable::dispose);
}

@Test
void testHeartbeatBuilderIntegration() {
  // Verify heartbeat setup in executeTemplate
  when(configService.getExecutionTimeout(any())).thenReturn(Mono.just(60L));

  HeartbeatBuilder heartbeatBuilder = new HeartbeatBuilder(
      controlBusGateway, heartbeatInterval, virtualThreadScheduler);
  List<Disposable> disposables = heartbeatBuilder
      .forNodes(List.of("node-1", "node-2"))
      .withHeartbeatInterval(heartbeatInterval)
      .build();

  assert !disposables.isEmpty();
  disposables.forEach(Disposable::dispose);
}

@Test
void testResourceManagementBuilderIntegration() {
  when(configService.getExecutionTimeout(any())).thenReturn(Mono.just(60L));

  ResourceManagementBuilder resourceMgr = new ResourceManagementBuilder(
      tracker, configService, virtualThreadScheduler);

  Mono<Void> execution = resourceMgr
      .withDisposables(new ArrayList<>())
      .withTerminals(List.of(Mono.empty()))
      .withConnectors(new ArrayList<>())
      .withExecutionTimeout("session-001", "exec-001")
      .build();

  StepVerifier.create(execution)
      .verifyComplete();

  verify(tracker).emitWorkflowStatusEvent("exec-001", "SUCCESS");
}
```

**Step 2: Run new tests**

```bash
./gradlew :yukta-core:test --tests WorkflowOrchestratorTest -v
```

Expected: PASS (all new and existing tests pass)

**Step 3: Run full quality checks**

```bash
./gradlew :yukta-core:check
```

Expected: PASS

**Step 4: Commit**

```bash
git add yukta-core/src/test/java/com/infenia/yukta/service/WorkflowOrchestratorTest.java
git commit -m "test: add comprehensive tests for refactored orchestrator and builders"
```

---

## Task 9: Update CLAUDE.md with refactoring notes

**Files:**
- Modify: `CLAUDE.md`

**Step 1: Add architecture documentation**

Add a new section to `CLAUDE.md` under "Code Architecture & Patterns":

```markdown
### WorkflowOrchestrator Builder Pattern (Refactored)

The `WorkflowOrchestrator` uses internal fluent builder classes to manage cross-cutting concerns:

**StreamBuilder** (`ExecutionContextBuilder` in package `yukta-core/src/main/java/com/infenia/yukta/service/orchestrator/`):
- Unifies stream construction across Trigger, Processor, and Terminal plugins
- Handles: timeout wrapping, task status tracking, error handling, context application
- Usage: `new StreamBuilder(...).withSource(...).withTimeout().withTaskTracking(...).build()`

**ExecutionContextBuilder**:
- Centralizes context key management (sessionId, workflowId, executionId, nodeId, payload)
- Provides fluent API for building and applying contexts to Mono/Flux

**HeartbeatBuilder**:
- Manages periodic heartbeat and statistics emissions to the control bus
- Encapsulates disposable lifecycle for heartbeat subscriptions
- Replaces two nested loops with a single configuration point

**ResourceManagementBuilder**:
- Wraps `Mono.using()` pattern for resource lifecycle (acquire → execute → cleanup)
- Manages timeouts, terminal completion, and disposable cleanup
- Centralizes workflow status event emission (SUCCESS/ERROR)

**Key Benefits**:
- Node assembler methods reduced from ~70 lines each to ~20 lines
- No duplication across plugin types (consistent error handling)
- Testable in isolation: each builder has dedicated unit tests
- Performance unchanged: builders are transient; no allocation overhead
```

**Step 2: Run spotlessApply**

```bash
./gradlew spotlessApply
```

**Step 3: Commit**

```bash
git add CLAUDE.md
git commit -m "docs: add refactoring documentation for builder pattern in WorkflowOrchestrator"
```

---

## Task 10: Final verification and cleanup

**Files:**
- None (verification only)

**Step 1: Run full test suite**

```bash
./gradlew :yukta-core:test -v
```

Expected: PASS (all tests pass, including new builder tests)

**Step 2: Run all quality checks**

```bash
./gradlew :yukta-core:check
```

Expected: PASS (spotless, checkstyle, PMD, SpotBugs, JaCoCo all pass)

**Step 3: Verify no regressions**

```bash
./gradlew :yukta-core:check :yukta-plugin-api:check :yukta-ui:check :yukta-boot:check
```

Expected: PASS (all modules pass)

**Step 4: Commit final state**

```bash
git log --oneline -10
```

Expected: See all refactoring commits in order

**Step 5: Summary**

At this point, the refactoring is complete:
- ✅ `WorkflowOrchestrator` reduced from 965 to ~500 lines
- ✅ Four builder classes added (~1000 lines total, fully tested)
- ✅ All functionality preserved
- ✅ Performance unchanged (no allocation overhead, same Reactor patterns)
- ✅ Testability dramatically improved (builders testable in isolation)
- ✅ Maintainability improved (clear separation of concerns)
- ✅ All quality gates passing

---

## Execution Options

Plan complete and saved to `docs/plans/2026-03-14-workflow-orchestrator-refactor.md`. Two execution options:

**1. Subagent-Driven (this session)** - I dispatch fresh subagent per task, review between tasks, fast iteration

**2. Parallel Session (separate)** - Open new session with executing-plans, batch execution with checkpoints

Which approach?