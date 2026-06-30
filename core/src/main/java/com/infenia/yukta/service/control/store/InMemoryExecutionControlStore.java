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
