// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow;

import com.infenia.yukta.model.session.TaskResponse;
import com.infenia.yukta.model.workflow.WorkflowExecution;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.validation.SessionId;
import com.infenia.yukta.validation.WorkflowId;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.validation.annotation.Validated;
import reactor.core.publisher.Mono;

/** Service for orchestrating workflow execution. */
@Slf4j
@Service
@Validated
@RequiredArgsConstructor
public class WorkflowService {

  /** Log key for session ID. */
  private static final String LOG_KEY_SESSION_ID = "sessionId";

  /** Log key for workflow ID. */
  private static final String LOG_KEY_WORKFLOW_ID = "workflowId";

  /** Log key for execution ID. */
  private static final String LOG_KEY_EXECUTION_ID = "executionId";

  /** The control bus gateway for orchestrating workflow execution. */
  private final ControlBusGateway controlBus;

  /**
   * Validate and run a specific workflow for a session.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return a Mono containing the WorkflowExecution
   * @throws IllegalArgumentException if session or workflow not found
   */
  public Mono<WorkflowExecution> validateAndStartWorkflow(
      @SessionId final String sessionId, @WorkflowId final String workflowId) {
    log.atInfo()
        .addKeyValue(LOG_KEY_SESSION_ID, sessionId)
        .addKeyValue(LOG_KEY_WORKFLOW_ID, workflowId)
        .log("Validating and triggering workflow");

    return controlBus
        .startWorkflow(sessionId, workflowId)
        .map(
            executionId -> {
              log.atDebug()
                  .addKeyValue(LOG_KEY_SESSION_ID, sessionId)
                  .addKeyValue(LOG_KEY_WORKFLOW_ID, workflowId)
                  .addKeyValue(LOG_KEY_EXECUTION_ID, executionId)
                  .log("Workflow execution initiated via control bus");
              final Mono<TaskResponse> resultMono =
                  Mono.just(new TaskResponse("PENDING", "Workflow execution in progress"));
              return new WorkflowExecution(executionId, resultMono);
            })
        .doOnSuccess(
            _ ->
                log.atInfo()
                    .addKeyValue(LOG_KEY_SESSION_ID, sessionId)
                    .addKeyValue(LOG_KEY_WORKFLOW_ID, workflowId)
                    .log("Workflow validation and start completed"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue(LOG_KEY_SESSION_ID, sessionId)
                    .addKeyValue(LOG_KEY_WORKFLOW_ID, workflowId)
                    .log("Failed to validate and start workflow"));
  }
}
