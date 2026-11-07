// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.testing;

import com.infenia.yukta.model.execution.TaskProgress;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowExecutionSummary;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.model.supervisor.SupervisorRef;
import com.infenia.yukta.model.workflow.NodePolicy;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.service.supervisor.BoundSupervisor;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Short forms of the model records for tests: each fills the fields a test does not care about with
 * the default the records used to offer as extra constructors.
 */
public final class Fixtures {

  private Fixtures() {}

  /** A progress snapshot with no control state, definition version or place in a tree. */
  public static WorkflowProgress workflowProgress(
      final String executionId,
      final String sessionId,
      final String workflowId,
      final TaskStatus status,
      final List<TaskProgress> tasks,
      final Instant startTime,
      final Instant endTime) {
    return workflowProgress(
        executionId, sessionId, workflowId, status, tasks, startTime, endTime, null);
  }

  /** A progress snapshot with a control state and no definition version or place in a tree. */
  public static WorkflowProgress workflowProgress(
      final String executionId,
      final String sessionId,
      final String workflowId,
      final TaskStatus status,
      final List<TaskProgress> tasks,
      final Instant startTime,
      final Instant endTime,
      final String controlState) {
    return workflowProgress(
        executionId, sessionId, workflowId, status, tasks, startTime, endTime, controlState, null);
  }

  /** A progress snapshot with no place in a tree of runs. */
  public static WorkflowProgress workflowProgress(
      final String executionId,
      final String sessionId,
      final String workflowId,
      final TaskStatus status,
      final List<TaskProgress> tasks,
      final Instant startTime,
      final Instant endTime,
      final String controlState,
      final String definitionVersion) {
    return new WorkflowProgress(
        executionId,
        sessionId,
        workflowId,
        status,
        tasks,
        startTime,
        endTime,
        controlState,
        definitionVersion,
        null,
        null,
        null);
  }

  /** A summary with no place in a tree of runs. */
  public static WorkflowExecutionSummary summary(
      final String executionId,
      final String workflowId,
      final TaskStatus status,
      final Instant startTime,
      final Instant endTime) {
    return summary(executionId, workflowId, status, startTime, endTime, null, null, null);
  }

  /** A summary in a tree of runs, without node progress. */
  public static WorkflowExecutionSummary summary(
      final String executionId,
      final String workflowId,
      final TaskStatus status,
      final Instant startTime,
      final Instant endTime,
      final String parentExecutionId,
      final String rootExecutionId,
      final String restartOf) {
    return summary(
        executionId,
        workflowId,
        status,
        startTime,
        endTime,
        parentExecutionId,
        rootExecutionId,
        restartOf,
        null);
  }

  /** A summary of a run whose initiator is not known. */
  public static WorkflowExecutionSummary summary(
      final String executionId,
      final String workflowId,
      final TaskStatus status,
      final Instant startTime,
      final Instant endTime,
      final String parentExecutionId,
      final String rootExecutionId,
      final String restartOf,
      final WorkflowExecutionSummary.Nodes nodes) {
    return new WorkflowExecutionSummary(
        executionId,
        workflowId,
        status,
        startTime,
        endTime,
        parentExecutionId,
        rootExecutionId,
        restartOf,
        nodes,
        null);
  }

  /** A policy with no element timeout. */
  public static NodePolicy nodePolicy(
      final NodePolicy.Retry retry,
      final NodePolicy.OnError onError,
      final NodePolicy.JoinRule trigger) {
    return nodePolicy(retry, onError, trigger, null);
  }

  /** A policy without a deadline. */
  public static NodePolicy nodePolicy(
      final NodePolicy.Retry retry,
      final NodePolicy.OnError onError,
      final NodePolicy.JoinRule trigger,
      final Duration elementTimeout) {
    return new NodePolicy(retry, onError, trigger, elementTimeout, null, null, null, null);
  }

  /** A policy that never reports a stall. */
  public static NodePolicy nodePolicy(
      final NodePolicy.Retry retry,
      final NodePolicy.OnError onError,
      final NodePolicy.JoinRule trigger,
      final Duration elementTimeout,
      final Duration deadline,
      final NodePolicy.FailureBudget failureBudget) {
    return new NodePolicy(
        retry, onError, trigger, elementTimeout, deadline, null, null, failureBudget);
  }

  /** A supervisor that does not watch internal sessions. */
  public static BoundSupervisor boundSupervisor(
      final SupervisorAttachment binding,
      final ExecutionSupervisor delegate,
      final boolean dryRun,
      final SupervisorCapabilities capabilities) {
    return new BoundSupervisor(binding, delegate, dryRun, capabilities, false);
  }

  /** A supervisor that may send every command and may not answer input requests. */
  public static BoundSupervisor boundSupervisor(
      final SupervisorAttachment binding,
      final ExecutionSupervisor delegate,
      final boolean dryRun) {
    return boundSupervisor(
        binding, delegate, dryRun, new SupervisorCapabilities(null, false, InputRisk.LOW));
  }

  /** A workflow that declares no supervisors and no group colors. */
  public static WorkflowDefinition workflow(
      final String workflowId,
      final String description,
      final List<WorkflowDefinition.Node> nodes,
      final List<WorkflowDefinition.Edge> edges) {
    return workflow(workflowId, description, nodes, edges, List.of());
  }

  /** A workflow without group colors. */
  public static WorkflowDefinition workflow(
      final String workflowId,
      final String description,
      final List<WorkflowDefinition.Node> nodes,
      final List<WorkflowDefinition.Edge> edges,
      final List<SupervisorRef> supervisors) {
    return new WorkflowDefinition(workflowId, description, nodes, edges, supervisors, Map.of());
  }

  /** A node in no declared group and with no color. */
  public static WorkflowDefinition.Node node(
      final String nodeId, final String type, final Map<String, Object> config) {
    return node(nodeId, type, config, null);
  }

  /** A node without a color. */
  public static WorkflowDefinition.Node node(
      final String nodeId,
      final String type,
      final Map<String, Object> config,
      final String group) {
    return new WorkflowDefinition.Node(nodeId, type, config, group, null);
  }
}
