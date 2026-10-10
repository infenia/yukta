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
import java.util.function.BooleanSupplier;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.event.Level;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** Stream operators every node's output goes through: timeout and task status tracking. */
@Slf4j
@UtilityClass
@SuppressWarnings({"PMD.AtLeastOneConstructor", "PMD.OnlyOneReturn"})
public class NodeStreams {

  /**
   * Applies the node's idle timeout, then reports RUNNING on subscribe and SUCCESS or FAILURE on
   * the terminal signal. A failing stream reports FAILURE exactly once.
   *
   * @param node the node the stream belongs to
   * @param timeout the maximum idle time, counted from the node's first activity
   * @param tracker receives the task status events
   * @param executionId the execution identifier
   * @param source the node's output stream
   * @param activity the node's activity clock; the timeout counts idle time since its last touch
   * @param paused tells whether the node or run is paused, which holds the timeout clock
   * @return the decorated stream
   */
  public Flux<Message<?>> withTimeoutAndTracking(
      final WorkflowNode node,
      final Duration timeout,
      final TaskTrackerService tracker,
      final String executionId,
      final Flux<Message<?>> source,
      final NodeActivity activity,
      final BooleanSupplier paused) {
    final String nodeId = node.nodeId();
    return activity
        .guard(source.doOnNext(_ -> activity.touch()), timeout, Schedulers.parallel(), paused)
        .doOnError(
            TimeoutException.class,
            e ->
                log.atWarn()
                    .setMessage("Stream timeout exceeded for node: {} after duration: {}")
                    .addArgument(nodeId)
                    .addArgument(timeout)
                    .setCause(e)
                    .log())
        .doOnSubscribe(_ -> emit(tracker, executionId, node, TaskStatus.RUNNING))
        .doOnComplete(() -> emit(tracker, executionId, node, TaskStatus.SUCCESS))
        .doOnCancel(() -> emit(tracker, executionId, node, TaskStatus.CANCELLED))
        .doOnError(
            error -> {
              // the node that failed logs the error; the ones behind it only note the consequence
              log.atLevel(error instanceof UpstreamFailure ? Level.DEBUG : Level.ERROR)
                  .setMessage("Task failed - execution: {}, node: {}")
                  .addArgument(executionId)
                  .addArgument(nodeId)
                  .setCause(error)
                  .log();
              emit(tracker, executionId, node, failureStatus(error));
            });
  }

  /**
   * Maps a stream error to the node's final status.
   *
   * @param error the error that ended the stream
   * @return UPSTREAM_FAILED for an error that came from a node before this one, TIMED_OUT for an
   *     idle timeout, otherwise FAILURE
   */
  public TaskStatus failureStatus(final Throwable error) {
    if (error instanceof UpstreamFailure) {
      return TaskStatus.UPSTREAM_FAILED;
    }
    return error instanceof TimeoutException ? TaskStatus.TIMED_OUT : TaskStatus.FAILURE;
  }

  private void emit(
      final TaskTrackerService tracker,
      final String executionId,
      final WorkflowNode node,
      final TaskStatus status) {
    tracker.emitTaskStatusEvent(
        executionId, node.nodeId(), node.type(), status, Collections.emptyMap());
  }
}
