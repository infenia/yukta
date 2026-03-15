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
import com.infenia.yukta.plugin.gateway.ControlBusGateway;
import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.message.control.ControlError;
import com.infenia.yukta.service.TaskTrackerService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeoutException;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

/**
 * Fluent builder for constructing reactive streams with unified stream patterns across all plugin
 * types (Trigger, Processor, Terminal).
 *
 * <p>StreamBuilder encapsulates common stream transformation patterns: timeout handling with
 * TimeoutException mapping, task status tracking (RUNNING → SUCCESS/FAILURE), and error handling
 * with ControlBusGateway emission.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * Flux<Message<?>> stream = new StreamBuilder(
 *     node,
 *     Duration.ofSeconds(10),
 *     taskTracker,
 *     controlBus)
 *   .withSource(sourceFlux)
 *   .withTimeout()
 *   .withTaskTracking("exec-001", "session-001")
 *   .withErrorHandling("exec-001")
 *   .build();
 * }</pre>
 */
@SuppressFBWarnings("EI_EXPOSE_REP2")
public class StreamBuilder {

  /** Default task identifier constant. */
  private static final String DEFAULT_TASK_ID = "default";

  /** Status constant: RUNNING. */
  private static final String STATUS_RUNNING = "RUNNING";

  /** Status constant: SUCCESS. */
  private static final String STATUS_SUCCESS = "SUCCESS";

  /** Status constant: FAILURE. */
  private static final String STATUS_FAILURE = "FAILURE";

  private final Node node;
  private final Duration timeout;
  private final TaskTrackerService taskTracker;
  private final ControlBusGateway controlBusGateway;

  @Nullable private Flux<Message<?>> sourceStream;

  private boolean applyTimeout;
  private boolean applyTaskTracking;
  private boolean applyErrors;

  @Nullable private String executionId;

  /**
   * Creates a new StreamBuilder instance.
   *
   * @param node the workflow node
   * @param timeout the operation timeout duration
   * @param taskTracker the task tracker service for status events
   * @param controlBusGateway the control bus gateway for error emission
   */
  public StreamBuilder(
      final Node node,
      final Duration timeout,
      final TaskTrackerService taskTracker,
      final ControlBusGateway controlBusGateway) {
    this.node = node;
    this.timeout = timeout;
    this.taskTracker = taskTracker;
    this.controlBusGateway = controlBusGateway;
  }

  /**
   * Sets the source stream for this builder.
   *
   * @param stream the Flux source stream
   * @return this builder for fluent chaining
   */
  public StreamBuilder withSource(final Flux<Message<?>> stream) {
    this.sourceStream = stream;
    return this;
  }

  /**
   * Enables timeout handling with TimeoutException mapping.
   *
   * @return this builder for fluent chaining
   */
  public StreamBuilder withTimeout() {
    this.applyTimeout = true;
    return this;
  }

  /**
   * Enables task status tracking with RUNNING, SUCCESS, and FAILURE events.
   *
   * @param execId the execution identifier
   * @return this builder for fluent chaining
   */
  public StreamBuilder withTaskTracking(final String execId) {
    this.applyTaskTracking = true;
    this.executionId = execId;
    return this;
  }

  /**
   * Enables error handling with ControlBusGateway emission of errors.
   *
   * @param execId the execution identifier for error tracking
   * @return this builder for fluent chaining
   */
  public StreamBuilder withErrorHandling(final String execId) {
    this.applyErrors = true;
    this.executionId = execId;
    return this;
  }

  /**
   * Builds and returns the configured Flux stream with all registered transformations applied in
   * order.
   *
   * @return the constructed Flux stream
   */
  public Flux<Message<?>> build() {
    Flux<Message<?>> stream = sourceStream;

    if (stream == null) {
      stream = Flux.empty();
    }

    // Apply timeout wrapping
    if (applyTimeout) {
      stream = applyTimeoutTransform(stream);
    }

    // Apply task tracking
    if (applyTaskTracking) {
      stream = applyTaskTrackingTransform(stream);
    }

    // Apply error handling
    if (applyErrors) {
      stream = applyErrorsTransform(stream);
    }

    return stream;
  }

  /**
   * Applies timeout transformation to the stream.
   *
   * @param flux the source flux
   * @return the flux with timeout applied
   */
  private Flux<Message<?>> applyTimeoutTransform(final Flux<Message<?>> flux) {
    return flux.timeout(timeout).onErrorMap(TimeoutException.class, e -> e);
  }

  /**
   * Applies task tracking transformation to the stream.
   *
   * @param flux the source flux
   * @return the flux with task tracking applied
   */
  private Flux<Message<?>> applyTaskTrackingTransform(final Flux<Message<?>> flux) {
    return flux.doOnSubscribe(
            sub ->
                taskTracker.emitTaskStatusEvent(
                    executionId,
                    node.nodeId(),
                    DEFAULT_TASK_ID,
                    STATUS_RUNNING,
                    Collections.emptyMap()))
        .doOnComplete(
            () ->
                taskTracker.emitTaskStatusEvent(
                    executionId,
                    node.nodeId(),
                    DEFAULT_TASK_ID,
                    STATUS_SUCCESS,
                    Collections.emptyMap()))
        .doOnError(
            error ->
                taskTracker.emitTaskStatusEvent(
                    executionId,
                    node.nodeId(),
                    DEFAULT_TASK_ID,
                    STATUS_FAILURE,
                    Collections.emptyMap()));
  }

  /**
   * Applies error handling transformation to the stream.
   *
   * @param flux the source flux
   * @return the flux with error handling applied
   */
  private Flux<Message<?>> applyErrorsTransform(final Flux<Message<?>> flux) {
    return flux.doOnError(
        error -> {
          // Emit FAILURE status to task tracker
          taskTracker.emitTaskStatusEvent(
              executionId, node.nodeId(), DEFAULT_TASK_ID, STATUS_FAILURE, Collections.emptyMap());

          // Emit control error to control bus
          final ControlError controlError =
              new ControlError(
                  node.nodeId(), executionId, error.getClass().getSimpleName(), error.getMessage());
          controlBusGateway
              .emit(
                  DefaultMessage.create(null, controlError)
                      .withSourceNodeId(node.nodeId())
                      .withControl(true)
                      .withPriority(10))
              .subscribe();
        });
  }
}
