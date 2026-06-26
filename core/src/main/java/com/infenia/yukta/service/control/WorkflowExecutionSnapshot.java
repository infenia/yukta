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
