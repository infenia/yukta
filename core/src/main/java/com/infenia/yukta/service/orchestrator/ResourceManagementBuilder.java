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

import com.infenia.yukta.service.TaskTrackerService;
import com.infenia.yukta.service.session.SessionConfigStore;
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
 * events to TaskTrackerService on completion or error, and executes connector runnables on
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

  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_ERROR = "ERROR";
  private static final long DEFAULT_TIMEOUT = 3600L;

  @SuppressFBWarnings("EI_EXPOSE_REP2")
  private final TaskTrackerService tracker;

  @SuppressFBWarnings("EI_EXPOSE_REP2")
  private final SessionConfigStore configStore;

  private final Scheduler scheduler;

  private List<Disposable> disposables = new ArrayList<>();

  @Nullable private List<Mono<Void>> terminals;

  @Nullable private List<Runnable> connectors;

  @Nullable private String sessionId;

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
  public Mono<Void> build() {
    return executeWithTimeout();
  }

  /**
   * Executes the workflow with the configured timeout.
   *
   * @return a Mono<Void> that manages the execution with timeout
   */
  private Mono<Void> executeWithTimeout() {
    // Retrieve the execution timeout from the session config store
    final Mono<Long> timeoutMono =
        (sessionId != null)
            ? configStore.getExecutionTimeout(sessionId)
            : Mono.just(DEFAULT_TIMEOUT);

    return timeoutMono.flatMap(
        timeout -> {
          // Build the terminal mono from the list of terminals
          final Mono<Void> terminalMono = buildTerminalMono();

          // Apply timeout to the terminal execution
          final Mono<Void> timedMono = terminalMono.timeout(Duration.ofSeconds(timeout), scheduler);

          // Run connectors on subscription and manage resources
          return Mono.using(
                  () -> disposables,
                  unused -> timedMono.doOnSubscribe(s -> runConnectors()),
                  this::cleanup)
              .doOnSuccess(v -> emitStatus(STATUS_SUCCESS))
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
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void runConnectors() {
    if (connectors != null && !connectors.isEmpty()) {
      // Execute connectors in reverse order
      for (int i = connectors.size() - 1; i >= 0; i--) {
        try {
          connectors.get(i).run();
        } catch (final Exception e) {
          log.error("Error executing connector at index {}", i, e);
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
      tracker.emitWorkflowStatusEvent(executionId, status);
    }
  }

  /**
   * Cleans up all disposables.
   *
   * @param resource the disposables resource to clean up
   */
  @SuppressWarnings("PMD.AvoidCatchingGenericException")
  private void cleanup(final List<Disposable> resource) {
    for (final Disposable disposable : resource) {
      try {
        disposable.dispose();
      } catch (final Exception e) {
        log.error("Error disposing resource", e);
      }
    }
  }
}
