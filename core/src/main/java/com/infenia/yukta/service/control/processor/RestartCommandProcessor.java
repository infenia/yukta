// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.RestartCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import java.util.UUID;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Processor for restart commands.
 *
 * <p>Stops the current execution and restarts the entire workflow from the beginning with the
 * original payload. Emits an observability event for the new execution.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RestartCommandProcessor implements ControlSignalProcessor {

  /** The execution control registry for accessing execution control state. */
  private final ExecutionControlRegistry registry;

  /** The workflow orchestrator for restarting workflows. */
  private final WorkflowOrchestrator orchestrator;

  /** The task tracker service for tracking task execution. */
  private final DefaultTaskTrackerService taskTracker;

  @Override
  public boolean canProcess(final ExecutionControlCommand command) {
    return command instanceof RestartCommand;
  }

  @Override
  public Mono<WorkflowDirective> process(final ExecutionControlCommand command) {
    final RestartCommand restart = (RestartCommand) command;

    return Mono.fromSupplier(
            () ->
                registry
                    .findByExecutionId(restart.executionId())
                    .orElseThrow(
                        () ->
                            new IllegalArgumentException(
                                "Execution not found: " + restart.executionId())))
        .flatMap(
            control -> {
              registry.unregister(control.executionId());
              control.safeStopSink().emitEmpty(Sinks.EmitFailureHandler.FAIL_FAST);

              final String newExecutionId = UUID.randomUUID().toString();
              return orchestrator
                  .execute(
                      control.sessionId(),
                      control.workflowId(),
                      newExecutionId,
                      control.prepared(),
                      control.payload())
                  .doOnSuccess(
                      ignored -> {
                        taskTracker.emitWorkflowStatusEvent(newExecutionId, "RUNNING");
                        log.atInfo()
                            .addKeyValue("oldExecutionId", restart.executionId())
                            .addKeyValue("newExecutionId", newExecutionId)
                            .addKeyValue("workflowId", control.workflowId())
                            .addKeyValue("status", "RUNNING")
                            .log("Restarted execution");
                      })
                  .then(Mono.<WorkflowDirective>empty());
            })
        .onErrorResume(
            e -> {
              log.atError()
                  .addKeyValue("executionId", restart.executionId())
                  .setCause(e)
                  .log("Restart failed");
              return Mono.empty();
            });
  }

  @Override
  public int getPriority() {
    return 20;
  }
}
