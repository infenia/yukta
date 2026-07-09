// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import java.time.Instant;
import java.util.Set;

/**
 * Snapshot of execution status at a point in time.
 *
 * @param executionId the unique execution identifier
 * @param sessionId the session owning this execution
 * @param workflowId the workflow being executed
 * @param isGlobalPaused true if the global pause valve is paused
 * @param pausedNodes set of node IDs that are paused
 * @param skippedNodes set of node IDs that are marked for skip
 * @param stoppedNodes set of node IDs that are stopped
 * @param createdAt execution start time
 * @param lastUpdatedAt last state change time
 */
public record WorkflowExecutionSnapshot(
    String executionId,
    String sessionId,
    String workflowId,
    boolean isGlobalPaused,
    Set<String> pausedNodes,
    Set<String> skippedNodes,
    Set<String> stoppedNodes,
    Instant createdAt,
    Instant lastUpdatedAt) {

  /** Compact constructor to enforce immutable defensive copies. */
  public WorkflowExecutionSnapshot {
    pausedNodes = pausedNodes != null ? Set.copyOf(pausedNodes) : Set.of();
    skippedNodes = skippedNodes != null ? Set.copyOf(skippedNodes) : Set.of();
    stoppedNodes = stoppedNodes != null ? Set.copyOf(stoppedNodes) : Set.of();
  }
}
