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

/**
 * Abstraction for storing and retrieving execution control handles.
 *
 * <p>Allows pluggable backends (in-memory, Redis, database) for tracking live workflow executions.
 */
public interface ExecutionControlStore {

  /**
   * Stores an execution control handle.
   *
   * @param control the execution handle to store
   */
  void save(ExecutionControl control);

  /**
   * Removes an execution control handle by ID.
   *
   * @param executionId the execution ID to remove
   */
  void remove(String executionId);

  /**
   * Retrieves an execution control handle by ID.
   *
   * @param executionId the execution ID
   * @return an Optional containing the handle, or empty if not found
   */
  Optional<ExecutionControl> findByExecutionId(String executionId);

  /**
   * Finds the active execution for a workflow and session.
   *
   * <p>If multiple executions are active for the same workflow, the first found is returned.
   *
   * @param sessionId the session ID
   * @param workflowId the workflow ID
   * @return an Optional containing the active handle, or empty if not found
   */
  Optional<ExecutionControl> findActiveByWorkflow(String sessionId, String workflowId);
}
