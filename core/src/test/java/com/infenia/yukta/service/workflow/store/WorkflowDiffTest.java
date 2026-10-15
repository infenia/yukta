// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.CommentRequired")
class WorkflowDiffTest {

  private static WorkflowDefinition definition(
      final String description, final List<Node> nodes, final List<Edge> edges) {
    return new WorkflowDefinition("w", description, nodes, edges);
  }

  @Test
  void reportsAddedRemovedAndChangedNodes_andEdges() {
    final WorkflowDefinition older =
        definition(
            "old",
            List.of(
                new Node("a", "TRIGGER", Map.of()),
                new Node("b", "MAPPER", Map.of("x", 1)),
                new Node("gone", "CONSOLE", Map.of())),
            List.of(new Edge("a", "b", null), new Edge("b", "gone", "error")));
    final WorkflowDefinition newer =
        definition(
            "new",
            List.of(
                new Node("a", "TRIGGER", Map.of()),
                new Node("b", "MAPPER", Map.of("x", 2)),
                new Node("end", "CONSOLE", Map.of())),
            List.of(new Edge("a", "b", null), new Edge("b", "end", null)));

    final WorkflowDiff diff = WorkflowDiff.between(older, newer);

    assertThat(diff.descriptionChanged()).isTrue();
    assertThat(diff.addedNodes()).containsExactly("end");
    assertThat(diff.removedNodes()).containsExactly("gone");
    assertThat(diff.changedNodes()).containsExactly("b");
    assertThat(diff.addedEdges()).containsExactly("b -> end");
    assertThat(diff.removedEdges()).containsExactly("b -> gone:error");
  }

  @Test
  void missingDescription_isComparedNotDereferenced() {
    final List<Node> nodes = List.of(new Node("a", "T", Map.of()));

    final WorkflowDefinition missing = definition(null, nodes, List.of());

    final WorkflowDiff withOne = WorkflowDiff.between(missing, definition("d", nodes, List.of()));
    final WorkflowDiff withNone = WorkflowDiff.between(missing, missing);

    assertThat(withOne.descriptionChanged()).isTrue();
    assertThat(withNone.descriptionChanged()).isFalse();
  }

  @Test
  void identicalDefinitions_haveNoDifferences() {
    final WorkflowDefinition same =
        definition("d", List.of(new Node("a", "T", Map.of())), List.of());

    final WorkflowDiff diff = WorkflowDiff.between(same, same);

    assertThat(diff.descriptionChanged()).isFalse();
    assertThat(diff.addedNodes()).isEmpty();
    assertThat(diff.changedNodes()).isEmpty();
    assertThat(diff.removedEdges()).isEmpty();
  }
}
