// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import java.time.Instant;
import org.jspecify.annotations.Nullable;

/**
 * What became of one command a supervisor emitted.
 *
 * @param time when it was decided
 * @param supervisorId the supervisor, or the attachment id of an attached one
 * @param outcome {@code applied}, {@code rejected}, {@code dry-run} or {@code rate-limited}
 * @param command the command name, such as {@code PauseWorkflowCommand}
 * @param executionId the run it addressed
 * @param reason why it was not applied, or null
 * @param nodeId the node it addressed, or null for a run-level or server command
 * @param cause what made the supervisor send it: {@code event}, {@code answers}, {@code tick} or
 *     {@code initiative} (acted on its own); null for a supervisor that gives no cause
 * @param key the directive's key; the runs of one selector share it before {@code #}
 * @param because the reason the supervisor gave for sending it, or null
 * @param workflowId the workflow of the run, or null when the run is not known
 */
public record SupervisorDecision(
    Instant time,
    String supervisorId,
    String outcome,
    String command,
    String executionId,
    @Nullable String reason,
    @Nullable String nodeId,
    @Nullable String cause,
    @Nullable String key,
    @Nullable String because,
    @Nullable String workflowId) {

  /**
   * A decision with no node, cause, key, given reason or workflow.
   *
   * @param time when it was decided
   * @param supervisorId the supervisor
   * @param outcome the outcome
   * @param command the command name
   * @param executionId the run
   * @param reason why it was not applied, or null
   */
  public SupervisorDecision(
      final Instant time,
      final String supervisorId,
      final String outcome,
      final String command,
      final String executionId,
      final @Nullable String reason) {
    this(time, supervisorId, outcome, command, executionId, reason, null, null, null, null, null);
  }
}
