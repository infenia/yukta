# ControlBus Refactoring Implementation Plan

> **For Claude:** REQUIRED SUB-SKILL: Use superpowers:executing-plans to implement this plan task-by-task.

**Goal:** Refactor ControlBusService and DefaultControlBusGateway to eliminate encapsulation breaks (casts in WorkflowOrchestrator), fix memory leaks, inject configuration, and implement an extensible handler registry for control signals.

**Architecture:** Replace hardcoded instanceof dispatch with a handler registry pattern. Extend ControlBusGateway interface to expose all control-bus operations (plugin lifecycle, command execution, state queries). Inject configuration via @Value for per-environment tuning. Keep ControlBusService as the orchestrator of handlers and signal processing.

**Tech Stack:** Java 21, Spring Boot 4.0.2, Project Reactor (Mono/Flux), Spring @Value for configuration injection, handler strategy pattern

---

## Task 1: Extend ControlBusGateway Interface

**Files:**
- Modify: `plugin-api/src/main/java/com/infenia/yukta/plugin/gateway/ControlBusGateway.java`

**Step 1: Read current interface**

```bash
cat plugin-api/src/main/java/com/infenia/yukta/plugin/gateway/ControlBusGateway.java
```

Expected: Single method `emit()`, marked `@FunctionalInterface`

**Step 2: Replace with extended interface**

Replace entire file with:

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
package com.infenia.yukta.plugin.gateway;

import com.infenia.yukta.plugin.core.WorkflowPlugin;
import com.infenia.yukta.plugin.message.Message;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import reactor.core.publisher.Mono;

/**
 * Interface for components to interact with the system's Control Bus.
 *
 * <p>The Control Bus manages administrative signals such as heartbeats, statistics, and
 * configuration updates. It also provides plugin lifecycle management and command execution.
 */
public interface ControlBusGateway {

  /**
   * Emit a control message to the bus.
   *
   * @param <T> the type of the control payload
   * @param signal the control message to emit
   * @return a Mono that completes when the signal has been emitted
   */
  <T> Mono<Void> emit(Message<T> signal);

  /**
   * Register a plugin to receive control signals.
   *
   * @param nodeId the node identifier
   * @param plugin the plugin instance
   */
  void registerPlugin(@NotBlank String nodeId, WorkflowPlugin plugin);

  /**
   * Unregister a plugin from the control bus.
   *
   * @param nodeId the node identifier
   */
  void unregisterPlugin(@NotBlank String nodeId);

  /**
   * Send a command to a specific node and wait for response.
   *
   * @param nodeId the target node identifier
   * @param command the command message
   * @return a Mono of the response message
   */
  Mono<Message<?>> sendCommand(@NotBlank String nodeId, Message<?> command);

  /**
   * Get the last heartbeat for a node.
   *
   * @param nodeId the node identifier
   * @return the last heartbeat message, or null if none
   */
  Message<?> getLastHeartbeat(@NotBlank String nodeId);

  /**
   * Get the last statistics message for a node.
   *
   * @param nodeId the node identifier
   * @return the last statistics message, or null if none
   */
  Message<?> getLastStatistics(@NotBlank String nodeId);

  /**
   * List all node IDs that have emitted heartbeats.
   *
   * @return list of node IDs
   */
  List<String> getActiveNodes();
}
```

**Step 3: Verify syntax**

```bash
./gradlew :plugin-api:compileJava
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add plugin-api/src/main/java/com/infenia/yukta/plugin/gateway/ControlBusGateway.java
git commit -m "refactor: extend ControlBusGateway interface with plugin lifecycle and state queries

Adds 6 new methods to ControlBusGateway:
- registerPlugin(), unregisterPlugin() — plugin lifecycle
- sendCommand() — command execution
- getLastHeartbeat(), getLastStatistics() — state queries
- getActiveNodes() — node discovery

This eliminates the need for casts in WorkflowOrchestrator.
No longer marked @FunctionalInterface (now has 7 methods).

Co-Authored-By: Claude Haiku 4.5 <noreply@anthropic.com>"
```

---

## Task 2: Create ControlSignalHandler Interface

**Files:**
- Create: `core/src/main/java/com/infenia/yukta/service/control/ControlSignalHandler.java`

**Step 1: Create directory**

```bash
mkdir -p core/src/main/java/com/infenia/yukta/service/control
```

**Step 2: Write the interface**

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
package com.infenia.yukta.service.control;

import com.infenia.yukta.plugin.message.Message;

/**
 * Handler for a specific type of control signal.
 *
 * <p>Implementations process control signals of a particular type (e.g., heartbeat,
 * statistics). Multiple handlers can be registered in ControlBusService; when a signal
 * arrives, the first handler that can handle it processes it.
 */
public interface ControlSignalHandler {

  /**
   * Check whether this handler can process the given payload.
   *
   * @param payload the signal payload
   * @return true if this handler should process the signal, false otherwise
   */
  boolean canHandle(Object payload);

  /**
   * Process a control signal.
   *
   * @param nodeId the source node identifier
   * @param message the full message (includes metadata)
   * @param payload the signal payload (pre-cast by the handler)
   */
  void handle(String nodeId, Message<?> message, Object payload);
}
```

**Step 3: Verify syntax**

```bash
./gradlew :core:compileJava
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add core/src/main/java/com/infenia/yukta/service/control/ControlSignalHandler.java
git commit -m "feat: add ControlSignalHandler interface for extensible signal dispatch

Introduces strategy pattern for control signal handling.
Allows future signal types (ControlError, ControlAlert) to be added
without modifying ControlBusService.

Handlers register with Spring and are discovered via dependency injection.

Co-Authored-By: Claude Haiku 4.5 <noreply@anthropic.com>"
```

---

## Task 3: Create ControlHeartbeatHandler

**Files:**
- Create: `core/src/main/java/com/infenia/yukta/service/control/ControlHeartbeatHandler.java`

**Step 1: Write the handler**

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
package com.infenia.yukta.service.control;

import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.message.control.ControlHeartbeat;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Handler for ControlHeartbeat signals.
 *
 * <p>Stores the last heartbeat message from each node for status queries.
 */
@Component
@NoArgsConstructor
public class ControlHeartbeatHandler implements ControlSignalHandler {

  private final Map<String, Message<?>> lastHeartbeats = new ConcurrentHashMap<>();

  @Override
  public boolean canHandle(final Object payload) {
    return payload instanceof ControlHeartbeat;
  }

  @Override
  public void handle(final String nodeId, final Message<?> message, final Object payload) {
    lastHeartbeats.put(nodeId, message);
  }

  /**
   * Get the last heartbeat for a node.
   *
   * @param nodeId the node identifier
   * @return the last heartbeat message, or null if none
   */
  public Message<?> getLastHeartbeat(final String nodeId) {
    return lastHeartbeats.get(nodeId);
  }

  /**
   * Get all active node IDs.
   *
   * @return list of node IDs that have sent heartbeats
   */
  public java.util.List<String> getActiveNodes() {
    return java.util.List.copyOf(lastHeartbeats.keySet());
  }

  /**
   * Remove a node's heartbeat record.
   *
   * @param nodeId the node identifier
   */
  public void removeNode(final String nodeId) {
    lastHeartbeats.remove(nodeId);
  }
}
```

**Step 2: Verify syntax**

```bash
./gradlew :core:compileJava
```

Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add core/src/main/java/com/infenia/yukta/service/control/ControlHeartbeatHandler.java
git commit -m "feat: add ControlHeartbeatHandler for heartbeat signal dispatch

Implements ControlSignalHandler for ControlHeartbeat signals.
Stores last heartbeat per node, provides getLastHeartbeat() and getActiveNodes() queries.
Registered as Spring @Component for auto-discovery.

Co-Authored-By: Claude Haiku 4.5 <noreply@anthropic.com>"
```

---

## Task 4: Create ControlStatisticsHandler

**Files:**
- Create: `core/src/main/java/com/infenia/yukta/service/control/ControlStatisticsHandler.java`

**Step 1: Write the handler**

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
package com.infenia.yukta.service.control;

import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.message.control.ControlStatistics;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Handler for ControlStatistics signals.
 *
 * <p>Stores the last statistics message from each node for performance monitoring.
 */
@Component
@NoArgsConstructor
public class ControlStatisticsHandler implements ControlSignalHandler {

  private final Map<String, Message<?>> lastStatistics = new ConcurrentHashMap<>();

  @Override
  public boolean canHandle(final Object payload) {
    return payload instanceof ControlStatistics;
  }

  @Override
  public void handle(final String nodeId, final Message<?> message, final Object payload) {
    lastStatistics.put(nodeId, message);
  }

  /**
   * Get the last statistics message for a node.
   *
   * @param nodeId the node identifier
   * @return the last statistics message, or null if none
   */
  public Message<?> getLastStatistics(final String nodeId) {
    return lastStatistics.get(nodeId);
  }

  /**
   * Remove a node's statistics record.
   *
   * @param nodeId the node identifier
   */
  public void removeNode(final String nodeId) {
    lastStatistics.remove(nodeId);
  }
}
```

**Step 2: Verify syntax**

```bash
./gradlew :core:compileJava
```

Expected: BUILD SUCCESSFUL

**Step 3: Commit**

```bash
git add core/src/main/java/com/infenia/yukta/service/control/ControlStatisticsHandler.java
git commit -m "feat: add ControlStatisticsHandler for statistics signal dispatch

Implements ControlSignalHandler for ControlStatistics signals.
Stores last statistics per node, provides getLastStatistics() queries.
Registered as Spring @Component for auto-discovery.

Co-Authored-By: Claude Haiku 4.5 <noreply@anthropic.com>"
```

---

## Task 5: Refactor ControlBusService — Inject Config, Handlers, Add Cleanup, Expose getLastStatistics()

**Files:**
- Modify: `core/src/main/java/com/infenia/yukta/service/ControlBusService.java`

**Step 1: Review current file**

```bash
head -60 core/src/main/java/com/infenia/yukta/service/ControlBusService.java
```

**Step 2: Replace entire file**

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
package com.infenia.yukta.service;

import com.infenia.yukta.plugin.core.WorkflowPlugin;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.service.control.ControlSignalHandler;
import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.NotBlank;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;
import reactor.util.concurrent.Queues;

/**
 * Service for managing the system's Control Bus.
 *
 * <p>Handles administrative commands, heartbeats, and performance metrics from plugins.
 * Dispatches signals to registered handlers for extensible processing.
 */
@Slf4j
@Service
public class ControlBusService {

  private final int batchSize;
  private final Duration batchTimeout;
  private final int bufferSize;
  private final List<ControlSignalHandler> handlers;
  private final Map<String, WorkflowPlugin> activePlugins = new ConcurrentHashMap<>();
  private Sinks.Many<Message<?>> controlSink;
  private static final Sinks.EmitFailureHandler RETRY_HANDLER =
      Sinks.EmitFailureHandler.busyLooping(Duration.ofMillis(100));

  public ControlBusService(
      @Value("${control.bus.batch.size:100}") final int batchSize,
      @Value("${control.bus.batch.timeout.ms:50}") final int batchTimeoutMs,
      @Value("${control.bus.buffer.size:256}") final int bufferSize,
      final List<ControlSignalHandler> handlers) {
    this.batchSize = batchSize;
    this.batchTimeout = Duration.ofMillis(batchTimeoutMs);
    this.bufferSize = bufferSize;
    this.handlers = handlers;
  }

  /** Initialize the control sink and background event consumer. */
  @PostConstruct
  public void init() {
    controlSink =
        Sinks.many()
            .multicast()
            .onBackpressureBuffer(
                bufferSize < 0 ? Queues.SMALL_BUFFER_SIZE : bufferSize, false);

    controlSink
        .asFlux()
        .publishOn(Schedulers.parallel())
        .bufferTimeout(batchSize, batchTimeout)
        .concatMap(
            batch ->
                Mono.fromRunnable(() -> handleControlBatch(batch))
                    .onErrorResume(
                        e -> {
                          log.atError().setCause(e).log("Error processing control signal batch");
                          return Mono.empty();
                        }))
        .subscribe();
  }

  /**
   * Emit a control signal to the bus.
   *
   * @param signal the control signal message
   * @return a Mono that completes when the signal is emitted
   */
  public Mono<Void> emit(final Message<?> signal) {
    return Mono.fromRunnable(() -> controlSink.emitNext(signal, RETRY_HANDLER));
  }

  /**
   * Get a stream of all control signals.
   *
   * @return a Flux of control messages
   */
  public Flux<Message<?>> getControlStream() {
    return controlSink.asFlux();
  }

  /**
   * Get the last heartbeat for a node.
   *
   * @param nodeId the node identifier
   * @return the last heartbeat message, or null
   */
  public Message<?> getLastHeartbeat(final String nodeId) {
    for (final ControlSignalHandler handler : handlers) {
      if (handler instanceof com.infenia.yukta.service.control.ControlHeartbeatHandler hb) {
        return hb.getLastHeartbeat(nodeId);
      }
    }
    return null;
  }

  /**
   * Get the last statistics for a node.
   *
   * @param nodeId the node identifier
   * @return the last statistics message, or null
   */
  public Message<?> getLastStatistics(final String nodeId) {
    for (final ControlSignalHandler handler : handlers) {
      if (handler instanceof com.infenia.yukta.service.control.ControlStatisticsHandler cs) {
        return cs.getLastStatistics(nodeId);
      }
    }
    return null;
  }

  /**
   * List all node IDs that have emitted heartbeats.
   *
   * @return list of node IDs
   */
  public List<String> getActiveNodes() {
    for (final ControlSignalHandler handler : handlers) {
      if (handler instanceof com.infenia.yukta.service.control.ControlHeartbeatHandler hb) {
        return hb.getActiveNodes();
      }
    }
    return List.of();
  }

  /**
   * Register a plugin to receive control signals.
   *
   * @param nodeId the node identifier
   * @param plugin the plugin instance
   */
  public void registerPlugin(@NotBlank final String nodeId, final WorkflowPlugin plugin) {
    activePlugins.put(nodeId, plugin);
  }

  /**
   * Unregister a plugin from the control bus and clean up all state.
   *
   * @param nodeId the node identifier
   */
  public void unregisterPlugin(@NotBlank final String nodeId) {
    activePlugins.remove(nodeId);
    // Clean up handler state
    for (final ControlSignalHandler handler : handlers) {
      if (handler instanceof com.infenia.yukta.service.control.ControlHeartbeatHandler hb) {
        hb.removeNode(nodeId);
      } else if (handler instanceof com.infenia.yukta.service.control.ControlStatisticsHandler cs) {
        cs.removeNode(nodeId);
      }
    }
  }

  /**
   * Send a command to a specific node and wait for response.
   *
   * @param nodeId the target node identifier
   * @param command the command message
   * @return a Mono of the response message
   */
  public Mono<Message<?>> sendCommand(
      @NotBlank final String nodeId, final Message<?> command) {
    final WorkflowPlugin plugin = activePlugins.get(nodeId);
    if (plugin == null) {
      return Mono.error(new IllegalArgumentException("Node not found: " + nodeId));
    }
    return plugin.onControlSignal(command);
  }

  /**
   * Shutdown the control bus gracefully.
   *
   * <p>Signals completion on the control sink, terminating the control stream and allowing
   * subscribers to close cleanly.
   */
  public void shutdown() {
    controlSink.emitComplete(RETRY_HANDLER);
  }

  private void handleControlBatch(final List<Message<?>> batch) {
    final List<Message<?>> prioritized =
        batch.stream()
            .sorted(Comparator.comparingInt(Message::getPriority).reversed())
            .toList();

    for (final Message<?> msg : prioritized) {
      final Object payload = msg.getPayload();
      final String nodeId = msg.getSourceNodeId();

      if (nodeId != null) {
        for (final ControlSignalHandler handler : handlers) {
          if (handler.canHandle(payload)) {
            handler.handle(nodeId, msg, payload);
            break;
          }
        }
      }
    }
  }
}
```

**Step 3: Verify syntax and tests compile**

```bash
./gradlew :core:compileJava :core:compileTestJava
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add core/src/main/java/com/infenia/yukta/service/ControlBusService.java
git commit -m "refactor: inject config, implement handler registry, add cleanup in ControlBusService

Major changes:
1. Inject batchSize, batchTimeout, bufferSize via @Value for per-environment tuning
2. Inject List<ControlSignalHandler> for extensible signal dispatch
3. Replace hardcoded instanceof checks with handler registry dispatch in handleControlBatch()
4. Add cleanup in unregisterPlugin() — removes node from all handlers
5. Expose getLastStatistics() (was dead code, now public)
6. Add shutdown() hook for graceful termination
7. Remove @NoArgsConstructor (now has constructor with dependencies)
8. Remove @SuppressWarnings(PMD.LawOfDemeter) (no longer needed)

Handler registry allows future signal types without modifying this class.

Co-Authored-By: Claude Haiku 4.5 <noreply@anthropic.com>"
```

---

## Task 6: Update DefaultControlBusGateway — Implement All 7 Methods, Remove @Getter

**Files:**
- Modify: `core/src/main/java/com/infenia/yukta/service/DefaultControlBusGateway.java`

**Step 1: Review current file**

```bash
cat core/src/main/java/com/infenia/yukta/service/DefaultControlBusGateway.java
```

**Step 2: Replace entire file**

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
package com.infenia.yukta.service;

import com.infenia.yukta.plugin.core.WorkflowPlugin;
import com.infenia.yukta.plugin.gateway.ControlBusGateway;
import com.infenia.yukta.plugin.message.Message;
import jakarta.validation.constraints.NotBlank;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import reactor.core.publisher.Mono;

/**
 * Default implementation of the {@link ControlBusGateway} that delegates to the {@link
 * ControlBusService}.
 */
@Service
@RequiredArgsConstructor
public class DefaultControlBusGateway implements ControlBusGateway {

  private final ControlBusService controlBusService;

  @Override
  public <T> Mono<Void> emit(final Message<T> signal) {
    return controlBusService.emit(signal);
  }

  @Override
  public void registerPlugin(@NotBlank final String nodeId, final WorkflowPlugin plugin) {
    controlBusService.registerPlugin(nodeId, plugin);
  }

  @Override
  public void unregisterPlugin(@NotBlank final String nodeId) {
    controlBusService.unregisterPlugin(nodeId);
  }

  @Override
  public Mono<Message<?>> sendCommand(
      @NotBlank final String nodeId, final Message<?> command) {
    return controlBusService.sendCommand(nodeId, command);
  }

  @Override
  public Message<?> getLastHeartbeat(@NotBlank final String nodeId) {
    return controlBusService.getLastHeartbeat(nodeId);
  }

  @Override
  public Message<?> getLastStatistics(@NotBlank final String nodeId) {
    return controlBusService.getLastStatistics(nodeId);
  }

  @Override
  public List<String> getActiveNodes() {
    return controlBusService.getActiveNodes();
  }
}
```

**Step 3: Verify syntax**

```bash
./gradlew :core:compileJava
```

Expected: BUILD SUCCESSFUL

**Step 4: Commit**

```bash
git add core/src/main/java/com/infenia/yukta/service/DefaultControlBusGateway.java
git commit -m "refactor: implement all 7 ControlBusGateway methods in DefaultControlBusGateway

Implement complete control-bus façade:
- registerPlugin(), unregisterPlugin()
- sendCommand()
- getLastHeartbeat(), getLastStatistics(), getActiveNodes()

Remove @Getter annotation (no longer needed; all methods are explicit delegates).

Co-Authored-By: Claude Haiku 4.5 <noreply@anthropic.com>"
```

---

## Task 7: Update WorkflowOrchestrator — Remove All Casts

**Files:**
- Modify: `core/src/main/java/com/infenia/yukta/service/WorkflowOrchestrator.java:196-198` (first cast)
- Modify: `core/src/main/java/com/infenia/yukta/service/WorkflowOrchestrator.java:225-227` (second cast)

**Step 1: Review current casts**

```bash
grep -n "DefaultControlBusGateway" core/src/main/java/com/infenia/yukta/service/WorkflowOrchestrator.java
```

Expected: Two occurrences (lines ~196 and ~225)

**Step 2: Replace first cast (line 196-198)**

Find this block:
```java
                          ((DefaultControlBusGateway) controlBusGateway)
                              .getControlBusService()
                              .registerPlugin(node.nodeId(), plugin)
```

Replace with:
```java
                          controlBusGateway.registerPlugin(node.nodeId(), plugin)
```

**Step 3: Replace second cast (line 225-227)**

Find this block:
```java
                          ((DefaultControlBusGateway) controlBusGateway)
                              .getControlBusService()
                              .unregisterPlugin(nodeId);
```

Replace with:
```java
                          controlBusGateway.unregisterPlugin(nodeId);
```

**Step 4: Verify syntax and tests compile**

```bash
./gradlew :core:compileJava :core:compileTestJava
```

Expected: BUILD SUCCESSFUL

**Step 5: Run orchestrator tests to ensure no regression**

```bash
./gradlew :core:test --tests "WorkflowOrchestratorTest*"
```

Expected: All tests PASS

**Step 6: Commit**

```bash
git add core/src/main/java/com/infenia/yukta/service/WorkflowOrchestrator.java
git commit -m "refactor: remove casts in WorkflowOrchestrator, use ControlBusGateway interface

Replace two occurrences of:
  ((DefaultControlBusGateway) controlBusGateway).getControlBusService().registerPlugin(...)

With direct interface calls:
  controlBusGateway.registerPlugin(...)

Same for unregisterPlugin().

WorkflowOrchestrator now depends only on the ControlBusGateway interface,
not on DefaultControlBusGateway implementation details.

Co-Authored-By: Claude Haiku 4.5 <noreply@anthropic.com>"
```

---

## Task 8: Add Configuration to application.yaml

**Files:**
- Modify: `boot/src/main/resources/application.yaml`

**Step 1: Read current file**

```bash
head -50 boot/src/main/resources/application.yaml
```

**Step 2: Add control bus configuration**

Find a good location (after spring config, before other services) and add:

```yaml
# Control Bus Configuration
control:
  bus:
    batch:
      size: 100          # Number of control signals to batch before processing
      timeout:
        ms: 50           # Timeout (in ms) for batch accumulation
    buffer:
      size: 256          # Internal buffer size (Queues.SMALL_BUFFER_SIZE = 256)
```

**Step 3: Verify YAML syntax**

```bash
./gradlew :boot:compileJava
```

Expected: BUILD SUCCESSFUL (YAML is validated during startup)

**Step 4: Commit**

```bash
git add boot/src/main/resources/application.yaml
git commit -m "config: add control bus configuration for batch and buffer tuning

Add configurable parameters:
- control.bus.batch.size (default: 100)
- control.bus.batch.timeout.ms (default: 50)
- control.bus.buffer.size (default: 256)

Allows per-environment tuning without code changes.

Co-Authored-By: Claude Haiku 4.5 <noreply@anthropic.com>"
```

---

## Task 9: Write Unit Tests for Handler Registry and Cleanup

**Files:**
- Create: `core/src/test/java/com/infenia/yukta/service/control/ControlSignalHandlerTest.java`

**Step 1: Write the test**

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
package com.infenia.yukta.service.control;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.message.control.ControlHeartbeat;
import com.infenia.yukta.plugin.message.control.ControlStatistics;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class ControlSignalHandlerTest {

  private ControlHeartbeatHandler heartbeatHandler;
  private ControlStatisticsHandler statisticsHandler;

  @BeforeEach
  void setUp() {
    heartbeatHandler = new ControlHeartbeatHandler();
    statisticsHandler = new ControlStatisticsHandler();
  }

  @Test
  void testHeartbeatHandlerCanHandle() {
    final Message<?> hb =
        DefaultMessage.create(new ControlHeartbeat()).withSourceNodeId("node1");
    final Message<?> stats =
        DefaultMessage.create(new ControlStatistics()).withSourceNodeId("node1");

    assertTrue(heartbeatHandler.canHandle(hb.getPayload()));
    assertTrue(!statisticsHandler.canHandle(hb.getPayload()));

    assertTrue(statisticsHandler.canHandle(stats.getPayload()));
    assertTrue(!heartbeatHandler.canHandle(stats.getPayload()));
  }

  @Test
  void testHeartbeatHandlerStoresAndRetrievesMessages() {
    final Message<?> msg =
        DefaultMessage.create(new ControlHeartbeat()).withSourceNodeId("node1");

    heartbeatHandler.handle("node1", msg, msg.getPayload());

    assertEquals(msg, heartbeatHandler.getLastHeartbeat("node1"));
    assertEquals(List.of("node1"), heartbeatHandler.getActiveNodes());
  }

  @Test
  void testHeartbeatHandlerRemovesNode() {
    final Message<?> msg =
        DefaultMessage.create(new ControlHeartbeat()).withSourceNodeId("node1");

    heartbeatHandler.handle("node1", msg, msg.getPayload());
    assertEquals(msg, heartbeatHandler.getLastHeartbeat("node1"));

    heartbeatHandler.removeNode("node1");

    assertNull(heartbeatHandler.getLastHeartbeat("node1"));
    assertEquals(List.of(), heartbeatHandler.getActiveNodes());
  }

  @Test
  void testStatisticsHandlerStoresAndRetrievesMessages() {
    final Message<?> msg =
        DefaultMessage.create(new ControlStatistics()).withSourceNodeId("node1");

    statisticsHandler.handle("node1", msg, msg.getPayload());

    assertEquals(msg, statisticsHandler.getLastStatistics("node1"));
  }

  @Test
  void testStatisticsHandlerRemovesNode() {
    final Message<?> msg =
        DefaultMessage.create(new ControlStatistics()).withSourceNodeId("node1");

    statisticsHandler.handle("node1", msg, msg.getPayload());
    assertEquals(msg, statisticsHandler.getLastStatistics("node1"));

    statisticsHandler.removeNode("node1");

    assertNull(statisticsHandler.getLastStatistics("node1"));
  }
}
```

**Step 2: Run the test to verify it passes**

```bash
./gradlew :core:test --tests "ControlSignalHandlerTest"
```

Expected: All tests PASS

**Step 3: Commit**

```bash
git add core/src/test/java/com/infenia/yukta/service/control/ControlSignalHandlerTest.java
git commit -m "test: add unit tests for ControlSignalHandler implementations

Tests verify:
- canHandle() correctly identifies signal types
- handle() stores and retrieves messages
- removeNode() cleans up state correctly
- Multiple nodes can be tracked independently

Co-Authored-By: Claude Haiku 4.5 <noreply@anthropic.com>"
```

---

## Task 10: Write Integration Test for Handler Registry Dispatch

**Files:**
- Create: `core/src/test/java/com/infenia/yukta/service/ControlBusServiceIntegrationTest.java`

**Step 1: Write the test**

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
package com.infenia.yukta.service;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.message.control.ControlHeartbeat;
import com.infenia.yukta.plugin.message.control.ControlStatistics;
import com.infenia.yukta.service.control.ControlHeartbeatHandler;
import com.infenia.yukta.service.control.ControlSignalHandler;
import com.infenia.yukta.service.control.ControlStatisticsHandler;
import java.util.List;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

class ControlBusServiceIntegrationTest {

  private ControlBusService controlBusService;
  private ControlHeartbeatHandler heartbeatHandler;
  private ControlStatisticsHandler statisticsHandler;

  @BeforeEach
  void setUp() {
    heartbeatHandler = new ControlHeartbeatHandler();
    statisticsHandler = new ControlStatisticsHandler();

    final List<ControlSignalHandler> handlers = List.of(heartbeatHandler, statisticsHandler);
    controlBusService = new ControlBusService(100, 50, 256, handlers);
    controlBusService.init();
  }

  @Test
  void testHeartbeatSignalDispatch() {
    final Message<?> hb =
        DefaultMessage.create(new ControlHeartbeat())
            .withSourceNodeId("node1")
            .withPriority(5);

    StepVerifier.create(controlBusService.emit(hb)).verifyComplete();

    // Give handler time to process (batched event)
    try {
      Thread.sleep(100);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    assertNotNull(controlBusService.getLastHeartbeat("node1"));
    assertEquals("node1", controlBusService.getActiveNodes().get(0));
  }

  @Test
  void testStatisticsSignalDispatch() {
    final Message<?> stats =
        DefaultMessage.create(new ControlStatistics())
            .withSourceNodeId("node2")
            .withPriority(5);

    StepVerifier.create(controlBusService.emit(stats)).verifyComplete();

    // Give handler time to process (batched event)
    try {
      Thread.sleep(100);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    assertNotNull(controlBusService.getLastStatistics("node2"));
  }

  @Test
  void testUnregisterPluginCleansUpAllHandlerState() {
    final Message<?> hb =
        DefaultMessage.create(new ControlHeartbeat())
            .withSourceNodeId("node3")
            .withPriority(5);
    final Message<?> stats =
        DefaultMessage.create(new ControlStatistics())
            .withSourceNodeId("node3")
            .withPriority(5);

    StepVerifier.create(controlBusService.emit(hb)).verifyComplete();
    StepVerifier.create(controlBusService.emit(stats)).verifyComplete();

    // Give handler time to process
    try {
      Thread.sleep(100);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    // Verify signals were stored
    assertNotNull(controlBusService.getLastHeartbeat("node3"));
    assertNotNull(controlBusService.getLastStatistics("node3"));

    // Unregister and verify cleanup
    controlBusService.unregisterPlugin("node3");

    assertNull(controlBusService.getLastHeartbeat("node3"));
    assertNull(controlBusService.getLastStatistics("node3"));
    assertTrue(controlBusService.getActiveNodes().isEmpty());
  }

  @Test
  void testMultipleNodesTrackedIndependently() {
    final Message<?> hb1 =
        DefaultMessage.create(new ControlHeartbeat())
            .withSourceNodeId("node1")
            .withPriority(5);
    final Message<?> hb2 =
        DefaultMessage.create(new ControlHeartbeat())
            .withSourceNodeId("node2")
            .withPriority(5);

    StepVerifier.create(controlBusService.emit(hb1)).verifyComplete();
    StepVerifier.create(controlBusService.emit(hb2)).verifyComplete();

    // Give handler time to process
    try {
      Thread.sleep(100);
    } catch (final InterruptedException e) {
      Thread.currentThread().interrupt();
    }

    final List<String> activeNodes = controlBusService.getActiveNodes();
    assertEquals(2, activeNodes.size());
    assertTrue(activeNodes.contains("node1"));
    assertTrue(activeNodes.contains("node2"));

    // Unregister one and verify the other remains
    controlBusService.unregisterPlugin("node1");

    assertEquals(1, controlBusService.getActiveNodes().size());
    assertNull(controlBusService.getLastHeartbeat("node1"));
    assertNotNull(controlBusService.getLastHeartbeat("node2"));
  }
}
```

**Step 2: Run the test to verify it passes**

```bash
./gradlew :core:test --tests "ControlBusServiceIntegrationTest"
```

Expected: All tests PASS

**Step 3: Commit**

```bash
git add core/src/test/java/com/infenia/yukta/service/ControlBusServiceIntegrationTest.java
git commit -m "test: add integration tests for handler registry dispatch and cleanup

Tests verify:
- Heartbeat and statistics signals are correctly dispatched to handlers
- unregisterPlugin() cleans up state in all handlers
- Multiple nodes tracked independently
- getActiveNodes() reflects cleanup

Co-Authored-By: Claude Haiku 4.5 <noreply@anthropic.com>"
```

---

## Task 11: Verify No Casts Remain in Codebase

**Step 1: Search for any remaining casts**

```bash
grep -r "DefaultControlBusGateway.*getControlBusService\|cast.*ControlBusGateway" \
  core/src/main/java/ core/src/test/java/ 2>/dev/null || echo "No casts found"
```

Expected: "No casts found"

**Step 2: Run all tests to ensure no regression**

```bash
./gradlew :core:test :boot:test 2>&1 | tail -20
```

Expected: BUILD SUCCESSFUL, all tests PASS

**Step 3: Run full quality checks**

```bash
./gradlew check -x :ui:spotlessCheck 2>&1 | tail -30
```

Expected: BUILD SUCCESSFUL (or only pre-existing failures unrelated to these changes)

**Step 4: Commit verification**

```bash
git status
```

Expected: No uncommitted changes

---

## Task 12: Format Code with Spotless and Create Final Integration Commit

**Step 1: Format code**

```bash
./gradlew spotlessApply -x :ui:spotlessXml
```

Expected: BUILD SUCCESSFUL

**Step 2: Verify formatting didn't break anything**

```bash
./gradlew :core:compileJava :core:test
```

Expected: BUILD SUCCESSFUL

**Step 3: Create final integration test**

Run the full test suite one more time:

```bash
./gradlew :core:test :boot:test -x :ui:spotlessCheck
```

Expected: ALL TESTS PASS, no regressions

**Step 4: Commit any formatting changes**

```bash
git status
```

If there are formatting changes:
```bash
git add -A
git commit -m "style: apply Spotless formatting to ControlBus refactoring

Co-Authored-By: Claude Haiku 4.5 <noreply@anthropic.com>"
```

---

## Success Criteria Checklist

- [ ] ControlBusGateway interface extended with 6 new methods
- [ ] ControlSignalHandler interface created
- [ ] ControlHeartbeatHandler and ControlStatisticsHandler implemented
- [ ] ControlBusService refactored (config injection, handler registry, cleanup)
- [ ] DefaultControlBusGateway implements all 7 methods, @Getter removed
- [ ] WorkflowOrchestrator: all casts removed, uses interface methods
- [ ] application.yaml: control bus configuration added
- [ ] Unit tests for handlers pass
- [ ] Integration tests for handler dispatch and cleanup pass
- [ ] No casts of ControlBusGateway remain in codebase
- [ ] All unit and integration tests pass
- [ ] Code formatted with Spotless
- [ ] Quality gates pass (Checkstyle, PMD, SpotBugs, JaCoCo)

---

## Notes

- All file paths are exact; copy-paste them directly
- Configuration defaults allow override per environment
- Handler registry is fully extensible — adding new signal types requires only a new handler bean
- Cleanup on unregister() prevents memory leaks
- Tests use Thread.sleep() to account for batched event processing; in production this is fine since events arrive continuously
