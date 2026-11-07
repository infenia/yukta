// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.workflow.store;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.model.supervisor.SupervisorRef;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.testing.Fixtures;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class WorkflowDiffTest {

  private static WorkflowDefinition definition(
      final String description, final List<Node> nodes, final List<Edge> edges) {
    return Fixtures.workflow("w", description, nodes, edges);
  }

  @Test
  void reportsAddedRemovedAndChangedNodes_andEdges() {
    final WorkflowDefinition older =
        definition(
            "old",
            List.of(
                Fixtures.node("a", "TRIGGER", Map.of()),
                Fixtures.node("b", "MAPPER", Map.of("x", 1)),
                Fixtures.node("gone", "CONSOLE", Map.of())),
            List.of(new Edge("a", "b", null), new Edge("b", "gone", "error")));
    final WorkflowDefinition newer =
        definition(
            "new",
            List.of(
                Fixtures.node("a", "TRIGGER", Map.of()),
                Fixtures.node("b", "MAPPER", Map.of("x", 2)),
                Fixtures.node("end", "CONSOLE", Map.of())),
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
    final List<Node> nodes = List.of(Fixtures.node("a", "T", Map.of()));

    final WorkflowDefinition missing = definition(null, nodes, List.of());

    final WorkflowDiff withOne = WorkflowDiff.between(missing, definition("d", nodes, List.of()));
    final WorkflowDiff withNone = WorkflowDiff.between(missing, missing);

    assertThat(withOne.descriptionChanged()).isTrue();
    assertThat(withNone.descriptionChanged()).isFalse();
  }

  @Test
  void identicalDefinitions_haveNoDifferences() {
    final WorkflowDefinition same =
        definition("d", List.of(Fixtures.node("a", "T", Map.of())), List.of());

    final WorkflowDiff diff = WorkflowDiff.between(same, same);

    assertThat(diff.descriptionChanged()).isFalse();
    assertThat(diff.addedNodes()).isEmpty();
    assertThat(diff.changedNodes()).isEmpty();
    assertThat(diff.removedEdges()).isEmpty();
  }

  @Test
  void declaredSupervisors_areComparedByIdNodesAndEnabled() {
    final List<Node> nodes = List.of(Fixtures.node("a", "T", Map.of()));
    final WorkflowDefinition older =
        new WorkflowDefinition(
            "w",
            "d",
            nodes,
            List.of(),
            List.of(
                new SupervisorRef("keep", null, null),
                new SupervisorRef("gone", List.of("b", "a"), null)));
    final WorkflowDefinition newer =
        new WorkflowDefinition(
            "w",
            "d",
            nodes,
            List.of(),
            List.of(new SupervisorRef("keep", null, null), new SupervisorRef("new", null, false)));

    final WorkflowDiff diff = WorkflowDiff.between(older, newer);

    assertThat(diff.addedSupervisors()).containsExactly("new (disabled)");
    assertThat(diff.removedSupervisors()).containsExactly("gone[a,b]");
  }
}
