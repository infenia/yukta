// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.WorkflowStartResult;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowExecution;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.control.gateway.StartIdempotency;
import com.infenia.yukta.service.session.SessionService;
import com.infenia.yukta.service.workflow.ConfigRedactor;
import com.infenia.yukta.service.workflow.WorkflowService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Handles workflow execution operations including definition retrieval, start operations, status
 * snapshots, and execution history.
 */
@Slf4j
@Component
public class WorkflowExecutionProvider {

  /** Service for executing workflows and managing their lifecycle. */
  private final WorkflowService workflowService;

  /** Service for managing session state and configuration. */
  private final SessionService sessionService;

  /** Gateway for workflow control and observability operations. */
  private final ControlBusGateway controlBus;

  /** Hides literal secrets in the definitions handed to an agent. */
  private final ConfigRedactor configRedactor;

  /** Merges keyless starts of one session and workflow made within a short window. */
  private final StartIdempotency autoKey;

  /**
   * Creates the provider.
   *
   * @param workflowService runs workflows
   * @param sessionService reads sessions
   * @param controlBus observes and controls executions
   * @param configRedactor hides literal secrets
   * @param autoKeyWindow how long a start without a key stands in for its repeats; 0 turns it off
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public WorkflowExecutionProvider(
      final WorkflowService workflowService,
      final SessionService sessionService,
      final ControlBusGateway controlBus,
      final ConfigRedactor configRedactor,
      @Value("${yukta.mcp.start-dedupe-window:30s}") final Duration autoKeyWindow) {
    this.workflowService = workflowService;
    this.sessionService = sessionService;
    this.controlBus = controlBus;
    this.configRedactor = configRedactor;
    this.autoKey = new StartIdempotency(autoKeyWindow);
  }

  /**
   * Get workflow definition.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return Mono containing workflow definition
   */
  public Mono<WorkflowDefinition> getWorkflowDetails(
      final String sessionId, final String workflowId) {
    return sessionService
        .getSessionWorkflow(sessionId, workflowId)
        .map(configRedactor::redact)
        .switchIfEmpty(
            Mono.error(
                () ->
                    new IllegalArgumentException(
                        "Workflow not found: "
                            + workflowId
                            + " in session "
                            + sessionId
                            + ". Use list_sessions to see the sessions and get_session_details"
                            + " for the workflows of one.")));
  }

  /**
   * Start a workflow execution.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @return Mono containing the start result with the new execution ID
   */
  public Mono<WorkflowStartResult> startWorkflow(final String sessionId, final String workflowId) {
    return startWorkflow(sessionId, workflowId, null);
  }

  /**
   * Start a workflow execution that is safe to repeat.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param idempotencyKey optional key; a repeat with the same key (same session and workflow,
   *     within the server's window) returns the first execution instead of starting another
   * @return Mono containing the start result with the new execution ID
   */
  public Mono<WorkflowStartResult> startWorkflow(
      final String sessionId, final String workflowId, final String idempotencyKey) {
    final boolean keyed = idempotencyKey != null && !idempotencyKey.isBlank();
    // Without a key the server derives one from the session and workflow: a model that repeats a
    // start it got no answer to is merged for a short window; a deliberate later start is not.
    return keyed
        ? workflowService
            .validateAndStartWorkflow(sessionId, workflowId, Map.of(), idempotencyKey.strip())
            .map(execution -> new WorkflowStartResult(execution.executionId(), false))
        : startMergingRepeats(sessionId, workflowId);
  }

  /** Starts without a client key; a repeat inside the window gets the first run, marked reused. */
  private Mono<WorkflowStartResult> startMergingRepeats(
      final String sessionId, final String workflowId) {
    // Only the call that really starts the run subscribes to the supplier; a merged repeat never
    // does.
    final AtomicBoolean started = new AtomicBoolean();
    return autoKey
        .once(
            sessionId + "\0" + workflowId,
            "auto",
            () ->
                Mono.defer(
                    () -> {
                      started.set(true);
                      return workflowService
                          .validateAndStartWorkflow(sessionId, workflowId)
                          .map(WorkflowExecution::executionId);
                    }))
        .map(executionId -> new WorkflowStartResult(executionId, !started.get()));
  }

  /**
   * Get the current progress snapshot of a workflow execution owned by the session.
   *
   * @param sessionId the session that owns the execution
   * @param executionId the execution identifier
   * @return Mono containing the current workflow progress; errors if the execution is unknown or
   *     owned by another session
   */
  public Mono<WorkflowProgress> getWorkflowStatus(
      final String sessionId, final String executionId) {
    return ExecutionOwnership.require(controlBus, sessionId, executionId);
  }

  /**
   * Get the execution history of a session.
   *
   * @param sessionId the session identifier
   * @return Mono containing the list of execution summaries; errors if the session is unknown
   */
  public Mono<List<WorkflowExecutionSummary>> getWorkflowHistory(final String sessionId) {
    return sessionService
        .getSessionConfig(sessionId)
        .flatMap(config -> controlBus.getHistory(sessionId))
        .switchIfEmpty(
            Mono.error(
                () ->
                    new IllegalArgumentException(
                        "Session not found: "
                            + sessionId
                            + ". Use list_sessions to see available sessions.")));
  }
}
