// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import com.infenia.yukta.plugin.core.LogLevel;
import java.time.Instant;
import java.util.Map;
import java.util.Objects;
import org.jspecify.annotations.Nullable;

/**
 * Something that happened to a running workflow, as seen by an {@link ExecutionSupervisor}.
 *
 * <p>The feed is best effort: a supervisor that cannot keep up misses events. It is told with an
 * {@link EventsMissed} before the next event it receives, and a rule that must not miss a case
 * reads the durable execution log from that point.
 */
public sealed interface ExecutionEvent {

  /**
   * The execution the event belongs to.
   *
   * @return the execution id
   */
  String executionId();

  /**
   * When the event happened.
   *
   * @return the time
   */
  Instant timestamp();

  /**
   * A node changed status.
   *
   * @param executionId the execution
   * @param workflowId the workflow it runs
   * @param nodeId the node
   * @param module the node's plugin type
   * @param status the new status, a {@code TaskStatus} name
   * @param metadata what the node reported
   * @param timestamp when it happened
   */
  record NodeStatusChanged(
      String executionId,
      String workflowId,
      String nodeId,
      String module,
      String status,
      Map<String, Object> metadata,
      Instant timestamp)
      implements ExecutionEvent {

    /** Keeps an immutable copy of the metadata. */
    public NodeStatusChanged {
      metadata = metadata == null ? Map.of() : Map.copyOf(metadata);
    }
  }

  /**
   * A workflow changed status.
   *
   * @param executionId the execution
   * @param workflowId the workflow it runs
   * @param status the new status, a {@code TaskStatus} name
   * @param timestamp when it happened
   */
  record WorkflowStatusChanged(
      String executionId, String workflowId, String status, Instant timestamp)
      implements ExecutionEvent {}

  /**
   * A control command was applied or rejected.
   *
   * @param executionId the execution the command addressed
   * @param command the command name, for example {@code PauseWorkflowCommand}
   * @param actor who sent it, or {@code null} when it came through the API
   * @param applied true if it was applied, false if it was rejected
   * @param reason why it was rejected, otherwise {@code null}
   * @param timestamp when it happened
   */
  record ControlOutcome(
      String executionId,
      String command,
      @Nullable String actor,
      boolean applied,
      @Nullable String reason,
      Instant timestamp)
      implements ExecutionEvent {}

  /**
   * A node wrote a log line.
   *
   * @param executionId the execution
   * @param nodeId the node
   * @param line the line, a single line of text
   * @param level its level, as the plugin reported it or the engine rated it
   * @param timestamp when it happened
   */
  record LogLine(String executionId, String nodeId, String line, LogLevel level, Instant timestamp)
      implements ExecutionEvent {

    /** Rejects a missing level, so a line is never shown with a level nobody gave it. */
    public LogLine {
      Objects.requireNonNull(level, "level");
    }
  }

  /**
   * Events were dropped for this supervisor because it fell behind; it may have missed a status
   * change or a log line. Injected by the host, never published to the feed.
   *
   * @param count how many events were dropped since the last event it received
   * @param timestamp when the gap was noticed
   */
  record EventsMissed(long count, Instant timestamp) implements ExecutionEvent {

    /**
     * A gap belongs to no single execution.
     *
     * @return an empty id
     */
    @Override
    public String executionId() {
      return "";
    }
  }
}
