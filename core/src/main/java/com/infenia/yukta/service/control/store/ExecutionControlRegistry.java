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
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

/**
 * Registry of all currently running workflow executions.
 *
 * <p>The orchestrator saves an {@link ExecutionControl} on start and removes it in the {@code
 * doFinally} callback. The {@code DirectiveDispatcher} queries this registry to locate the
 * execution that a control command targets.
 *
 * <p>Delegates to a pluggable {@link ExecutionControlStore} for actual storage. By default, uses
 * in-memory storage.
 */
@Component
@RequiredArgsConstructor
public class ExecutionControlRegistry {

  /** The execution control store for persisting execution state. */
  private final ExecutionControlStore store;

  /**
   * Saves an execution control handle.
   *
   * @param control the execution handle to save
   */
  public void register(final ExecutionControl control) {
    store.save(control);
  }

  /**
   * Removes the execution handle for the given execution ID.
   *
   * @param executionId the execution to remove
   */
  public void unregister(final String executionId) {
    store.remove(executionId);
  }

  /**
   * Looks up an execution by its unique identifier.
   *
   * @param executionId the execution identifier
   * @return an Optional containing the handle, or empty if not found
   */
  public Optional<ExecutionControl> findByExecutionId(final String executionId) {
    return store.findByExecutionId(executionId);
  }

  /**
   * Finds the active execution for a given workflow and session.
   *
   * <p>If more than one execution is active for the same workflow (which should not happen in
   * normal operation), the first found is returned.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return an Optional containing the active handle, or empty if no match
   */
  public Optional<ExecutionControl> findActiveByWorkflow(
      final String sessionId, final String workflowId) {
    return store.findActiveByWorkflow(sessionId, workflowId);
  }

  /**
   * Finds all active executions for a given workflow and session.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return a list of all active execution handles (empty if none found)
   */
  public java.util.List<ExecutionControl> findAllActiveByWorkflow(
      final String sessionId, final String workflowId) {
    return store.findAllActiveByWorkflow(sessionId, workflowId);
  }
}
