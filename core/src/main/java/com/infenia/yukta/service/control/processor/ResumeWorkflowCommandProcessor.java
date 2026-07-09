// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.ResumeWorkflowCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Processor for resume workflow commands.
 *
 * <p>Removes backpressure from all nodes in an execution via the global pause valve and emits an
 * observability event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeWorkflowCommandProcessor implements ControlSignalProcessor {

  /** The execution control registry for accessing execution control state. */
  private final ExecutionControlRegistry registry;

  /** The task tracker service for tracking task execution. */
  private final DefaultTaskTrackerService taskTracker;

  @Override
  public boolean canProcess(final ExecutionControlCommand command) {
    return command instanceof ResumeWorkflowCommand;
  }

  @Override
  public Mono<WorkflowDirective> process(final ExecutionControlCommand command) {
    final ResumeWorkflowCommand resume = (ResumeWorkflowCommand) command;
    return Mono.fromRunnable(
        () -> {
          final ExecutionControl control =
              registry
                  .findByExecutionId(resume.executionId())
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              "Execution not found: " + resume.executionId()));

          if (control.globalPauseValve() == null) {
            throw new IllegalStateException("Global pause valve not initialized");
          }

          control.globalPauseValve().resume();

          taskTracker.emitWorkflowStatusEvent(resume.executionId(), "RUNNING");

          log.atDebug()
              .addKeyValue("executionId", resume.executionId())
              .addKeyValue("status", "RUNNING")
              .log("Resumed workflow");
        });
  }

  @Override
  public int getPriority() {
    return 10;
  }
}
