// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopNodeCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Processor for stop node commands.
 *
 * <p>Stops a specific node with immediate or safe semantics and emits an observability event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class StopNodeCommandProcessor implements ControlSignalProcessor {

  /** The execution control registry for accessing execution control state. */
  private final ExecutionControlRegistry registry;

  /** The task tracker service for tracking task execution. */
  private final DefaultTaskTrackerService taskTracker;

  @Override
  public boolean canProcess(final ExecutionControlCommand command) {
    return command instanceof StopNodeCommand;
  }

  @Override
  public Mono<WorkflowDirective> process(final ExecutionControlCommand command) {
    final StopNodeCommand stop = (StopNodeCommand) command;
    return Mono.fromRunnable(
        () -> {
          final ExecutionControl control =
              registry
                  .findByExecutionId(stop.executionId())
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              "Execution not found: " + stop.executionId()));

          final String nodeId = stop.nodeId();
          final Sinks.One<Void> sink =
              stop.immediate()
                  ? control.nodeImmediateStopSinks().get(nodeId)
                  : control.nodeSafeStopSinks().get(nodeId);

          if (sink == null) {
            throw new IllegalArgumentException("Node not found or not stoppable: " + nodeId);
          }

          sink.emitEmpty(Sinks.EmitFailureHandler.FAIL_FAST);

          taskTracker.emitWorkflowStatusEvent(stop.executionId(), "NODE_STOPPED");

          log.atDebug()
              .addKeyValue("executionId", stop.executionId())
              .addKeyValue("nodeId", nodeId)
              .addKeyValue("immediate", stop.immediate())
              .addKeyValue("reason", stop.reason())
              .addKeyValue("status", "NODE_STOPPED")
              .log("Stopped node");
        });
  }

  @Override
  public int getPriority() {
    return 15;
  }
}
