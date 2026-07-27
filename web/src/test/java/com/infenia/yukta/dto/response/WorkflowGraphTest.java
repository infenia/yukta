// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.dto.response;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.core.PluginCategory;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for WorkflowGraph. */
@NoArgsConstructor
class WorkflowGraphTest {

  /** Workflow identifier constant. */
  private static final String WORKFLOW_ID = "wf-982-xk-11";

  /** Description constant. */
  private static final String DESCRIPTION = "Supply chain optimizer";

  /** Node identifier constant. */
  private static final String NODE_ID = "data-ingress";

  /** Sample node constant. */
  private static final WorkflowGraphNode NODE =
      new WorkflowGraphNode(
          NODE_ID, "ingress-v1", PluginCategory.TRIGGER, null, null, List.of("default"));

  /** Sample edge constant. */
  private static final WorkflowGraphEdge EDGE =
      new WorkflowGraphEdge(NODE_ID, "sink-storage", null);

  @Test
  void constructor_validInputs_createsRecord() {
    // Given-When
    final WorkflowGraph graph =
        new WorkflowGraph(WORKFLOW_ID, DESCRIPTION, List.of(NODE), List.of(EDGE), List.of(NODE_ID));

    // Then
    assertThat(graph.workflowId()).isEqualTo(WORKFLOW_ID);
    assertThat(graph.description()).isEqualTo(DESCRIPTION);
    assertThat(graph.nodes()).containsExactly(NODE);
    assertThat(graph.edges()).containsExactly(EDGE);
    assertThat(graph.topologicalOrder()).containsExactly(NODE_ID);
  }

  @Test
  void constructor_withNullCollections_convertsToEmptyLists() {
    // Given-When
    final WorkflowGraph graph = new WorkflowGraph(WORKFLOW_ID, DESCRIPTION, null, null, null);

    // Then
    assertThat(graph.nodes()).isEmpty();
    assertThat(graph.edges()).isEmpty();
    assertThat(graph.topologicalOrder()).isEmpty();
  }

  @Test
  void collections_modificationAttempt_throwsUnsupportedOperationException() {
    // Given
    final WorkflowGraph graph =
        new WorkflowGraph(WORKFLOW_ID, DESCRIPTION, List.of(NODE), List.of(EDGE), List.of(NODE_ID));

    // When-Then
    assertThatThrownBy(() -> graph.nodes().add(NODE))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> graph.edges().add(EDGE))
        .isInstanceOf(UnsupportedOperationException.class);
    assertThatThrownBy(() -> graph.topologicalOrder().add(NODE_ID))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void equals_sameValues_returnsTrue() {
    // Given-When-Then
    assertThat(new WorkflowGraph(WORKFLOW_ID, DESCRIPTION, List.of(NODE), List.of(), List.of()))
        .isEqualTo(
            new WorkflowGraph(WORKFLOW_ID, DESCRIPTION, List.of(NODE), List.of(), List.of()));
  }

  @Test
  void verifyToStringContainsRelevantFieldValues() {
    // Given-When
    final String actual =
        new WorkflowGraph(WORKFLOW_ID, DESCRIPTION, List.of(), List.of(), List.of()).toString();

    // Then
    assertThat(actual).contains("WorkflowGraph").contains(WORKFLOW_ID);
  }
}
