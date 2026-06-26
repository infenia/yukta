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
package com.infenia.yukta.service.orchestrator.stream;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.service.execution.status.ExecutionStatusPublisher;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeoutException;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import reactor.core.publisher.Flux;

/**
 * Fluent builder for constructing reactive streams with unified stream patterns across all plugin
 * types (Trigger, Processor, Terminal).
 *
 * <p>StreamBuilder encapsulates common stream transformation patterns: timeout handling with
 * TimeoutException mapping, task status tracking (RUNNING → SUCCESS/FAILURE), and error handling
 * with ExecutionStatusPublisher emission.
 *
 * <p>Example usage:
 *
 * <pre>{@code
 * Flux<Message<?>> stream = new StreamBuilder(
 *     node,
 *     Duration.ofSeconds(10),
 *     taskTrackerService,
 *     controlBus)
 *   .withSource(sourceFlux)
 *   .withTimeout()
 *   .withTaskTracking("exec-001", "session-001")
 *   .withErrorHandling("exec-001")
 *   .build();
 * }</pre>
 */
@Slf4j
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

  /** The workflow node being processed. */
  private final WorkflowNode node;

  /** The operation timeout duration. */
  private final Duration timeout;

  /** The task tracker service for status events. */
  private final TaskTrackerService taskTrackerService;

  /** The execution status publisher for error emission. */
  private final ExecutionStatusPublisher statusPublisher;

  /** The source stream for this builder. */
  @Nullable private Flux<Message<?>> sourceStream;

  /** Flag to apply timeout to the stream. */
  private boolean applyTimeout;

  /** Flag to apply task tracking to the stream. */
  private boolean applyTaskTracking;

  /** Flag to apply error handling to the stream. */
  private boolean applyErrors;

  /** The execution ID for status tracking. */
  @Nullable private String executionId;

  /**
   * Creates a new StreamBuilder instance.
   *
   * @param node the workflow node
   * @param timeout the operation timeout duration
   * @param taskTrackerService the task tracker service for status events
   * @param statusPublisher the execution status publisher for error emission
   */
  public StreamBuilder(
      final WorkflowNode node,
      final Duration timeout,
      final TaskTrackerService taskTrackerService,
      final ExecutionStatusPublisher statusPublisher) {
    this.node = node;
    this.timeout = timeout;
    this.taskTrackerService = taskTrackerService;
    this.statusPublisher = statusPublisher;
    log.atDebug()
        .setMessage("StreamBuilder created for node: {}, timeout: {}")
        .addArgument(node.nodeId())
        .addArgument(timeout)
        .log();
  }

  /**
   * Sets the source stream for this builder.
   *
   * @param stream the Flux source stream
   * @return this builder for fluent chaining
   */
  public StreamBuilder withSource(final Flux<Message<?>> stream) {
    this.sourceStream = stream;
    log.atDebug()
        .setMessage("Source stream configured for node: {}")
        .addArgument(node.nodeId())
        .log();
    return this;
  }

  /**
   * Enables timeout handling with TimeoutException mapping.
   *
   * @return this builder for fluent chaining
   */
  public StreamBuilder withTimeout() {
    this.applyTimeout = true;
    log.atDebug()
        .setMessage("Timeout handling enabled for node: {} with duration: {}")
        .addArgument(node.nodeId())
        .addArgument(timeout)
        .log();
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
    log.atDebug()
        .setMessage("Task tracking enabled for execution: {} on node: {}")
        .addArgument(execId)
        .addArgument(node.nodeId())
        .log();
    return this;
  }

  /**
   * Enables error handling with ExecutionStatusPublisher emission of errors.
   *
   * @param execId the execution identifier for error tracking
   * @return this builder for fluent chaining
   */
  public StreamBuilder withErrorHandling(final String execId) {
    this.applyErrors = true;
    this.executionId = execId;
    log.atDebug()
        .setMessage("Error handling enabled for execution: {} on node: {}")
        .addArgument(execId)
        .addArgument(node.nodeId())
        .log();
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
      log.atDebug()
          .setMessage("No source stream provided for node: {}, using empty stream")
          .addArgument(node.nodeId())
          .log();
      stream = Flux.empty();
    }

    // Apply timeout wrapping
    if (applyTimeout) {
      log.atDebug()
          .setMessage("Applying timeout transformation for node: {}")
          .addArgument(node.nodeId())
          .log();
      stream = applyTimeoutTransform(stream);
    }

    // Apply task tracking
    if (applyTaskTracking) {
      log.atDebug()
          .setMessage("Applying task tracking transformation for execution: {}")
          .addArgument(executionId)
          .log();
      stream = applyTaskTrackingTransform(stream);
    }

    // Apply error handling
    if (applyErrors) {
      log.atDebug()
          .setMessage("Applying error handling transformation for execution: {}")
          .addArgument(executionId)
          .log();
      stream = applyErrorsTransform(stream);
    }

    log.atDebug().setMessage("Stream build complete for node: {}").addArgument(node.nodeId()).log();
    return stream;
  }

  /**
   * Applies timeout transformation to the stream.
   *
   * @param flux the source flux
   * @return the flux with timeout applied
   */
  private Flux<Message<?>> applyTimeoutTransform(final Flux<Message<?>> flux) {
    return flux.timeout(timeout)
        .onErrorMap(
            TimeoutException.class,
            e -> {
              log.atWarn()
                  .setMessage("Stream timeout exceeded for node: {} after duration: {}")
                  .addArgument(node.nodeId())
                  .addArgument(timeout)
                  .setCause(e)
                  .log();
              return e;
            });
  }

  /**
   * Applies task tracking transformation to the stream.
   *
   * @param flux the source flux
   * @return the flux with task tracking applied
   */
  private Flux<Message<?>> applyTaskTrackingTransform(final Flux<Message<?>> flux) {
    return flux.doOnSubscribe(
            sub -> {
              log.atDebug()
                  .setMessage("Task RUNNING - execution: {}, node: {}")
                  .addArgument(executionId)
                  .addArgument(node.nodeId())
                  .log();
              taskTrackerService.emitTaskStatusEvent(
                  executionId,
                  node.nodeId(),
                  DEFAULT_TASK_ID,
                  STATUS_RUNNING,
                  Collections.emptyMap());
            })
        .doOnComplete(
            () -> {
              log.atDebug()
                  .setMessage("Task SUCCESS - execution: {}, node: {}")
                  .addArgument(executionId)
                  .addArgument(node.nodeId())
                  .log();
              taskTrackerService.emitTaskStatusEvent(
                  executionId,
                  node.nodeId(),
                  DEFAULT_TASK_ID,
                  STATUS_SUCCESS,
                  Collections.emptyMap());
            })
        .doOnError(
            error -> {
              log.atError()
                  .setMessage("Task FAILURE - execution: {}, node: {}")
                  .addArgument(executionId)
                  .addArgument(node.nodeId())
                  .setCause(error)
                  .log();
              taskTrackerService.emitTaskStatusEvent(
                  executionId,
                  node.nodeId(),
                  DEFAULT_TASK_ID,
                  STATUS_FAILURE,
                  Collections.emptyMap());
            });
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
          log.atError()
              .setMessage(
                  "Stream error occurred for execution: {}, node: {} - emitting failure status")
              .addArgument(executionId)
              .addArgument(node.nodeId())
              .setCause(error)
              .log();
          taskTrackerService.emitTaskStatusEvent(
              executionId, node.nodeId(), DEFAULT_TASK_ID, STATUS_FAILURE, Collections.emptyMap());
        });
  }
}
