// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.factory;

import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.springframework.stereotype.Component;
import reactor.core.publisher.Sinks;

/**
 * Factory for creating ExecutionControl instances.
 *
 * <p>Initializes all per-node control structures (pause valves, skip flags, step mode, etc.) and
 * global control handles for a workflow execution.
 */
@Component
@NoArgsConstructor
public class ExecutionControlFactory {

  /**
   * Creates an ExecutionControl for a workflow execution.
   *
   * <p>Initializes per-node control maps for every node in the workflow's topological order. Each
   * node gets its own pause valve, skip flag, stop sinks, and step-through controls.
   *
   * @param sessionId the session identifier
   * @param workflowId the workflow identifier
   * @param executionId the execution identifier
   * @param prepared the prepared workflow (contains topological order)
   * @param payload the initial trigger payload
   * @return a new ExecutionControl instance ready for use
   */
  public ExecutionControl create(
      final String sessionId,
      final String workflowId,
      final String executionId,
      final PreparedWorkflow prepared,
      final Map<String, Object> payload) {

    final List<String> nodeIds =
        prepared.topologicalOrder().stream().map(WorkflowNode::nodeId).toList();

    final Map<String, Sinks.One<Void>> nodeImmediateStopSinks = new ConcurrentHashMap<>();
    final Map<String, Sinks.One<Void>> nodeSafeStopSinks = new ConcurrentHashMap<>();
    final Map<String, ReactiveControlValve> nodePauseValves = new ConcurrentHashMap<>();
    final Map<String, AtomicBoolean> nodeSkipFlags = new ConcurrentHashMap<>();
    final Map<String, AtomicBoolean> nodeStepModes = new ConcurrentHashMap<>();
    final Map<String, Sinks.Many<Void>> nodeStepSinks = new ConcurrentHashMap<>();

    nodeIds.forEach(
        nodeId -> {
          nodeImmediateStopSinks.put(nodeId, Sinks.one());
          nodeSafeStopSinks.put(nodeId, Sinks.one());
          nodePauseValves.put(nodeId, new ReactiveControlValve());
          nodeSkipFlags.put(nodeId, new AtomicBoolean(false));
          nodeStepModes.put(nodeId, new AtomicBoolean(false));
          nodeStepSinks.put(nodeId, Sinks.many().multicast().onBackpressureBuffer());
        });

    return new ExecutionControl(
        sessionId,
        workflowId,
        executionId,
        prepared,
        payload,
        Sinks.one(),
        Sinks.one(),
        new ReactiveControlValve(),
        nodeImmediateStopSinks,
        nodeSafeStopSinks,
        nodePauseValves,
        nodeSkipFlags,
        nodeStepModes,
        nodeStepSinks);
  }
}
