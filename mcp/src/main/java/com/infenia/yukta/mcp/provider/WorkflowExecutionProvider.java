// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import com.infenia.yukta.mcp.dto.WorkflowStartResult;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.security.ApiKeys;
import com.infenia.yukta.security.CallerIdentity;
import com.infenia.yukta.service.control.StartOptions;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.control.gateway.WorkflowService;
import com.infenia.yukta.service.session.SessionService;
import com.infenia.yukta.service.workflow.ConfigRedactor;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.ContextView;

/**
 * Handles workflow execution operations including definition retrieval, start operations, status
 * snapshots, and execution history.
 */
@Slf4j
@Component
public class WorkflowExecutionProvider {

  /** Service for executing workflows and managing their lifecycle. */
  private final WorkflowService workflowService;

  /** The longest a status call waits for a change, so a request does not hang. */
  public static final int MAX_WAIT_SECONDS = 30;

  /** Service for managing session state and configuration. */
  private final SessionService sessionService;

  /** Gateway for workflow control and observability operations. */
  private final ControlBusGateway controlBus;

  /** Hides literal secrets in the definitions handed to an agent. */
  private final ConfigRedactor configRedactor;

  /** Merges keyless starts of one session and workflow made within a short window. */
  private final AutoKeyWindow autoKey;

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
    this.autoKey = new AutoKeyWindow(autoKeyWindow);
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
            Mono.error(() ->
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
    return Mono.deferContextual(context -> {
      final Map<String, Object> payload = StartOptions.withInitiator(Map.of(), initiator(context));
      return keyed
          ? workflowService
              .start(sessionId, workflowId, payload, idempotencyKey.strip())
              .map(executionId -> new WorkflowStartResult(executionId, false))
          : startMergingRepeats(sessionId, workflowId, payload);
    });
  }

  /** Who started the run: an agent over MCP, with the name of its key when it has one. */
  private static String initiator(final ContextView context) {
    return context
        .<ApiKeys.Match>getOrEmpty(CallerIdentity.CONTEXT_KEY)
        .map(key -> "mcp · " + key.name())
        .orElse("mcp");
  }

  /** Starts without a client key; a repeat inside the window gets the first run, marked reused. */
  private Mono<WorkflowStartResult> startMergingRepeats(
      final String sessionId, final String workflowId, final Map<String, Object> payload) {
    // Only the call that really starts the run subscribes to the supplier; a merged repeat never
    // does.
    final AtomicBoolean started = new AtomicBoolean();
    return autoKey
        .once(
            sessionId + "\0" + workflowId,
            () ->
                Mono.defer(() -> {
                  started.set(true);
                  return workflowService.start(sessionId, workflowId, payload, null);
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
   * The progress of an execution, after waiting up to {@code waitSeconds} for it to change: its
   * status, its control state or a node's status. Returns at once when the run has finished.
   *
   * @param sessionId the session that owns the execution
   * @param executionId the execution identifier
   * @param waitSeconds how long to wait for a change, capped at {@link #MAX_WAIT_SECONDS}; null or
   *     0 returns the current snapshot
   * @return Mono containing the changed progress, or the current one when nothing changed in time
   */
  public Mono<WorkflowProgress> getWorkflowStatus(
      final String sessionId, final String executionId, final Integer waitSeconds) {
    final Mono<WorkflowProgress> now = getWorkflowStatus(sessionId, executionId);
    if (waitSeconds == null || waitSeconds <= 0) {
      return now;
    }
    final Duration wait = Duration.ofSeconds(Math.min(waitSeconds, MAX_WAIT_SECONDS));
    // ponytail: a change between the first read and the subscription shows at the next update or
    // when the wait ends; subscribe before reading if that delay matters
    return now.flatMap(before ->
        before.status() != null && before.status().isTerminal()
            ? Mono.just(before)
            : controlBus
                .watchExecution(executionId, false)
                .filter(after -> !shape(after).equals(shape(before)))
                .next()
                .timeout(wait, Mono.empty())
                .switchIfEmpty(Mono.defer(() -> getWorkflowStatus(sessionId, executionId))));
  }

  /** What counts as a change for a waiting caller: run status, control state, node statuses. */
  private static List<Object> shape(final WorkflowProgress progress) {
    return List.of(
        String.valueOf(progress.status()),
        String.valueOf(progress.controlState()),
        progress.tasks().stream().map(t -> t.nodeId() + "=" + t.status()).toList());
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
            Mono.error(() ->
                new IllegalArgumentException(
                    "Session not found: "
                        + sessionId
                        + ". Use list_sessions to see available sessions.")));
  }
}
