// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.runtime.store;

import com.infenia.yukta.service.runtime.ExecutionControl;
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
   * Finds all active executions for a workflow and session.
   *
   * @param sessionId the session ID
   * @param workflowId the workflow ID
   * @return a list of all active execution handles (empty if none found)
   */
  java.util.List<ExecutionControl> findAllActiveByWorkflow(String sessionId, String workflowId);

  /**
   * Every live execution.
   *
   * @return the handles, empty if none
   */
  java.util.List<ExecutionControl> findAll();
}
