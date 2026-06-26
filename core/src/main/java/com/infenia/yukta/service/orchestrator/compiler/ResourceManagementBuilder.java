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
package com.infenia.yukta.service.orchestrator.compiler;

import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import reactor.core.Disposable;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Scheduler;

/**
 * Fluent builder for managing execution resources in reactive streams.
 *
 * <p>ResourceManagementBuilder manages the Mono.using() pattern for resource lifecycle, handling
 * disposables collection, timeouts, terminal completion, and cleanup. It emits workflow status
 * events to DefaultTaskTrackerService on completion or error, and executes connector runnables on
 * subscription in reverse order.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * ResourceManagementBuilder builder = new ResourceManagementBuilder(
 *     tracker,
 *     configStore,
 *     Schedulers.boundedElastic());
 *
 * Mono<Void> execution = builder
 *     .withDisposables(disposables)
 *     .withTerminals(terminals)
 *     .withConnectors(connectors)
 *     .withExecutionTimeout("session-001", "exec-001")
 *     .build();
 * }</pre>
 */
@Slf4j
public class ResourceManagementBuilder {

  /** Status value indicating successful execution. */
  private static final String STATUS_SUCCESS = "SUCCESS";

  /** Status value indicating error execution. */
  private static final String STATUS_ERROR = "ERROR";

  /** Default timeout in seconds for workflow execution. */
  private static final long DEFAULT_TIMEOUT = 3600L;

  /** The task tracker service for emitting workflow status events. */
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  private final TaskTrackerService tracker;

  /** The session configuration store for retrieving execution settings. */
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  private final SessionConfigStore configStore;

  /** The scheduler for timeout operations. */
  private final Scheduler scheduler;

  /** List of disposables to manage for resource cleanup. */
  private List<Disposable> disposables = new ArrayList<>();

  /** List of terminal monos to execute on workflow completion. */
  @Nullable private List<Mono<Void>> terminals;

  /** List of connector runnable to execute. */
  @Nullable private List<Runnable> connectors;

  /** The session ID for the execution. */
  @Nullable private String sessionId;

  /** The execution ID. */
  @Nullable private String executionId;

  /**
   * Creates a new ResourceManagementBuilder instance.
   *
   * @param tracker the task tracker service for emitting workflow status events
   * @param configStore the session config store for retrieving execution timeouts
   * @param scheduler the scheduler for timeout operations
   */
  public ResourceManagementBuilder(
      final TaskTrackerService tracker,
      final SessionConfigStore configStore,
      final Scheduler scheduler) {
    this.tracker = tracker;
    this.configStore = configStore;
    this.scheduler = scheduler;
  }

  /**
   * Sets the list of disposables to manage.
   *
   * @param disposableList the list of disposables, or null to skip setting
   * @return this builder for fluent chaining
   */
  public ResourceManagementBuilder withDisposables(final List<Disposable> disposableList) {
    if (disposableList != null) {
      this.disposables = new ArrayList<>(disposableList);
    }
    return this;
  }

  /**
   * Sets the list of terminal monos to execute.
   *
   * @param terminalList the list of terminal monos, or null to skip setting
   * @return this builder for fluent chaining
   */
  public ResourceManagementBuilder withTerminals(final List<Mono<Void>> terminalList) {
    if (terminalList != null) {
      this.terminals = new ArrayList<>(terminalList);
    }
    return this;
  }

  /**
   * Sets the list of connector runnables to execute on subscription.
   *
   * @param connectorList the list of runnables, or null to skip setting
   * @return this builder for fluent chaining
   */
  public ResourceManagementBuilder withConnectors(final List<Runnable> connectorList) {
    if (connectorList != null) {
      this.connectors = new ArrayList<>(connectorList);
    }
    return this;
  }

  /**
   * Sets the execution timeout parameters.
   *
   * @param sessionIdParam the session identifier
   * @param executionIdParam the execution identifier
   * @return this builder for fluent chaining
   */
  public ResourceManagementBuilder withExecutionTimeout(
      final String sessionIdParam, final String executionIdParam) {
    this.sessionId = sessionIdParam;
    this.executionId = executionIdParam;
    log.atDebug()
        .addKeyValue("sessionId", sessionIdParam)
        .addKeyValue("executionId", executionIdParam)
        .log("Set execution timeout parameters");
    return this;
  }

  /**
   * Builds the resource-managed Mono execution.
   *
   * <p>The resulting Mono uses Mono.using() to manage the lifecycle of disposables. On
   * subscription, connector runnables are executed in reverse order. On completion or error,
   * workflow status events are emitted (SUCCESS or ERROR), and all disposables are cleaned up.
   *
   * @return a Mono<Void> that manages the execution lifecycle
   */
  public Mono<Void> buildAndExecute() {
    return executeWithTimeout();
  }

  /**
   * Executes the workflow with the configured timeout.
   *
   * @return a Mono<Void> that manages the execution with timeout
   */
  private Mono<Void> executeWithTimeout() {
    log.atDebug()
        .addKeyValue("sessionId", sessionId)
        .addKeyValue("executionId", executionId)
        .log("Starting execution with resource management");

    final Mono<Long> timeoutMono =
        (sessionId != null)
            ? configStore.getExecutionTimeout(sessionId)
            : Mono.just(DEFAULT_TIMEOUT);

    return timeoutMono.flatMap(
        timeout -> {
          log.atDebug()
              .addKeyValue("executionTimeout", timeout)
              .addKeyValue("unit", "seconds")
              .log("Retrieved execution timeout");

          final Mono<Void> terminalMono = buildTerminalMono();
          final Mono<Void> timedMono = terminalMono.timeout(Duration.ofSeconds(timeout), scheduler);

          return Mono.using(
                  () -> disposables,
                  _ -> timedMono.doOnSubscribe(_ -> runConnectors()),
                  this::cleanup)
              .doOnSuccess(_ -> emitStatus(STATUS_SUCCESS))
              .onErrorResume(
                  error -> {
                    emitStatus(STATUS_ERROR);
                    return Mono.error(error);
                  });
        });
  }

  /**
   * Builds the terminal mono from the list of terminals.
   *
   * @return a Mono<Void> that executes all terminals
   */
  private Mono<Void> buildTerminalMono() {
    final Mono<Void> result;
    if (terminals != null && !terminals.isEmpty()) {
      result = Mono.whenDelayError(terminals);
    } else {
      result = Mono.empty();
    }
    return result;
  }

  /** Runs connector runnables in reverse order on subscription. */
  private void runConnectors() {
    if (connectors != null && !connectors.isEmpty()) {
      log.atDebug()
          .addKeyValue("connectorCount", connectors.size())
          .log("Executing connectors in reverse order");
      for (int i = connectors.size() - 1; i >= 0; i--) {
        try {
          connectors.get(i).run();
          log.atDebug().addKeyValue("connectorIndex", i).log("Connector executed successfully");
        } catch (
            @SuppressWarnings("PMD.AvoidCatchingGenericException")
            final Exception e) {
          log.atError()
              .addKeyValue("connectorIndex", i)
              .addKeyValue("exception", e.getClass().getSimpleName())
              .log("Error executing connector", e);
        }
      }
    }
  }

  /**
   * Emits a workflow status event.
   *
   * @param status the status to emit
   */
  private void emitStatus(final String status) {
    if (executionId != null) {
      log.atInfo()
          .addKeyValue("executionId", executionId)
          .addKeyValue("status", status)
          .log("Emitting workflow status event");
      tracker.emitWorkflowStatusEvent(executionId, status);
    }
  }

  /**
   * Cleans up all disposables.
   *
   * @param resource the disposables resource to clean up
   */
  private void cleanup(final List<Disposable> resource) {
    if (resource.isEmpty()) {
      log.atDebug().log("No disposables to clean up");
      return;
    }

    log.atDebug().addKeyValue("disposableCount", resource.size()).log("Starting resource cleanup");

    int disposed = 0;
    for (final Disposable disposable : resource) {
      try {
        disposable.dispose();
        disposed++;
      } catch (
          @SuppressWarnings("PMD.AvoidCatchingGenericException")
          final Exception e) {
        log.atError()
            .addKeyValue("exception", e.getClass().getSimpleName())
            .addKeyValue("disposedCount", disposed)
            .log("Error disposing resource", e);
      }
    }

    log.atDebug()
        .addKeyValue("disposedCount", disposed)
        .addKeyValue("totalCount", resource.size())
        .log("Resource cleanup completed");
  }
}
