// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.model.workflow.WorkflowTemplate;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings({"PMD.CommentRequired", "PMD.TooManyMethods"})
class RestartPlanTest {

  private static WorkflowNode node(final String nodeId) {
    return new WorkflowNode(nodeId, "t", Map.of());
  }

  // a -> b -> d -> e, c -> d, and an independent branch x -> y
  private static PreparedWorkflow diamondWithIndependentBranch() {
    return new PreparedWorkflow(
        List.of(),
        Map.of(
            "a", List.of(node("b")),
            "b", List.of(node("d")),
            "c", List.of(node("d")),
            "d", List.of(node("e")),
            "e", List.of(),
            "x", List.of(node("y")),
            "y", List.of()),
        Map.of(
            "a", List.of(),
            "b", List.of(node("a")),
            "c", List.of(),
            "d", List.of(node("b"), node("c")),
            "e", List.of(node("d")),
            "x", List.of(),
            "y", List.of(node("x"))),
        Map.of(),
        List.of(node("a"), node("b"), node("c"), node("d"), node("e"), node("x"), node("y")),
        mock(WorkflowTemplate.class));
  }

  @Test
  void rerunsTheNodeAndItsDescendants_andReplaysOnlyWhatFeedsThem() {
    final RestartPlan plan = RestartPlan.compute(diamondWithIndependentBranch(), "d");

    assertThat(plan.rerun()).containsExactlyInAnyOrder("d", "e");
    assertThat(plan.boundary()).containsExactlyInAnyOrder("b", "c");
  }

  @Test
  void independentBranches_areNeitherRerunNorReplayed() {
    final RestartPlan plan = RestartPlan.compute(diamondWithIndependentBranch(), "b");

    assertThat(plan.rerun()).containsExactlyInAnyOrder("b", "d", "e");
    assertThat(plan.boundary()).containsExactlyInAnyOrder("a", "c");
    assertThat(plan.rerun()).doesNotContain("x", "y");
    assertThat(plan.boundary()).doesNotContain("x", "y");
  }

  @Test
  void restartingSourceNode_needsOnlyTheOtherParentReplayed() {
    final RestartPlan plan = RestartPlan.compute(diamondWithIndependentBranch(), "a");

    assertThat(plan.rerun()).containsExactlyInAnyOrder("a", "b", "d", "e");
    assertThat(plan.boundary()).containsExactly("c");
  }

  @Test
  void nodeReachedViaTwoPaths_isOnlyCountedOnce() {
    final RestartPlan plan = RestartPlan.compute(diamondWithIndependentBranch(), "e");

    assertThat(plan.rerun()).containsExactly("e");
    assertThat(plan.boundary()).containsExactly("d");
  }

  @Test
  void unknownNode_isRejected() {
    assertThatThrownBy(() -> RestartPlan.compute(diamondWithIndependentBranch(), "ghost"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessage("Unknown restart node: ghost");
  }

  @Test
  void nodeReachableViaTwoBranches_isRerunOnce() {
    final PreparedWorkflow fork =
        new PreparedWorkflow(
            List.of(),
            Map.of(
                "a", List.of(node("b"), node("c")),
                "b", List.of(node("d")),
                "c", List.of(node("d")),
                "d", List.of()),
            Map.of(
                "a", List.of(),
                "b", List.of(node("a")),
                "c", List.of(node("a")),
                "d", List.of(node("b"), node("c"))),
            Map.of(),
            List.of(node("a"), node("b"), node("c"), node("d")),
            mock(WorkflowTemplate.class));

    final RestartPlan plan = RestartPlan.compute(fork, "a");

    assertThat(plan.rerun()).containsExactlyInAnyOrder("a", "b", "c", "d");
    assertThat(plan.boundary()).isEmpty();
  }

  @Test
  void resuming_rerunsEverythingNotFinished_andReplaysFinishedParents() {
    final RestartPlan plan =
        RestartPlan.resuming(diamondWithIndependentBranch(), java.util.Set.of("a", "c", "x"));

    assertThat(plan.rerun()).containsExactlyInAnyOrder("b", "d", "e", "y");
    assertThat(plan.boundary()).containsExactlyInAnyOrder("a", "c", "x");
  }

  @Test
  void resuming_withNothingFinished_rerunsAllAndReplaysNothing() {
    final RestartPlan plan =
        RestartPlan.resuming(diamondWithIndependentBranch(), java.util.Set.of());

    assertThat(plan.rerun()).hasSize(7);
    assertThat(plan.boundary()).isEmpty();
  }

  @Test
  void resuming_withEverythingFinished_hasNothingToRun() {
    final RestartPlan plan =
        RestartPlan.resuming(
            diamondWithIndependentBranch(), java.util.Set.of("a", "b", "c", "d", "e", "x", "y"));

    assertThat(plan.rerun()).isEmpty();
  }
}
