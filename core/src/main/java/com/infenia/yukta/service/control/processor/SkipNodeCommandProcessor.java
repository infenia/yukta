// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SkipNodeCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Processor for skip node commands.
 *
 * <p>A node's stream is assembled once when the execution starts, so a skip toggled while the
 * execution runs cannot change it. The command is rejected explicitly instead of reporting a skip
 * that never happens.
 */
@Component
@NoArgsConstructor
public class SkipNodeCommandProcessor implements ControlSignalProcessor {

  @Override
  public boolean canProcess(final ExecutionControlCommand command) {
    return command instanceof SkipNodeCommand;
  }

  @Override
  public Mono<WorkflowDirective> process(final ExecutionControlCommand command) {
    final SkipNodeCommand skip = (SkipNodeCommand) command;
    return Mono.error(
        new IllegalArgumentException(
            "Skipping a node of a running execution is not supported (node "
                + skip.nodeId()
                + " of execution "
                + skip.executionId()
                + ")"));
  }

  @Override
  public int getPriority() {
    return 10;
  }
}
