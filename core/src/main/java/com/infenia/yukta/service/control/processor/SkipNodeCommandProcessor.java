// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SkipNodeCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.tracker.DefaultTaskTrackerService;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Processor for skip node commands.
 *
 * <p>Marks a node as skipped (passes through without processing) or unskipped and emits an
 * observability event.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkipNodeCommandProcessor implements ControlSignalProcessor {

  /** The execution control registry for accessing execution control state. */
  private final ExecutionControlRegistry registry;

  /** The task tracker service for tracking task execution. */
  private final DefaultTaskTrackerService taskTracker;

  @Override
  public boolean canProcess(final ExecutionControlCommand command) {
    return command instanceof SkipNodeCommand;
  }

  @Override
  public Mono<WorkflowDirective> process(final ExecutionControlCommand command) {
    final SkipNodeCommand skip = (SkipNodeCommand) command;
    return Mono.fromRunnable(
        () -> {
          final ExecutionControl control =
              registry
                  .findByExecutionId(skip.executionId())
                  .orElseThrow(
                      () ->
                          new IllegalArgumentException(
                              "Execution not found: " + skip.executionId()));

          final AtomicBoolean flag = control.nodeSkipFlags().get(skip.nodeId());
          if (flag == null) {
            throw new IllegalArgumentException("Node not found or not skippable: " + skip.nodeId());
          }

          flag.set(skip.skip());

          taskTracker.emitWorkflowStatusEvent(
              skip.executionId(), skip.skip() ? "NODE_SKIPPED" : "NODE_UNSKIPPED");

          log.atDebug()
              .addKeyValue("executionId", skip.executionId())
              .addKeyValue("nodeId", skip.nodeId())
              .addKeyValue("skip", skip.skip())
              .addKeyValue("status", skip.skip() ? "NODE_SKIPPED" : "NODE_UNSKIPPED")
              .log("Node skip flag set");
        });
  }

  @Override
  public int getPriority() {
    return 10;
  }
}
