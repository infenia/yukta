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
package com.infenia.yukta.service.orchestrator.tracker;

import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.validation.NodeId;
import com.infenia.yukta.validation.SessionId;
import com.infenia.yukta.validation.WorkflowId;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

/** Service interface for tracking the progress of workflows and tasks. */
@SuppressWarnings("PMD.UseObjectForClearerAPI")
public interface TaskTrackerService {

  /**
   * Start tracking a new workflow execution for a session.
   *
   * @param executionId the unique execution identifier
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param nodeIds the list of node IDs in the workflow DAG
   * @return a Mono that completes when the workflow tracking is started
   */
  Mono<Void> startWorkflow(
      @NotBlank String executionId,
      @SessionId String sessionId,
      @WorkflowId String workflowId,
      @NotEmpty List<String> nodeIds);

  /**
   * Update the status of a specific node.
   *
   * @param executionId the execution identifier
   * @param nodeId the ID of the node
   * @param module the module name
   * @param status the new status
   * @return a Mono that completes when the task status is updated
   */
  Mono<Void> updateTaskStatus(
      @NotBlank String executionId,
      @NodeId String nodeId,
      @NotBlank @Size(max = 256) String module,
      @NotBlank @Size(max = 256) String status);

  /**
   * Update the status and metadata of a specific node.
   *
   * @param executionId the execution identifier
   * @param nodeId the ID of the node
   * @param module the module name
   * @param status the new status
   * @param metadata the task metadata
   * @return a Mono that completes when the task status is updated
   */
  Mono<Void> updateTaskStatus(
      @NotBlank String executionId,
      @NodeId String nodeId,
      @NotBlank @Size(max = 256) String module,
      @NotBlank @Size(max = 256) String status,
      @NotNull Map<String, Object> metadata);

  /**
   * Finish the workflow execution.
   *
   * @param executionId the execution identifier
   * @param status the final status
   * @return a Mono that completes when the workflow is finished
   */
  Mono<Void> finishWorkflow(@NotBlank String executionId, @NotBlank @Size(max = 256) String status);

  /**
   * Append a log line to the live output.
   *
   * @param executionId the execution identifier
   * @param line the log line
   * @return a Mono that completes when the log line is appended
   */
  Mono<Void> appendLog(@NotBlank String executionId, @NotBlank @Size(max = 16_384) String line);

  /**
   * Get the current progress of a workflow execution.
   *
   * @param sessionId the session identifier
   * @param executionId the execution identifier
   * @return the workflow progress
   */
  WorkflowProgress getProgress(@SessionId String sessionId, @NotBlank String executionId);

  /**
   * Get workflow progress by execution ID only (independent of session).
   *
   * @param executionId the execution identifier
   * @return the workflow progress, or null if not found
   */
  WorkflowProgress getProgressByExecutionId(@NotBlank String executionId);

  /**
   * Get the latest execution ID for a workflow in a session.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return the execution identifier or null if none
   */
  String getLatestExecutionId(@SessionId String sessionId, @WorkflowId String workflowId);

  /**
   * Get the log stream for an execution.
   *
   * @param executionId the execution identifier
   * @return the log flux
   */
  Flux<String> getLogStream(@NotBlank String executionId);

  /**
   * Get the status stream for an execution.
   *
   * @param executionId the execution identifier
   * @return the status flux
   */
  Flux<WorkflowProgress> getStatusStream(@NotBlank String executionId);

  /**
   * Get history of executions for a session.
   *
   * @param sessionId the session identifier
   * @return list of workflow execution summaries
   */
  List<WorkflowExecutionSummary> getHistory(@SessionId String sessionId);

  /**
   * List all active sessions being tracked.
   *
   * @return list of session IDs
   */
  List<String> getActiveSessions();

  /**
   * Remove tracking data for a session.
   *
   * @param sessionId the session identifier
   */
  void removeSession(@SessionId String sessionId);

  /**
   * Emit a task status event for asynchronous processing.
   *
   * @param executionId the execution identifier
   * @param nodeId the node identifier
   * @param module the module name
   * @param status the new status
   * @param metadata the task metadata
   */
  void emitTaskStatusEvent(
      @NotBlank String executionId,
      @NotBlank String nodeId,
      @NotBlank @Size(max = 256) String module,
      @NotBlank @Size(max = 256) String status,
      @NotNull Map<String, Object> metadata);

  /**
   * Emit a workflow status event for asynchronous processing.
   *
   * @param executionId the execution identifier
   * @param status the final status
   */
  void emitWorkflowStatusEvent(
      @NotBlank String executionId, @NotBlank @Size(max = 256) String status);

  /**
   * Emit a log event for asynchronous processing.
   *
   * @param executionId the execution identifier
   * @param line the log line
   */
  void emitLogEvent(@NotBlank String executionId, @NotBlank @Size(max = 16_384) String line);
}
