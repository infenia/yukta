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
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.model.monitoring.WorkflowExecutionSummary;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import reactor.core.publisher.Mono;

/**
 * Provider for workflow execution operations. Handles workflow definition retrieval, trigger
 * operations, and status monitoring.
 */
public interface WorkflowExecutionProvider {

  /**
   * Get workflow definition.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return Mono containing workflow definition
   */
  Mono<WorkflowDefinition> getWorkflowDetails(String sessionId, String workflowId);

  /**
   * Trigger a workflow execution.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param payloadJson optional JSON string for trigger payload
   * @return Mono containing the execution ID
   */
  Mono<String> triggerWorkflow(String sessionId, String workflowId, String payloadJson);

  /**
   * Get status of a workflow execution.
   *
   * @param sessionId the session identifier
   * @param executionId the execution identifier
   * @return Mono containing workflow execution summary
   */
  Mono<WorkflowExecutionSummary> getWorkflowStatus(String sessionId, String executionId);
}
