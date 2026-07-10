// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.directive;

import com.infenia.yukta.model.control.ControlCommand;
import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.service.control.ControlBusService;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Bridges the Control Bus to the workflow execution layer.
 *
 * <p>On startup, subscribes to the raw control stream from {@link ControlBusService}. For every
 * message whose payload is a {@link ControlCommand}, finds the first matching {@link
 * ControlSignalProcessor} (by priority), obtains a {@link WorkflowDirective}, and applies it to the
 * active execution found in {@link ExecutionControlRegistry}.
 */
@Slf4j
@Component
public class DirectiveDispatcher {

  /** Emit failure handler for reactive operations. */
  private static final Sinks.EmitFailureHandler FAIL_FAST = Sinks.EmitFailureHandler.FAIL_FAST;

  /** The list of control signal processors ordered by priority. */
  private final List<ControlSignalProcessor> processors;

  /** The execution control registry for managing active executions. */
  private final ExecutionControlRegistry registry;

  /** The control bus service for subscribing to control signals. */
  private final ControlBusService controlBusService;

  /**
   * Constructs a DirectiveDispatcher.
   *
   * @param processors the registered signal processors, ordered by priority
   * @param registry the live execution registry
   * @param controlBusService the control bus to subscribe to
   */
  public DirectiveDispatcher(
      final List<ControlSignalProcessor> processors,
      final ExecutionControlRegistry registry,
      final ControlBusService controlBusService) {
    this.processors = List.copyOf(processors);
    this.registry = registry;
    this.controlBusService = controlBusService;
  }

  /**
   * Subscribes to the control stream and routes ExecutionControlCommand messages through the
   * pipeline.
   */
  @PostConstruct
  @SuppressWarnings("PMD.LawOfDemeter")
  public void init() {
    controlBusService
        .getControlStream()
        .filter(msg -> msg.getPayload() instanceof ExecutionControlCommand)
        .flatMap(
            msg -> {
              final ExecutionControlCommand command = (ExecutionControlCommand) msg.getPayload();
              return dispatch(command)
                  .onErrorResume(
                      e -> {
                        log.atError().setCause(e).log("Error dispatching command");
                        return Mono.empty();
                      });
            })
        .subscribe();
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
          control.safeStopSink().emitEmpty(FAIL_FAST);
          log.atInfo().log(
              "Stopped execution {} for workflow {}: {}",
              control.executionId(),
              control.workflowId(),
              directive.reason());
        });
  }
}
