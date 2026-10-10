// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.directive;

import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.service.control.CommandSequencer;
import com.infenia.yukta.service.control.ControlBusService;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Bridges the Control Bus to the workflow execution layer.
 *
 * <p>On startup, registers itself with {@link ControlBusService} as the executor of execution
 * control commands. For each command it finds the matching {@link ControlSignalProcessor} (by
 * priority), obtains a {@link WorkflowDirective}, and applies it to the active execution found in
 * {@link ExecutionControlRegistry}. Commands for one execution are applied in the order they were
 * sent, and the caller is told once a command has been applied or why it was rejected.
 */
@Slf4j
@Component
public class DirectiveDispatcher {

  /** The list of control signal processors ordered by priority. */
  private final List<ControlSignalProcessor> processors;

  /** The execution control registry for managing active executions. */
  private final ExecutionControlRegistry registry;

  /** The control bus service for subscribing to control signals. */
  private final ControlBusService controlBusService;

  /** Keeps commands of one execution in the order they were sent. */
  private final CommandSequencer sequencer = new CommandSequencer();

  /**
   * Constructs a DirectiveDispatcher.
   *
   * @param processors the registered signal processors, ordered by priority
   * @param registry the live execution registry
   * @param controlBusService the control bus to subscribe to
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public DirectiveDispatcher(
      final List<ControlSignalProcessor> processors,
      final ExecutionControlRegistry registry,
      final ControlBusService controlBusService) {
    this.processors = List.copyOf(processors);
    this.registry = registry;
    this.controlBusService = controlBusService;
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
    return sequencer.sequence(
        command.executionId(),
        Mono.defer(
            () ->
                registry.findByExecutionId(command.executionId()).isEmpty()
                    ? Mono.error(
                        new IllegalArgumentException(
                            "Execution not found: " + command.executionId()))
                    : dispatch(command)));
  }

  /**
   * Dispatches a single {@link ExecutionControlCommand}: finds a processor, produces a directive,
   * applies it.
   *
   * @param command the command to dispatch
   * @return a Mono that completes when the directive has been applied
   */
  public Mono<Void> dispatch(final ExecutionControlCommand command) {
    final Optional<ExecutionControl> controlOpt = registry.findByExecutionId(command.executionId());

    if (controlOpt.isEmpty()) {
      log.atWarn()
          .addKeyValue("executionId", command.executionId())
          .log("No active execution found");
    }

    return controlOpt
        .map(
            control ->
                findProcessor(command)
                    .flatMap(processor -> processor.process(command))
                    .flatMap(directive -> applyDirective(control, directive)))
        .orElseGet(Mono::empty);
  }

  private Mono<ControlSignalProcessor> findProcessor(final ExecutionControlCommand command) {
    return processors.stream()
        .filter(p -> p.canProcess(command))
        .max(Comparator.comparingInt(ControlSignalProcessor::getPriority))
        .map(Mono::just)
        .orElseGet(
            () -> Mono.error(new IllegalArgumentException("No processor registered for command")));
  }

  private Mono<Void> applyDirective(
      final ExecutionControl control, final WorkflowDirective directive) {
    return switch (directive) {
      case WorkflowDirective.Stop stop -> applyStop(control, stop);
    };
  }

  private Mono<Void> applyStop(
      final ExecutionControl control, final WorkflowDirective.Stop directive) {
    return Mono.fromRunnable(
        () -> {
          registry.unregister(control.executionId());
          control.safeStopSink().tryEmitEmpty();
          log.atInfo().log(
              "Stopped execution {} for workflow {}: {}",
              control.executionId(),
              control.workflowId(),
              directive.reason());
        });
  }
}
