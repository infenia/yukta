// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SkipNodeCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Processor for skip node commands.
 *
 * <p>Flips the node's skip flag. A processor node checks the flag for every element: while it is
 * set, elements pass through without calling the plugin (an element the plugin already has finishes
 * normally). Only processor nodes can be skipped; any other node is rejected with a clear reason,
 * as skipping a trigger or a terminal would change what the workflow means.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkipNodeCommandProcessor implements ControlSignalProcessor {

  /** The execution control registry, to find the run and its node flags. */
  private final ExecutionControlRegistry registry;

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
                      () -> new NotFoundException("Execution not found: " + skip.executionId()));
          final AtomicBoolean flag = control.nodeSkipFlags().get(skip.nodeId());
          if (flag == null) {
            throw new NotFoundException("Node not found: " + skip.nodeId());
          }
          if (!(control.prepared().pluginCache().get(skip.nodeId()) instanceof ProcessorPlugin)) {
            throw new IllegalArgumentException(
                "Only processor nodes can be skipped (node "
                    + skip.nodeId()
                    + " of execution "
                    + skip.executionId()
                    + ")");
          }
          flag.set(skip.skip());
          log.atDebug()
              .addKeyValue("executionId", skip.executionId())
              .addKeyValue("nodeId", skip.nodeId())
              .addKeyValue("skip", skip.skip())
              .log("Set node skip flag");
        });
  }

  @Override
  public int getPriority() {
    return 10;
  }
}
