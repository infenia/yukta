// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.model.execution.ControlState;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.ResumeNodeCommand;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Processor for resume node commands.
 *
 * <p>Removes backpressure from a specific node via its pause valve and emits an observability
 * event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ResumeNodeCommandProcessor {

  /** The execution control registry for accessing execution control state. */
  private final ExecutionControlStore registry;

  /** The task tracker service for tracking task execution. */
  private final TaskTrackerService taskTracker;

  /** Applies the command. */
  public Mono<Void> process(final ResumeNodeCommand resume) {
    return Mono.fromRunnable(
        () -> {
          final ExecutionControl control =
              registry
                  .findByExecutionId(resume.executionId())
                  .orElseThrow(
                      () -> new NotFoundException("Execution not found: " + resume.executionId()));

          final ReactiveControlValve valve = control.nodePauseValves().get(resume.nodeId());
          if (valve == null) {
            throw new NotFoundException("Node not found or not pausable: " + resume.nodeId());
          }

          valve.resume();

          taskTracker.emitControlStateEvent(resume.executionId(), ControlState.NODE_RESUMED);

          log.atDebug()
              .addKeyValue("executionId", resume.executionId())
              .addKeyValue("nodeId", resume.nodeId())
              .addKeyValue("status", "NODE_RESUMED")
              .log("Resumed node");
        });
  }
}
