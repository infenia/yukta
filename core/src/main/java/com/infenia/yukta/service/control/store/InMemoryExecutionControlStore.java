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

  /** Live runs per (session, workflow), so the by-workflow lookups do not scan every run. */
  private final Map<WorkflowKey, Map<String, ExecutionControl>> byWorkflow =
      new ConcurrentHashMap<>();

  private record WorkflowKey(String sessionId, String workflowId) {}

  private static WorkflowKey keyOf(final ExecutionControl c) {
    return new WorkflowKey(c.sessionId(), c.workflowId());
  }

  @Override
  public void save(final ExecutionControl control) {
    final ExecutionControl previous = byExecutionId.put(control.executionId(), control);
    if (previous != null) {
      unindex(previous);
    }
    byWorkflow
        .computeIfAbsent(keyOf(control), k -> new ConcurrentHashMap<>())
        .put(control.executionId(), control);
  }

  @Override
  public void remove(final String executionId) {
    final ExecutionControl removed = byExecutionId.remove(executionId);
    if (removed != null) {
      unindex(removed);
    }
  }

  private void unindex(final ExecutionControl c) {
    byWorkflow.computeIfPresent(
        keyOf(c),
        (k, runs) -> {
          runs.remove(c.executionId());
          return runs.isEmpty() ? null : runs;
        });
  }

  @Override
  public Optional<ExecutionControl> findByExecutionId(final String executionId) {
    return Optional.ofNullable(byExecutionId.get(executionId));
  }

  @Override
  public Optional<ExecutionControl> findActiveByWorkflow(
      final String sessionId, final String workflowId) {
    return findAllActiveByWorkflow(sessionId, workflowId).stream().findFirst();
  }

  @Override
  public java.util.List<ExecutionControl> findAllActiveByWorkflow(
      final String sessionId, final String workflowId) {
    final var runs = byWorkflow.get(new WorkflowKey(sessionId, workflowId));
    return runs == null ? java.util.List.of() : java.util.List.copyOf(runs.values());
  }

  @Override
  public java.util.List<ExecutionControl> findAll() {
    return java.util.List.copyOf(byExecutionId.values());
  }
}
