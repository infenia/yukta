// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.SetBreakpointCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import java.util.Map;
import java.util.function.Predicate;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Processor for set-breakpoint commands.
 *
 * <p>Turns the declarative condition of the command into a test on the element ({@code payload},
 * {@code payload.some.path} or {@code metadata.key}, compared as text) and sets it on the node's
 * pause valve; a command without a field clears it.
 */
@Slf4j
@Component
@SuppressWarnings("PMD.OnlyOneReturn")
@RequiredArgsConstructor
public class SetBreakpointCommandProcessor implements ControlSignalProcessor {

  /** The execution control registry, to find the run and its valves. */
  private final ExecutionControlRegistry registry;

  @Override
  public boolean canProcess(final ExecutionControlCommand command) {
    return command instanceof SetBreakpointCommand;
  }

  @Override
  public Mono<WorkflowDirective> process(final ExecutionControlCommand command) {
    final SetBreakpointCommand breakpoint = (SetBreakpointCommand) command;
    return Mono.fromRunnable(
        () -> {
          final ExecutionControl control =
              registry
                  .findByExecutionId(breakpoint.executionId())
                  .orElseThrow(
                      () ->
                          new NotFoundException(
                              "Execution not found: " + breakpoint.executionId()));
          final ReactiveControlValve valve = control.nodePauseValves().get(breakpoint.nodeId());
          if (valve == null) {
            throw new NotFoundException("Node not found or not pausable: " + breakpoint.nodeId());
          }
          valve.setBreakpoint(
              breakpoint.field() == null
                  ? null
                  : matcher(breakpoint.field(), breakpoint.equalsValue()));
          log.atDebug()
              .addKeyValue("executionId", breakpoint.executionId())
              .addKeyValue("nodeId", breakpoint.nodeId())
              .addKeyValue("field", breakpoint.field())
              .log("Set node breakpoint");
        });
  }

  /** Builds the element test: the value at {@code field}, as text, equals {@code expected}. */
  /* default */ static Predicate<Message<?>> matcher(final String field, final String expected) {
    return message -> expected.equals(text(valueAt(message, field)));
  }

  private static Object valueAt(final Message<?> message, final String field) {
    if (field.startsWith("metadata.")) {
      return message.metadata().get(field.substring("metadata.".length()));
    }
    Object value = message.payload();
    if (!"payload".equals(field)) {
      for (final String key : field.substring("payload.".length()).split("\\.")) {
        if (!(value instanceof Map<?, ?> map)) {
          return null;
        }
        value = map.get(key);
      }
    }
    return value;
  }

  private static String text(final Object value) {
    return value == null ? null : String.valueOf(value);
  }

  @Override
  public int getPriority() {
    return 10;
  }
}
