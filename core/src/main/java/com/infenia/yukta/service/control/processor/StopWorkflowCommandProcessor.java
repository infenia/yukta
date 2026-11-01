// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.model.execution.ControlState;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.plugin.control.ControlSignalProcessor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.StopWorkflowCommand;
import com.infenia.yukta.plugin.control.StopMode;
import com.infenia.yukta.plugin.control.WorkflowDirective;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.store.ExecutionControlStore;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import edu.umd.cs.findbugs.annotations.SuppressFBWarnings;
import java.time.Duration;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Mono;

/**
 * Processor for stop workflow commands.
 *
 * <p>Three modes: {@code DRAIN} (default) stops the triggers so no new input enters and lets work
 * already in flight finish, falling back to a cancel after a deadline; {@code CANCEL} cancels every
 * node at once; {@code KILL} cancels and removes the execution. In every mode the run is reported
 * as stopped, not successful.
 */
@Slf4j
@Component
public class StopWorkflowCommandProcessor implements ControlSignalProcessor {

  /** The execution control registry for accessing execution control state. */
  private final ExecutionControlStore registry;

  /** Reports that the run is stopping. */
  private final TaskTrackerService taskTracker;

  /** How long a drain may take before the run is cancelled. */
  private final Duration drainTimeout;

  /**
   * Creates the processor.
   *
   * @param registry the execution control registry
   * @param taskTracker reports the stopping state
   * @param properties the {@code yukta.stop} settings: how long a drain may take
   */
  @Autowired
  public StopWorkflowCommandProcessor(
      final ExecutionControlStore registry,
      final TaskTrackerService taskTracker,
      final YuktaProperties properties) {
    this(registry, taskTracker, properties.stop().drainTimeout());
  }

  /**
   * Creates the processor.
   *
   * @param registry the execution control registry
   * @param taskTracker reports the stopping state
   * @param drainTimeout how long a drain may take before falling back to a cancel
   */
  @SuppressFBWarnings("EI_EXPOSE_REP2")
  public StopWorkflowCommandProcessor(
      final ExecutionControlStore registry,
      final TaskTrackerService taskTracker,
      final Duration drainTimeout) {
    this.registry = registry;
    this.taskTracker = taskTracker;
    this.drainTimeout = drainTimeout;
  }

  @Override
  public boolean canProcess(final ExecutionControlCommand command) {
    return command instanceof StopWorkflowCommand;
  }

  @Override
  public Mono<WorkflowDirective> process(final ExecutionControlCommand command) {
    final StopWorkflowCommand stop = (StopWorkflowCommand) command;
    return Mono.defer(
        () -> {
          final ExecutionControl control =
              registry
                  .findByExecutionId(stop.executionId())
                  .orElseThrow(
                      () -> new NotFoundException("Execution not found: " + stop.executionId()));

          control.stopRequested().set(true);
          log.atDebug()
              .addKeyValue("executionId", stop.executionId())
              .addKeyValue("reason", stop.reason())
              .addKeyValue("mode", stop.mode())
              .log("Stopping workflow");

          if (stop.mode() == StopMode.KILL) {
            return Mono.just(new WorkflowDirective.Stop(String.valueOf(stop.reason())));
          }
          if (stop.mode() == StopMode.DRAIN && drain(control)) {
            taskTracker.emitControlStateEvent(stop.executionId(), ControlState.STOPPING);
            return Mono.empty();
          }
          // Already-stopped sinks are terminated; stopping twice is a no-op.
          control.safeStopSink().tryEmitEmpty();
          return Mono.empty();
        });
  }

  /**
   * Stops the triggers and arms the fallback cancel.
   *
   * @return false if the triggers cannot be identified, in which case the caller cancels instead
   */
  private boolean drain(final ExecutionControl control) {
    final PreparedWorkflow prepared = control.prepared();
    if (prepared == null) {
      return false;
    }
    // A paused run could never drain, so let held elements through first.
    control.nodePauseValves().values().forEach(ReactiveControlValve::resume);
    if (control.globalPauseValve() != null) {
      control.globalPauseValve().resume();
    }
    prepared.parentsList().forEach((nodeId, parents) -> stopIfTrigger(control, nodeId, parents));
    // The orchestrator completes the safe-stop sink when the run ends, which cancels this timer.
    Mono.delay(drainTimeout)
        .takeUntilOther(control.safeStopSink().asMono())
        .subscribe(
            _ -> {
              log.atWarn()
                  .addKeyValue("executionId", control.executionId())
                  .log("Drain deadline reached, cancelling the execution");
              control.safeStopSink().tryEmitEmpty();
            });
    return true;
  }

  private static void stopIfTrigger(
      final ExecutionControl control, final String nodeId, final java.util.List<?> parents) {
    final var sink = control.nodeSafeStopSinks().get(nodeId);
    if (parents.isEmpty() && sink != null) {
      sink.tryEmitEmpty();
    }
  }

  @Override
  public int getPriority() {
    return 20;
  }
}
