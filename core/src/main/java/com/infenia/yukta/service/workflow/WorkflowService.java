// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow;

import com.infenia.yukta.service.control.SupervisedControl;
import com.infenia.yukta.validation.SessionId;
import com.infenia.yukta.validation.WorkflowId;
import java.util.Map;
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
  private final SupervisedControl control;

  /**
   * Starts a workflow, safely retryable with an idempotency key.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param payload the trigger payload
   * @param idempotencyKey the client's key, or null; a repeat within the window returns the first
   *     execution
   * @return a Mono with the id of the run
   * @throws IllegalArgumentException if session or workflow not found
   */
  public Mono<String> start(
      @SessionId final String sessionId,
      @WorkflowId final String workflowId,
      final Map<String, Object> payload,
      final String idempotencyKey) {
    log.atInfo()
        .addKeyValue(LOG_KEY_SESSION_ID, sessionId)
        .addKeyValue(LOG_KEY_WORKFLOW_ID, workflowId)
        .log("Starting workflow");
    return control
        .startWorkflow(sessionId, workflowId, payload, idempotencyKey)
        .doOnSuccess(
            executionId ->
                log.atDebug()
                    .addKeyValue(LOG_KEY_SESSION_ID, sessionId)
                    .addKeyValue(LOG_KEY_WORKFLOW_ID, workflowId)
                    .addKeyValue(LOG_KEY_EXECUTION_ID, executionId)
                    .log("Workflow execution initiated via control bus"))
        .doOnError(
            err ->
                log.atError()
                    .setCause(err)
                    .addKeyValue(LOG_KEY_SESSION_ID, sessionId)
                    .addKeyValue(LOG_KEY_WORKFLOW_ID, workflowId)
                    .log("Failed to start workflow"));
  }
}
