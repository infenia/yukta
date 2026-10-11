// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import java.time.Instant;
import java.util.Map;
import org.jspecify.annotations.Nullable;

/**
 * Something that happened to a running workflow, as seen by an {@link ExecutionSupervisor}.
 *
 * <p>The feed is best effort: a supervisor that cannot keep up misses events.
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
   * @param line the line
   * @param timestamp when it happened
   */
  record LogLine(String executionId, String nodeId, String line, Instant timestamp)
      implements ExecutionEvent {}
}
