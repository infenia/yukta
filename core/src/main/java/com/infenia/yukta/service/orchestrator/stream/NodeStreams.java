// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.time.Duration;
import java.util.Collections;
import java.util.concurrent.TimeoutException;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;

/** Stream operators every node's output goes through: timeout and task status tracking. */
@Slf4j
@UtilityClass
@SuppressWarnings("PMD.AtLeastOneConstructor")
public class NodeStreams {

  /** Task identifier used for the node-level status events. */
  private static final String DEFAULT_TASK_ID = "default";

  /**
   * Applies the node timeout, then reports RUNNING on subscribe and SUCCESS or FAILURE on the
   * terminal signal. A failing stream reports FAILURE exactly once.
   *
   * @param node the node the stream belongs to
   * @param timeout the maximum time between elements
   * @param tracker receives the task status events
   * @param executionId the execution identifier
   * @param source the node's output stream
   * @return the decorated stream
   */
  public Flux<Message<?>> withTimeoutAndTracking(
      final WorkflowNode node,
      final Duration timeout,
      final TaskTrackerService tracker,
      final String executionId,
      final Flux<Message<?>> source) {
    final String nodeId = node.nodeId();
    return source
        .timeout(timeout)
        .doOnError(
            TimeoutException.class,
            e ->
                log.atWarn()
                    .setMessage("Stream timeout exceeded for node: {} after duration: {}")
                    .addArgument(nodeId)
                    .addArgument(timeout)
                    .setCause(e)
                    .log())
        .doOnSubscribe(_ -> emit(tracker, executionId, nodeId, TaskStatus.RUNNING))
        .doOnComplete(() -> emit(tracker, executionId, nodeId, TaskStatus.SUCCESS))
        .doOnError(
            error -> {
              log.atError()
                  .setMessage("Task FAILURE - execution: {}, node: {}")
                  .addArgument(executionId)
                  .addArgument(nodeId)
                  .setCause(error)
                  .log();
              emit(tracker, executionId, nodeId, TaskStatus.FAILURE);
            });
  }

  private void emit(
      final TaskTrackerService tracker,
      final String executionId,
      final String nodeId,
      final TaskStatus status) {
    tracker.emitTaskStatusEvent(
        executionId, nodeId, DEFAULT_TASK_ID, status, Collections.emptyMap());
  }
}
