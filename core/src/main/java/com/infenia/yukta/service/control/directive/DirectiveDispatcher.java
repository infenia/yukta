// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.directive;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.security.AuditRecord;
import com.infenia.yukta.security.AuditSink;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.archive.ExecutionArchive;
import com.infenia.yukta.service.control.CommandSequencer;
import com.infenia.yukta.service.control.ControlAudit;
import com.infenia.yukta.service.control.ControlBusService;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.input.InputRequestStore;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.supervisor.ControlReviewer;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.PostConstruct;
import java.time.Instant;
import lombok.extern.slf4j.Slf4j;
import org.jspecify.annotations.Nullable;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Bridges the Control Bus to the workflow execution layer.
 *
 * <p>On startup, registers itself with {@link ControlBusService} as the executor of execution
 * control commands. Each command for a live (or restartable archived) execution is run by the
 * {@link CommandHandler}. Commands for one execution are applied in the order they were sent, and
 * the caller is told once a command has been applied or why it was rejected.
 */
@Slf4j
@Component
@SuppressWarnings("PMD.ConfusingTernary")
public class DirectiveDispatcher {

  /** Runs a command. */
  private final CommandHandler handler;

  /** The execution control registry for managing active executions. */
  private final ExecutionControlStore registry;

  /** The control bus service for subscribing to control signals. */
  private final ControlBusService controlBusService;

  /** Finished executions that can still be restarted. */
  private final ExecutionArchive archive;

  /** Reason recorded for a command whose caller went away before it finished. */
  private static final String CANCELLED_REASON = "cancelled";

  /** Records applied and rejected commands. */
  private final ExecutionLogStore executionLog;

  /** Receives every audit record besides the log, if a module provides one. */
  @Nullable private AuditSink auditSink;

  /** Lets supervisors refuse a command before it runs. */
  private final ControlReviewer reviewer;

  /** Receives the outcome of every command. */
  private final ExecutionEventFeed eventFeed;

  /** Keeps commands of one execution in the order they were sent. */
  private final CommandSequencer sequencer = new CommandSequencer();

  /** Finds the input request an answer or escalation names, for its node; none without it. */
  @Nullable private ObjectProvider<InputRequestStore> inputRequests;

  /**
   * Constructs a DirectiveDispatcher.
   *
   * @param handler runs a command
   * @param registry the live execution registry
   * @param controlBusService the control bus to subscribe to
   * @param archive finished executions that can still be restarted
   * @param executionLog records applied and rejected commands
   * @param reviewer lets supervisors refuse a command before it runs
   * @param eventFeed receives the outcome of every command
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public DirectiveDispatcher(
      final CommandHandler handler,
      final ExecutionControlStore registry,
      final ControlBusService controlBusService,
      final ExecutionArchive archive,
      final ExecutionLogStore executionLog,
      final ControlReviewer reviewer,
      final ExecutionEventFeed eventFeed) {
    this.handler = handler;
    this.registry = registry;
    this.controlBusService = controlBusService;
    this.archive = archive;
    this.executionLog = executionLog;
    this.reviewer = reviewer;
    this.eventFeed = eventFeed;
  }

  /**
   * Passes every audit record on to a sink, besides the execution log.
   *
   * @param sink the sink, if the application has one
   */
  @Autowired(required = false)
  public void setAuditSink(final AuditSink sink) {
    this.auditSink = sink;
  }

  /**
   * Lets the node of an input answer or escalation be found from its request, so a section
   * supervisor reviews it and may send it.
   *
   * @param store the input request store lookup
   */
  @Autowired
  public void setInputRequests(final ObjectProvider<InputRequestStore> store) {
    this.inputRequests = store;
  }

  /** Registers this dispatcher as the executor of execution control commands. */
  @PostConstruct
  public void init() {
    controlBusService.setCommandExecutor(this::dispatchOrdered);
  }

  /**
   * Applies a command after every earlier command for the same execution, and completes once it has
   * been applied.
   *
   * @param command the command
   * @return completes when applied; errors when the execution is unknown or the processor rejects
   *     the command
   */
  public Mono<Void> dispatchOrdered(final ExecutionControlCommand command) {
    return Mono.deferContextual(
        context -> {
          final String actor = context.getOrDefault(ControlActor.CONTEXT_KEY, null);
          return sequencer
              .sequence(
                  command.executionId(),
                  Mono.defer(
                      () ->
                          !isAddressable(command)
                              ? Mono.error(
                                  new NotFoundException(
                                      "Execution not found: " + command.executionId()))
                              : audited(command, actor)))
              .doOnCancel(
                  () -> {
                    if (isAddressable(command)) {
                      cancelled(command, actor);
                    }
                  });
        });
  }

  /**
   * Asks the supervisors, applies the command and records the outcome, so every command of a known
   * run is traceable.
   */
  private Mono<Void> audited(final ExecutionControlCommand command, final String actor) {
    final String name = command.getClass().getSimpleName();
    return target(command)
        .flatMap(
            run ->
                reviewer
                    .review(
                        new ControlRequest(
                            command, actor, run.sessionId(), run.workflowId(), run.nodeId()))
                    .then(Mono.defer(() -> dispatch(command)))
                    .doOnSuccess(_ -> outcome(command, run, name, actor, null))
                    .doOnError(error -> outcome(command, run, name, actor, error.getMessage())))
        .doOnSuccess(
            _ ->
                ControlAudit.record(
                    executionLog,
                    auditSink,
                    new AuditRecord(true, command.executionId(), name, actor, null, Instant.now())))
        .doOnError(
            error ->
                ControlAudit.record(
                    executionLog,
                    auditSink,
                    new AuditRecord(
                        false,
                        command.executionId(),
                        name,
                        actor,
                        error.getMessage(),
                        Instant.now())));
  }

  /** A command whose caller went away before it finished is neither applied nor lost. */
  private void cancelled(final ExecutionControlCommand command, final String actor) {
    final String name = command.getClass().getSimpleName();
    outcome(command, owner(command, command.nodeId()), name, actor, CANCELLED_REASON);
    ControlAudit.record(
        executionLog,
        auditSink,
        new AuditRecord(
            false, command.executionId(), name, actor, CANCELLED_REASON, Instant.now()));
  }

  /**
   * What a command acts on: the session and workflow of its run (live, or archived for a restart)
   * and its node. An input answer or escalation acts on the node of its request. Supervisors are
   * matched against it, both to review a command and to check that one they send is in scope.
   *
   * @param command the command
   * @return the target; its parts are {@code null} when the run or node is not known
   */
  public Mono<CommandTarget> target(final ExecutionControlCommand command) {
    return requestNode(command)
        .map(node -> owner(command, node))
        .switchIfEmpty(Mono.fromSupplier(() -> owner(command, command.nodeId())));
  }

  /** The node of the request an input command names, if the request is of the same run. */
  private Mono<String> requestNode(final ExecutionControlCommand command) {
    final String requestId =
        switch (command) {
          case ExecutionControlCommand.AnswerInputCommand answer -> answer.requestId();
          case ExecutionControlCommand.EscalateInputCommand escalate -> escalate.requestId();
          default -> null;
        };
    final InputRequestStore store = inputRequests == null ? null : inputRequests.getIfAvailable();
    if (requestId == null || store == null) {
      return Mono.empty();
    }
    return store
        .findById(requestId)
        .filter(request -> command.executionId().equals(request.executionId()))
        .map(InputRequest::nodeId);
  }

  /** The session and workflow of the run a command addresses; nulls when the run is not known. */
  private CommandTarget owner(final ExecutionControlCommand command, @Nullable final String node) {
    return registry
        .findByExecutionId(command.executionId())
        .map(control -> new CommandTarget(control.sessionId(), control.workflowId(), node))
        .or(
            () ->
                archive
                    .find(command.executionId())
                    .map(run -> new CommandTarget(run.sessionId(), run.workflowId(), node)))
        .orElseGet(() -> new CommandTarget(null, null, node));
  }

  private void outcome(
      final ExecutionControlCommand command,
      final CommandTarget run,
      final String name,
      final String actor,
      final String reason) {
    eventFeed.publish(
        new ExecutionEvent.ControlOutcome(
            command.executionId(),
            run.sessionId(),
            run.workflowId(),
            name,
            actor,
            reason == null,
            reason,
            Instant.now(),
            run.nodeId()));
  }

  /**
   * Dispatches a single {@link ExecutionControlCommand} to the {@link CommandHandler}.
   *
   * @param command the command to dispatch
   * @return a Mono that completes when the command has been applied
   */
  public Mono<Void> dispatch(final ExecutionControlCommand command) {
    if (registry.findByExecutionId(command.executionId()).isPresent()
        || isRestartOfArchived(command)) {
      return handler.handle(command);
    }
    log.atWarn().addKeyValue("executionId", command.executionId()).log("No active execution found");
    return Mono.error(new NotFoundException("Execution not found: " + command.executionId()));
  }

  private boolean isAddressable(final ExecutionControlCommand command) {
    return registry.findByExecutionId(command.executionId()).isPresent()
        || isRestartOfArchived(command);
  }

  private boolean isRestartOfArchived(final ExecutionControlCommand command) {
    return (command instanceof ExecutionControlCommand.RestartCommand
            || command instanceof ExecutionControlCommand.RestartFromNodeCommand)
        && archive.find(command.executionId()).isPresent();
  }
}
