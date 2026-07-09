// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.factory;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

/** Test for {@link ExecutionControlFactory}. */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.CommentRequired"})
@NoArgsConstructor
class ExecutionControlFactoryTest {

  private ExecutionControlFactory factory;

  @BeforeEach
  void setUp() {
    factory = new ExecutionControlFactory();
  }

  @Test
  void testCreateWithSingleNodeWorkflow() {
    // Given
    final String sessionId = "session-1";
    final String workflowId = "workflow-1";
    final String executionId = "exec-1";
    final Map<String, Object> payload = Map.of("key", "value");

    // Mock PreparedWorkflow with single node
    final PreparedWorkflow prepared = Mockito.mock(PreparedWorkflow.class);
    final WorkflowNode node = Mockito.mock(WorkflowNode.class);
    Mockito.when(node.nodeId()).thenReturn("node-1");
    Mockito.when(prepared.topologicalOrder()).thenReturn(List.of(node));

    // When
    final ExecutionControl control =
        factory.create(sessionId, workflowId, executionId, prepared, payload);

    // Then
    assertThat(control.sessionId()).isEqualTo(sessionId);
    assertThat(control.workflowId()).isEqualTo(workflowId);
    assertThat(control.executionId()).isEqualTo(executionId);
    assertThat(control.payload()).containsAllEntriesOf(payload);
    assertThat(control.prepared()).isSameAs(prepared);

    // Verify global controls are initialized
    assertThat(control.immediateStopSink()).isNotNull();
    assertThat(control.safeStopSink()).isNotNull();
    assertThat(control.globalPauseValve()).isInstanceOf(ReactiveControlValve.class);

    // Verify node-specific controls are initialized for the node
    assertThat(control.nodeImmediateStopSinks()).containsKey("node-1");
    assertThat(control.nodeSafeStopSinks()).containsKey("node-1");
    assertThat(control.nodePauseValves()).containsKey("node-1");
    assertThat(control.nodeSkipFlags()).containsKey("node-1");
    assertThat(control.nodeStepModes()).containsKey("node-1");
    assertThat(control.nodeStepSinks()).containsKey("node-1");

    // Verify node control types
    assertThat(control.nodePauseValves().get("node-1")).isInstanceOf(ReactiveControlValve.class);
    assertThat(control.nodeSkipFlags().get("node-1"))
        .isInstanceOf(java.util.concurrent.atomic.AtomicBoolean.class);
    assertThat(control.nodeStepModes().get("node-1"))
        .isInstanceOf(java.util.concurrent.atomic.AtomicBoolean.class);
    assertThat(control.nodeStepSinks().get("node-1")).isNotNull();

    // Verify initial state of node controls
    assertThat(control.nodeSkipFlags().get("node-1").get()).isFalse();
    assertThat(control.nodeStepModes().get("node-1").get()).isFalse();
  }

  @Test
  void testCreateWithMultipleNodeWorkflow() {
    // Given
    final String sessionId = "session-1";
    final String workflowId = "workflow-1";
    final String executionId = "exec-1";
    final Map<String, Object> payload = Map.of();

    // Mock PreparedWorkflow with multiple nodes
    final PreparedWorkflow prepared = Mockito.mock(PreparedWorkflow.class);
    final WorkflowNode node1 = Mockito.mock(WorkflowNode.class);
    final WorkflowNode node2 = Mockito.mock(WorkflowNode.class);
    final WorkflowNode node3 = Mockito.mock(WorkflowNode.class);

    Mockito.when(node1.nodeId()).thenReturn("node-1");
    Mockito.when(node2.nodeId()).thenReturn("node-2");
    Mockito.when(node3.nodeId()).thenReturn("node-3");
    Mockito.when(prepared.topologicalOrder()).thenReturn(List.of(node1, node2, node3));

    // When
    final ExecutionControl control =
        factory.create(sessionId, workflowId, executionId, prepared, payload);

    // Then
    assertThat(control.nodeImmediateStopSinks()).hasSize(3);
    assertThat(control.nodeSafeStopSinks()).hasSize(3);
    assertThat(control.nodePauseValves()).hasSize(3);
    assertThat(control.nodeSkipFlags()).hasSize(3);
    assertThat(control.nodeStepModes()).hasSize(3);
    assertThat(control.nodeStepSinks()).hasSize(3);

    // Verify all node IDs are present
    assertThat(control.nodeImmediateStopSinks()).containsKeys("node-1", "node-2", "node-3");
    assertThat(control.nodeSafeStopSinks()).containsKeys("node-1", "node-2", "node-3");
    assertThat(control.nodePauseValves()).containsKeys("node-1", "node-2", "node-3");
    assertThat(control.nodeSkipFlags()).containsKeys("node-1", "node-2", "node-3");
    assertThat(control.nodeStepModes()).containsKeys("node-1", "node-2", "node-3");
    assertThat(control.nodeStepSinks()).containsKeys("node-1", "node-2", "node-3");

    // Verify initial state
    assertThat(control.nodeSkipFlags().get("node-1").get()).isFalse();
    assertThat(control.nodeSkipFlags().get("node-2").get()).isFalse();
    assertThat(control.nodeSkipFlags().get("node-3").get()).isFalse();

    assertThat(control.nodeStepModes().get("node-1").get()).isFalse();
    assertThat(control.nodeStepModes().get("node-2").get()).isFalse();
    assertThat(control.nodeStepModes().get("node-3").get()).isFalse();
  }

  @Test
  void testCreateWithEmptyWorkflow() {
    // Given
    final String sessionId = "session-1";
    final String workflowId = "workflow-1";
    final String executionId = "exec-1";
    final Map<String, Object> payload = Map.of("test", "data");

    // Mock PreparedWorkflow with no nodes
    final PreparedWorkflow prepared = Mockito.mock(PreparedWorkflow.class);
    Mockito.when(prepared.topologicalOrder()).thenReturn(List.of());

    // When
    final ExecutionControl control =
        factory.create(sessionId, workflowId, executionId, prepared, payload);

    // Then
    // Basic properties should still be set
    assertThat(control.sessionId()).isEqualTo(sessionId);
    assertThat(control.workflowId()).isEqualTo(workflowId);
    assertThat(control.executionId()).isEqualTo(executionId);
    assertThat(control.payload()).containsAllEntriesOf(payload);
    assertThat(control.prepared()).isSameAs(prepared);

    // Global controls should still be initialized
    assertThat(control.immediateStopSink()).isNotNull();
    assertThat(control.safeStopSink()).isNotNull();
    assertThat(control.globalPauseValve()).isInstanceOf(ReactiveControlValve.class);

    // Node-specific collections should be empty
    assertThat(control.nodeImmediateStopSinks()).isEmpty();
    assertThat(control.nodeSafeStopSinks()).isEmpty();
    assertThat(control.nodePauseValves()).isEmpty();
    assertThat(control.nodeSkipFlags()).isEmpty();
    assertThat(control.nodeStepModes()).isEmpty();
    assertThat(control.nodeStepSinks()).isEmpty();
  }

  @Test
  void testNodeControlsAreIndependentInstances() {
    // Given
    final PreparedWorkflow prepared = Mockito.mock(PreparedWorkflow.class);
    final WorkflowNode node1 = Mockito.mock(WorkflowNode.class);
    final WorkflowNode node2 = Mockito.mock(WorkflowNode.class);

    Mockito.when(node1.nodeId()).thenReturn("node-1");
    Mockito.when(node2.nodeId()).thenReturn("node-2");
    Mockito.when(prepared.topologicalOrder()).thenReturn(List.of(node1, node2));

    // When
    final ExecutionControl control = factory.create("s", "w", "e", prepared, Map.of());

    // Then
    // Get references to the same node's controls twice
    final var valve1First = control.nodePauseValves().get("node-1");
    final var valve1Second = control.nodePauseValves().get("node-1");
    final var skip1First = control.nodeSkipFlags().get("node-1");
    final var skip1Second = control.nodeSkipFlags().get("node-1");

    // Verify they are the same instances (consistent mapping)
    assertThat(valve1First).isSameAs(valve1Second);
    assertThat(skip1First).isSameAs(skip1Second);

    // Verify different nodes have different instances
    final var valve2 = control.nodePauseValves().get("node-2");
    final var skip2 = control.nodeSkipFlags().get("node-2");

    assertThat(valve1First).isNotSameAs(valve2);
    assertThat(skip1First).isNotSameAs(skip2);

    // Verify initial states are independent
    assertThat(skip1First.get()).isFalse();
    assertThat(skip2.get()).isFalse();

    // Modify one skip flag
    skip1First.set(true);

    // Verify only that flag changed
    assertThat(skip1First.get()).isTrue();
    assertThat(skip1Second.get()).isTrue(); // Same instance
    assertThat(skip2.get()).isFalse(); // Different instance, unchanged
  }
}
