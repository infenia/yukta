// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import java.util.Collections;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.extern.slf4j.Slf4j;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

/**
 * Runtime handle for a live workflow execution.
 *
 * <p>Registered in {@link com.infenia.yukta.service.control.store.ExecutionControlRegistry} when an
 * execution starts and removed when it finishes. The {@code DirectiveDispatcher} looks up this
 * record to apply stop, restart, or restart-from-node directives without coupling to the
 * orchestrator's internal state.
 *
 * <p>Control hierarchy:
 *
 * <ul>
 *   <li><strong>immediateStopSink:</strong> Hard stop; cancels upstream immediately
 *   <li><strong>safeStopSink:</strong> Drain & stop; completes streams after inflight work
 *   <li><strong>globalPauseValve:</strong> Pauses all nodes via backpressure
 *   <li><strong>nodeImmediateStopSinks:</strong> Per-node hard stop
 *   <li><strong>nodeSafeStopSinks:</strong> Per-node drain & stop
 *   <li><strong>nodePauseValves:</strong> Per-node pause via backpressure
 *   <li><strong>nodeSkipFlags:</strong> Per-node bypass (skip processing, pass through)
 *   <li><strong>nodeStepModes:</strong> Per-node debug step-through (one element at a time)
 *   <li><strong>nodeStepSinks:</strong> Per-node step signal sink (signals next step)
 * </ul>
 *
 * @param sessionId the session that owns this execution
 * @param workflowId the workflow being executed
 * @param executionId the unique execution identifier
 * @param prepared the prepared workflow (definition, plugin cache, topology)
 * @param payload the original trigger payload used to start the execution
 * @param immediateStopSink hard stop cancels upstream immediately
 * @param safeStopSink drain & stop completes streams after inflight work
 * @param globalPauseValve pauses all nodes via backpressure
 * @param nodeImmediateStopSinks per-node hard stop (keyed by nodeId)
 * @param nodeSafeStopSinks per-node drain & stop (keyed by nodeId)
 * @param nodePauseValves per-node pause (keyed by nodeId)
 * @param nodeSkipFlags per-node bypass flags (keyed by nodeId)
 * @param nodeStepModes per-node step-through debug mode flags (keyed by nodeId)
 * @param nodeStepSinks per-node step signal sinks (keyed by nodeId)
 */
@Slf4j
@SuppressWarnings("PMD.DataClass")
public record ExecutionControl(
    String sessionId,
    String workflowId,
    String executionId,
    PreparedWorkflow prepared,
    Map<String, Object> payload,
    Sinks.One<Void> immediateStopSink,
    Sinks.One<Void> safeStopSink,
    ReactiveControlValve globalPauseValve,
    Map<String, Sinks.One<Void>> nodeImmediateStopSinks,
    Map<String, Sinks.One<Void>> nodeSafeStopSinks,
    Map<String, ReactiveControlValve> nodePauseValves,
    Map<String, AtomicBoolean> nodeSkipFlags,
    Map<String, AtomicBoolean> nodeStepModes,
    Map<String, Sinks.Many<Void>> nodeStepSinks) {

  /** Compact constructor: makes all maps immutable and enforces field consistency. */
  public ExecutionControl {
    payload = Map.copyOf(payload);
    nodeImmediateStopSinks = Map.copyOf(nodeImmediateStopSinks);
    nodeSafeStopSinks = Map.copyOf(nodeSafeStopSinks);
    nodePauseValves = Map.copyOf(nodePauseValves);
    nodeSkipFlags = Map.copyOf(nodeSkipFlags);
    nodeStepModes = Map.copyOf(nodeStepModes);
    nodeStepSinks = Map.copyOf(nodeStepSinks);
  }

  @Override
  public Map<String, Sinks.One<Void>> nodeImmediateStopSinks() {
    return Collections.unmodifiableMap(nodeImmediateStopSinks);
  }

  @Override
  public Map<String, Sinks.One<Void>> nodeSafeStopSinks() {
    return Collections.unmodifiableMap(nodeSafeStopSinks);
  }

  @Override
  public Map<String, ReactiveControlValve> nodePauseValves() {
    return Collections.unmodifiableMap(nodePauseValves);
  }

  @Override
  public Map<String, AtomicBoolean> nodeSkipFlags() {
    return Collections.unmodifiableMap(nodeSkipFlags);
  }

  @Override
  public Map<String, AtomicBoolean> nodeStepModes() {
    return Collections.unmodifiableMap(nodeStepModes);
  }

  @Override
  public Map<String, Sinks.Many<Void>> nodeStepSinks() {
    return Collections.unmodifiableMap(nodeStepSinks);
  }

  /**
   * Applies pre-processing controls (safe stop + skip) to input stream.
   *
   * <p>Applied before stream enters Processor or Terminal plugin. Severs upstream connection but
   * allows inflight work to finish.
   *
   * @param nodeId the target node
   * @param inputFlux the incoming flux
   * @return flux with safe-stop and skip logic applied
   */
  public Flux<Message<?>> applyPreProcessingControls(
      final String nodeId, final Flux<Message<?>> inputFlux) {
    final AtomicBoolean skip = nodeSkipFlags.get(nodeId);
    final Sinks.One<Void> safeSink = nodeSafeStopSinks.get(nodeId);

    Flux<Message<?>> result = inputFlux;

    if (skip != null) {
      result =
          result.doOnNext(
              msg -> {
                if (skip.get()) {
                  log.atDebug().addKeyValue("nodeId", nodeId).log("Node marked for skip");
                }
              });
    }

    if (safeSink != null) {
      result = result.takeUntilOther(safeSink.asMono());
    }

    return result;
  }

  /**
   * Applies post-processing controls (immediate stop + pause) to output stream.
   *
   * <p>Applied after plugin generates/processes data. Applies backpressure up the chain via pause
   * valves and immediate stop.
   *
   * @param nodeId the target node
   * @param outputFlux the outgoing flux
   * @return flux with immediate-stop and pause logic applied
   */
  public Flux<Message<?>> applyPostProcessingControls(
      final String nodeId, final Flux<Message<?>> outputFlux) {
    final Sinks.One<Void> immediateSink = nodeImmediateStopSinks.get(nodeId);
    final ReactiveControlValve nodeValve = nodePauseValves.get(nodeId);

    Flux<Message<?>> result = outputFlux;

    if (nodeValve != null) {
      result =
          result.filterWhen(
              msg ->
                  nodeValve
                      .allowPassage()
                      .onErrorResume(
                          e -> {
                            log.atError()
                                .addKeyValue("nodeId", nodeId)
                                .setCause(e)
                                .log("Error in node pause valve");
                            return Mono.just(true);
                          }));
    }

    if (globalPauseValve != null) {
      result =
          result.filterWhen(
              msg ->
                  globalPauseValve
                      .allowPassage()
                      .onErrorResume(
                          e -> {
                            log.atError()
                                .addKeyValue("nodeId", nodeId)
                                .setCause(e)
                                .log("Error in global pause valve");
                            return Mono.just(true);
                          }));
    }

    if (immediateSink != null) {
      result = result.takeUntilOther(immediateSink.asMono());
    }

    return result;
  }
}
