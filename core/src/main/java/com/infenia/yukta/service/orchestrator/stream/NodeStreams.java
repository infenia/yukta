// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.stream;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import lombok.experimental.UtilityClass;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.slf4j.event.Level;
import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;

/** Stream operators every node's output goes through: timeout and task status tracking. */
@Slf4j
@UtilityClass
@SuppressWarnings({"PMD.AtLeastOneConstructor", "PMD.OnlyOneReturn"})
public class NodeStreams {

  /** Metadata key of a failed node's status event holding the reason. */
  public static final String ERROR_KEY = "error";

  /** Longest reason kept on a status event; a longer one keeps its head and tail. */
  private static final int MAX_ERROR_CHARS = 8_000;

  /**
   * Applies the node's idle timeout, then reports RUNNING at the node's first activity and SUCCESS
   * or FAILURE on the terminal signal. A failing stream reports FAILURE exactly once.
   *
   * @param node the node the stream belongs to
   * @param timeout the maximum idle time, counted from the node's first activity
   * @param tracker receives the task status events
   * @param executionId the execution identifier
   * @param source the node's output stream
   * @param activity the node's activity clock; the timeout counts idle time since its last touch
   * @param paused tells whether the node or run is paused, which holds the timeout clock
   * @param deadline the longest the node may run from its first activity, or null for none
   * @return the decorated stream
   */
  public Flux<Message<?>> withTimeoutAndTracking(
      final WorkflowNode node,
      final Duration timeout,
      final TaskTrackerService tracker,
      final String executionId,
      final Flux<Message<?>> source,
      final NodeActivity activity,
      final BooleanSupplier paused,
      @Nullable final Duration deadline) {
    final String nodeId = node.nodeId();
    // RUNNING at the node's first activity, not at wiring, so its times are its own; a node that
    // ends without any activity still passes through RUNNING.
    final AtomicBoolean running = new AtomicBoolean();
    final Runnable markRunning =
        () -> {
          if (running.compareAndSet(false, true)) {
            emit(tracker, executionId, node, TaskStatus.RUNNING);
          }
        };
    activity.onFirstTouch(markRunning);
    return activity
        .guard(
            source.doOnNext(_ -> activity.touch()),
            timeout,
            deadline,
            Schedulers.parallel(),
            paused)
        .doOnError(
            TimeoutException.class,
            e ->
                log.atWarn()
                    .setMessage("Stream timeout exceeded for node: {} after duration: {}")
                    .addArgument(nodeId)
                    .addArgument(timeout)
                    .setCause(e)
                    .log())
        .doOnComplete(
            () -> {
              markRunning.run();
              emit(tracker, executionId, node, TaskStatus.SUCCESS);
            })
        .doOnCancel(
            () -> {
              markRunning.run();
              emit(tracker, executionId, node, TaskStatus.CANCELLED);
            })
        .doOnError(
            error -> {
              // the node that failed logs the error; the ones behind it only note the consequence
              log.atLevel(error instanceof UpstreamFailure ? Level.DEBUG : Level.ERROR)
                  .setMessage("Task failed - execution: {}, node: {}")
                  .addArgument(executionId)
                  .addArgument(nodeId)
                  .setCause(error)
                  .log();
              markRunning.run();
              final TaskStatus status = failureStatus(error);
              emit(
                  tracker,
                  executionId,
                  node,
                  status,
                  status == TaskStatus.UPSTREAM_FAILED
                      ? Collections.emptyMap()
                      : Map.of(ERROR_KEY, describe(error)));
            });
  }

  /**
   * The reason a node failed, as reported on its status event: the error's message (its class name
   * when it has none), cut to head and tail when long, since a build tool puts the first errors at
   * the top and the report location at the bottom.
   *
   * @param error the error that ended the node
   * @return the text, at most {@value #MAX_ERROR_CHARS} characters plus the cut marker
   */
  /* default */ static String describe(final Throwable error) {
    final String message = error.getMessage();
    final String text =
        message == null || message.isBlank() ? error.getClass().getSimpleName() : message;
    // ponytail: fixed head/tail split; make it configurable if a plugin needs a different shape.
    if (text.length() <= MAX_ERROR_CHARS) {
      return text;
    }
    final int half = MAX_ERROR_CHARS / 2;
    return text.substring(0, half)
        + "\n… ["
        + (text.length() - MAX_ERROR_CHARS)
        + " chars cut] …\n"
        + text.substring(text.length() - half);
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
    emit(tracker, executionId, node, status, Collections.emptyMap());
  }

  private void emit(
      final TaskTrackerService tracker,
      final String executionId,
      final WorkflowNode node,
      final TaskStatus status,
      final Map<String, Object> metadata) {
    tracker.emitTaskStatusEvent(executionId, node.nodeId(), node.type(), status, metadata);
  }
}
