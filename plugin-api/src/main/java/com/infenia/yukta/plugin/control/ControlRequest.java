// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import org.jspecify.annotations.Nullable;

/**
 * A control command waiting for {@link ExecutionSupervisor#review} approval.
 *
 * @param command the command
 * @param actor who sent it: a supervisor id, {@code key:<name>} for a named API key, or {@code
 *     null} when it came through the API with the single key or no key
 * @param sessionId the session of the run the command addresses, or {@code null} when the command
 *     belongs to no run or the run is not known
 * @param workflowId the workflow of that run, or {@code null} likewise
 * @param nodeId the node the command acts on, or {@code null} when it acts on the whole run or on
 *     no run; for an input answer or escalation, the node of the request
 */
public record ControlRequest(
    ExecutionControlCommand command,
    @Nullable String actor,
    @Nullable String sessionId,
    @Nullable String workflowId,
    @Nullable String nodeId) {

  /**
   * A request for a command that acts on the whole run, or whose node is the command's own.
   *
   * @param command the command
   * @param actor who sent it
   * @param sessionId the session of the run, or {@code null}
   * @param workflowId the workflow of the run, or {@code null}
   */
  public ControlRequest(
      final ExecutionControlCommand command,
      final @Nullable String actor,
      final @Nullable String sessionId,
      final @Nullable String workflowId) {
    this(command, actor, sessionId, workflowId, command.nodeId());
  }
}
