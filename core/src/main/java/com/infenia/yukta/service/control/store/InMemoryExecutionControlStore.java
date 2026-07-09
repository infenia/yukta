// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.store;

import com.infenia.yukta.service.control.ExecutionControl;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import lombok.NoArgsConstructor;

/**
 * In-memory implementation of {@link ExecutionControlStore}.
 *
 * <p>Thread-safe storage using ConcurrentHashMap. Suitable for single-node deployments or
 * co-located execution contexts.
 */
@NoArgsConstructor
public final class InMemoryExecutionControlStore implements ExecutionControlStore {

  /** Map of execution ID to execution control state. */
  private final Map<String, ExecutionControl> byExecutionId = new ConcurrentHashMap<>();

  @Override
  public void save(final ExecutionControl control) {
    byExecutionId.put(control.executionId(), control);
  }

  @Override
  public void remove(final String executionId) {
    byExecutionId.remove(executionId);
  }

  @Override
  public Optional<ExecutionControl> findByExecutionId(final String executionId) {
    return Optional.ofNullable(byExecutionId.get(executionId));
  }

  @Override
  public Optional<ExecutionControl> findActiveByWorkflow(
      final String sessionId, final String workflowId) {
    return byExecutionId.values().stream()
        .filter(c -> c.sessionId().equals(sessionId) && c.workflowId().equals(workflowId))
        .findFirst();
  }

  @Override
  public java.util.List<ExecutionControl> findAllActiveByWorkflow(
      final String sessionId, final String workflowId) {
    return byExecutionId.values().stream()
        .filter(c -> c.sessionId().equals(sessionId) && c.workflowId().equals(workflowId))
        .toList();
  }
}
