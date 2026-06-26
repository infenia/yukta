/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.service.control.directive;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.control.ControlCommand;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.plugin.store.NodeCheckpointStore;
import com.infenia.yukta.service.control.ControlBusService;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlRegistry;
import com.infenia.yukta.service.orchestrator.WorkflowOrchestrator;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import jakarta.annotation.PostConstruct;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Bridges the Control Bus to the workflow execution layer.
 *
 * <p>On startup, subscribes to the raw control stream from {@link ControlBusService}. For every
 * message whose payload is a {@link ControlCommand}, finds the first matching {@link
 * ControlSignalProcessor} (by priority), obtains a {@link WorkflowDirective}, and applies it to the
 * active execution found in {@link ExecutionControlRegistry}.
 *
 * <p>Directive application is fire-and-forget for restart operations (a new execution is subscribed
 * independently). Stop is synchronous (just completes a sink).
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

  /** The workflow orchestrator for restart operations. */
  private final WorkflowOrchestrator orchestrator;

  /** The node checkpoint store for accessing node replay data. */
  private final NodeCheckpointStore checkpointStore;

  /** The control bus service for subscribing to control signals. */
  private final ControlBusService controlBusService;

  /**
   * Constructs a DirectiveDispatcher.
   *
   * @param processors the registered signal processors, ordered by priority
   * @param registry the live execution registry
   * @param orchestrator the workflow orchestrator for restart operations
   * @param checkpointStore the checkpoint store for node replay data
   * @param controlBusService the control bus to subscribe to
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public DirectiveDispatcher(
      final List<ControlSignalProcessor> processors,
      final ExecutionControlRegistry registry,
      final WorkflowOrchestrator orchestrator,
      final NodeCheckpointStore checkpointStore,
      final ControlBusService controlBusService) {
    this.processors = List.copyOf(processors);
    this.registry = registry;
    this.orchestrator = orchestrator;
    this.checkpointStore = checkpointStore;
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
      case WorkflowDirective.Restart _ -> applyRestart(control);
      case WorkflowDirective.RestartFromNode rfn -> applyRestartFromNode(control, rfn);
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

  private Mono<Void> applyRestart(final ExecutionControl control) {
    return Mono.fromRunnable(
            () -> {
              registry.unregister(control.executionId());
              control.safeStopSink().emitEmpty(FAIL_FAST);
            })
        .then(
            Mono.defer(
                () -> {
                  final String newExecutionId = UUID.randomUUID().toString();
                  orchestrator
                      .execute(
                          control.sessionId(),
                          control.workflowId(),
                          newExecutionId,
                          control.prepared(),
                          control.payload())
                      .subscribe(
                          v ->
                              log.atDebug().log("Restarted execution for {}", control.workflowId()),
                          e ->
                              log.atError()
                                  .setCause(e)
                                  .log("Restart failed for {}", control.workflowId()));
                  return Mono.empty();
                }));
  }

  private Mono<Void> applyRestartFromNode(
      final ExecutionControl control, final WorkflowDirective.RestartFromNode directive) {
    final List<String> parentNodeIds =
        control.prepared().parentsList().getOrDefault(directive.nodeId(), List.of()).stream()
            .map(WorkflowNode::nodeId)
            .toList();

    final Map<String, Message<?>> parentCheckpoints = new ConcurrentHashMap<>();

    return Flux.fromIterable(parentNodeIds)
        .flatMap(
            parentNodeId ->
                checkpointStore
                    .get(control.executionId(), parentNodeId)
                    .doOnNext(msg -> parentCheckpoints.put(parentNodeId, msg))
                    .onErrorResume(
                        e -> {
                          log.atWarn().log("No checkpoint for parent node {}", parentNodeId);
                          return Mono.empty();
                        }))
        .then(
            Mono.defer(
                () -> {
                  registry.unregister(control.executionId());
                  control.safeStopSink().emitEmpty(FAIL_FAST);
                  checkpointStore.clear(control.executionId());

                  final String newExecutionId = UUID.randomUUID().toString();
                  orchestrator
                      .restartFromNode(
                          control.sessionId(),
                          control.workflowId(),
                          control.executionId(),
                          newExecutionId,
                          control.prepared(),
                          directive.nodeId(),
                          parentCheckpoints)
                      .subscribe(
                          v ->
                              log.atDebug().log(
                                  "RestartFromNode {} completed for {}",
                                  directive.nodeId(),
                                  control.workflowId()),
                          e ->
                              log.atError()
                                  .setCause(e)
                                  .log(
                                      "RestartFromNode {} failed for {}",
                                      directive.nodeId(),
                                      control.workflowId()));
                  return Mono.empty();
                }));
  }
}
