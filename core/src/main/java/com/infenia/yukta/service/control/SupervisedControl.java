// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.service.control.directive.DirectiveDispatcher;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.orchestrator.ExecutionResumer;
import java.util.List;
import java.util.Map;
import lombok.RequiredArgsConstructor;
import org.jspecify.annotations.Nullable;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.util.context.Context;

/**
 * The one entry that applies control commands and starts for people and engines. REST, MCP, CLI and
 * UI calls come in as {@code OPERATOR}, a supervisor's directives as {@code ENGINE}, and the
 * server's own housekeeping as {@code SYSTEM}. It writes the channel into the Reactor context (the
 * audit and the command outcome read it) and hands the command to the gateway, which reviews,
 * applies and audits it. The actor and its kind are written earlier, by the authentication filter
 * or the supervisor host.
 *
 * <p>An ArchUnit rule keeps every other class from calling the gateway's command methods or the
 * dispatcher, so a new path cannot skip this class.
 */
@Component
@RequiredArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.UseObjectForClearerAPI"})
public class SupervisedControl {

  /** Applies the commands and records them. */
  private final ControlBusGateway gateway;

  /** Queues and applies an engine's commands. */
  private final DirectiveDispatcher dispatcher;

  /** Admission: draining. */
  private final ExecutionScheduler scheduler;

  /** Continues a run that a server restart aborted. */
  private final ExecutionResumer resumer;

  // --- OPERATOR: people and clients through REST, MCP, CLI and the UI ---

  /**
   * Start a run.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @param payload the start payload
   * @param idempotencyKey makes a repeat return the first run, or {@code null}
   * @return the new execution id
   */
  public Mono<String> startWorkflow(
      final String sessionId,
      final String workflowId,
      final Map<String, Object> payload,
      @Nullable final String idempotencyKey) {
    return operator(gateway.startWorkflow(sessionId, workflowId, payload, idempotencyKey));
  }

  /**
   * Pause a run.
   *
   * @param executionId the run
   * @return completes when applied
   */
  public Mono<Void> pauseWorkflow(final String executionId) {
    return operator(gateway.pauseWorkflow(executionId));
  }

  /**
   * Resume a run.
   *
   * @param executionId the run
   * @return completes when applied
   */
  public Mono<Void> resumeWorkflow(final String executionId) {
    return operator(gateway.resumeWorkflow(executionId));
  }

  /**
   * Pause a node.
   *
   * @param executionId the run
   * @param nodeId the node
   * @return completes when applied
   */
  public Mono<Void> pauseNode(final String executionId, final String nodeId) {
    return operator(gateway.pauseNode(executionId, nodeId));
  }

  /**
   * Resume a node.
   *
   * @param executionId the run
   * @param nodeId the node
   * @return completes when applied
   */
  public Mono<Void> resumeNode(final String executionId, final String nodeId) {
    return operator(gateway.resumeNode(executionId, nodeId));
  }

  /**
   * Stop a node.
   *
   * @param executionId the run
   * @param nodeId the node
   * @param immediate stop at once instead of draining
   * @param reason why
   * @return completes when applied
   */
  public Mono<Void> stopNode(
      final String executionId, final String nodeId, final boolean immediate, final String reason) {
    return operator(gateway.stopNode(executionId, nodeId, immediate, reason));
  }

  /**
   * Skip or unskip a node.
   *
   * @param executionId the run
   * @param nodeId the node
   * @param skip true to skip
   * @return completes when applied
   */
  public Mono<Void> skipNode(final String executionId, final String nodeId, final boolean skip) {
    return operator(gateway.skipNode(executionId, nodeId, skip));
  }

  /**
   * Set a breakpoint.
   *
   * @param executionId the run
   * @param nodeId the node
   * @param field the field to compare, or {@code null}
   * @param equalsValue the value that stops the node
   * @return completes when applied
   */
  public Mono<Void> addBreakpoint(
      final String executionId, final String nodeId, final String field, final String equalsValue) {
    return operator(gateway.addBreakpoint(executionId, nodeId, field, equalsValue));
  }

  /**
   * Clear a breakpoint.
   *
   * @param executionId the run
   * @param nodeId the node
   * @return completes when applied
   */
  public Mono<Void> clearBreakpoint(final String executionId, final String nodeId) {
    return operator(gateway.clearBreakpoint(executionId, nodeId));
  }

  /**
   * Turn step mode on for a node.
   *
   * @param executionId the run
   * @param nodeId the node
   * @return completes when applied
   */
  public Mono<Void> enableStepMode(final String executionId, final String nodeId) {
    return operator(gateway.enableStepMode(executionId, nodeId));
  }

  /**
   * Turn step mode off for a node.
   *
   * @param executionId the run
   * @param nodeId the node
   * @return completes when applied
   */
  public Mono<Void> disableStepMode(final String executionId, final String nodeId) {
    return operator(gateway.disableStepMode(executionId, nodeId));
  }

  /**
   * Let a node in step mode take one step.
   *
   * @param executionId the run
   * @param nodeId the node
   * @return completes when applied
   */
  public Mono<Void> stepNode(final String executionId, final String nodeId) {
    return operator(gateway.stepNode(executionId, nodeId));
  }

  /**
   * Retry a node.
   *
   * @param executionId the run
   * @param nodeId the node
   * @return the id of the new run
   */
  public Mono<String> retryNode(final String executionId, final String nodeId) {
    return operator(gateway.retryNode(executionId, nodeId));
  }

  /**
   * Stop every live run of a workflow.
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @param reason why
   * @param mode how
   * @return the ids of the stopped runs
   */
  public Mono<List<String>> stopWorkflow(
      final String sessionId, final String workflowId, final String reason, final StopMode mode) {
    return operator(gateway.stopWorkflow(sessionId, workflowId, reason, mode));
  }

  /**
   * Stop one run.
   *
   * @param executionId the run
   * @param reason why
   * @param mode how
   * @return the id of the stopped run
   */
  public Mono<String> stopExecution(
      final String executionId, final String reason, final StopMode mode) {
    return operator(gateway.stopExecution(executionId, reason, mode));
  }

  /**
   * Restart a run from the beginning.
   *
   * @param executionId the run
   * @return the id of the new run
   */
  public Mono<String> restartWorkflow(final String executionId) {
    return operator(gateway.restartWorkflow(executionId));
  }

  /**
   * Restart a run from a node.
   *
   * @param executionId the run
   * @param fromNodeId the node to restart from
   * @return the id of the new run
   */
  public Mono<String> restartFromNode(final String executionId, final String fromNodeId) {
    return operator(gateway.restartFromNode(executionId, fromNodeId));
  }

  /**
   * Change the scheduler limits.
   *
   * @param maxConcurrent the most runs at once, 0 for no limit
   * @param maxPerWorkflow the most runs of one workflow, 0 for no limit
   * @return completes when applied
   */
  public Mono<Void> changeLimits(final int maxConcurrent, final int maxPerWorkflow) {
    return operator(gateway.changeLimits(maxConcurrent, maxPerWorkflow));
  }

  /**
   * Move a waiting start.
   *
   * @param executionId the waiting run
   * @param priority the new priority
   * @return completes when applied
   */
  public Mono<Void> changePriority(final String executionId, final int priority) {
    return operator(gateway.changePriority(executionId, priority));
  }

  /**
   * Answer an input request.
   *
   * @param executionId the run
   * @param requestId the request
   * @param expectedVersion the version the answer is for, or {@code null}
   * @param decision the decision
   * @param text free text, or {@code null}
   * @param data structured data, or {@code null}
   * @param rationale why, or {@code null}
   * @param confidence how sure, or {@code null}
   * @return completes when applied
   */
  public Mono<Void> answerInput(
      final String executionId,
      final String requestId,
      @Nullable final Long expectedVersion,
      final String decision,
      @Nullable final String text,
      @Nullable final Map<String, Object> data,
      @Nullable final String rationale,
      @Nullable final Double confidence) {
    return operator(
        gateway.answerInput(
            executionId, requestId, expectedVersion, decision, text, data, rationale, confidence));
  }

  /**
   * Pass an input request up.
   *
   * @param executionId the run
   * @param requestId the request
   * @param reason why
   * @param advice advice for the next tier, or {@code null}
   * @return completes when applied
   */
  public Mono<Void> escalateInput(
      final String executionId,
      final String requestId,
      final String reason,
      @Nullable final String advice) {
    return operator(gateway.escalateInput(executionId, requestId, reason, advice));
  }

  /**
   * Record an administrative change that is not a run command, such as deleting a session.
   *
   * @param command the action
   * @param target what it acted on
   * @param applied whether it happened
   * @param reason why not, or {@code null}
   * @return completes when recorded
   */
  public Mono<Void> recordAdminAction(
      final String command,
      final String target,
      final boolean applied,
      @Nullable final String reason) {
    return operator(gateway.recordAdminAction(command, target, applied, reason));
  }

  /**
   * Continue a run that a server restart aborted, as a new run. Not reviewed by supervisors yet (it
   * has no command type); it is audited.
   *
   * @param executionId the aborted run
   * @return the id of the new run
   */
  public Mono<String> recover(final String executionId) {
    return operator(
        gateway.reviewedAdmin(
            new ExecutionControlCommand.RecoverCommand(executionId),
            "Recover",
            () -> Mono.defer(() -> resumer.resume(executionId))));
  }

  /**
   * Refuse new starts, or admit them again. Reviewed as a server-level command and audited.
   *
   * @param draining true to drain
   * @return completes when applied
   */
  public Mono<Void> drain(final boolean draining) {
    return operator(
        gateway.reviewedAdmin(
            new ExecutionControlCommand.DrainCommand(draining),
            draining ? "Drain" : "Undrain",
            () -> Mono.<Void>fromRunnable(() -> scheduler.setDraining(draining))));
  }

  // --- ENGINE: directives from a supervisor's engine ---

  /**
   * Apply a supervisor's command after every earlier command for the same run. The supervisor host
   * has already checked scope, allowed commands and rate; review, audit and the outcome follow.
   *
   * @param command the command
   * @return completes when applied
   */
  public Mono<Void> engine(final ExecutionControlCommand command) {
    return dispatcher
        .dispatchOrdered(command)
        .contextWrite(Context.of(ControlActor.CHANNEL_KEY, ControlActor.Channel.ENGINE.name()));
  }

  // --- SYSTEM: the server's own housekeeping ---

  /**
   * Stop every live run of a workflow because the server removes it (a session delete).
   *
   * @param sessionId the session
   * @param workflowId the workflow
   * @param reason why
   * @param mode how
   * @return the ids of the stopped runs
   */
  public Mono<List<String>> stopWorkflowAsSystem(
      final String sessionId, final String workflowId, final String reason, final StopMode mode) {
    return gateway
        .stopWorkflow(sessionId, workflowId, reason, mode)
        .contextWrite(Context.of(ControlActor.CHANNEL_KEY, ControlActor.Channel.SYSTEM.name()));
  }

  private static <T> Mono<T> operator(final Mono<T> command) {
    return command.contextWrite(
        Context.of(ControlActor.CHANNEL_KEY, ControlActor.Channel.OPERATOR.name()));
  }
}
