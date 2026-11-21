// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.testrun;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.TooManyMethods")
class TestRunsTest {

  private static final String ID = TestRuns.PREFIX + "x";

  /** t -> a -> b -> end, and a -> other -> end2 (a branch the cut drops). */
  private static final WorkflowDefinition DRAFT =
      new WorkflowDefinition(
          "wf",
          "d",
          List.of(
              new Node("t", "API_TRIGGER", Map.of(), null, null),
              new Node("a", "P", Map.of("k", "v"), null, null),
              new Node("b", "P", Map.of(), null, null),
              new Node("end", "T", Map.of(), null, null),
              new Node("other", "P", Map.of(), null, null),
              new Node("end2", "T", Map.of(), null, null)),
          List.of(
              new Edge("t", "a", null),
              new Edge("a", "b", null),
              new Edge("b", "end", null),
              new Edge("a", "other", null),
              new Edge("other", "end2", null)),
          List.of(),
          Map.of("g", "teal"));

  private static WorkflowDefinition cut(final String until, final boolean stepOnly) {
    return TestRuns.cut(DRAFT, ID, until, stepOnly, "T"::equals);
  }

  private static List<String> ids(final WorkflowDefinition def) {
    return def.nodes().stream().map(Node::nodeId).toList();
  }

  @Test
  void aReservedIdIsRecognised() {
    assertThat(TestRuns.isTestRun(TestRuns.newWorkflowId())).isTrue();
    assertThat(TestRuns.isTestRun("wf")).isFalse();
    assertThat(TestRuns.isTestRun(null)).isFalse();
    assertThat(TestRuns.newWorkflowId()).isNotEqualTo(TestRuns.newWorkflowId());
  }

  @Test
  void withoutANodeTheWholeDraftRunsUnderTheNewId() {
    final WorkflowDefinition def = cut(null, false);

    assertThat(def.workflowId()).isEqualTo(ID);
    assertThat(def.nodes()).isEqualTo(DRAFT.nodes());
    assertThat(def.edges()).isEqualTo(DRAFT.edges());
    assertThat(def.supervisors()).isEmpty();
    assertThat(def.groupColors()).containsEntry("g", "teal");
  }

  @Test
  void stepOnlyNeedsANode() {
    assertThatThrownBy(() -> cut(null, true)).isInstanceOf(IllegalArgumentException.class);
  }

  @Test
  void anUnknownNodeIsRefused() {
    assertThatThrownBy(() -> cut("nope", false))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("nope");
  }

  @Test
  void aNodeRunsWithItsAncestorsAndASink() {
    final WorkflowDefinition def = cut("b", false);

    assertThat(ids(def)).containsExactly("t", "a", "b", TestRuns.SINK);
    assertThat(def.edges())
        .containsExactly(
            new Edge("t", "a", null), new Edge("a", "b", null), new Edge("b", TestRuns.SINK, "*"));
    assertThat(def.nodes().getLast().type()).isEqualTo("CONSOLE_TERMINAL");
  }

  @Test
  void aTerminalGetsNoSink() {
    final WorkflowDefinition def = cut("end", false);

    assertThat(ids(def)).containsExactly("t", "a", "b", "end");
    assertThat(def.edges()).hasSize(3);
  }

  @Test
  void aSharedAncestorIsKeptOnce() {
    final WorkflowDefinition diamond =
        new WorkflowDefinition(
            "wf",
            "d",
            List.of(
                new Node("t", "API_TRIGGER", Map.of(), null, null),
                new Node("l", "P", Map.of(), null, null),
                new Node("r", "P", Map.of(), null, null),
                new Node("j", "P", Map.of(), null, null)),
            List.of(
                new Edge("t", "l", null),
                new Edge("t", "r", null),
                new Edge("l", "j", null),
                new Edge("r", "j", null)),
            List.of(),
            null);

    assertThat(ids(TestRuns.cut(diamond, ID, "j", false, "T"::equals)))
        .containsExactlyInAnyOrder("t", "l", "r", "j", TestRuns.SINK);
  }

  @Test
  void aStepRunsAloneBehindATriggerThatEmitsTheSample() {
    final WorkflowDefinition def = cut("a", true);

    assertThat(ids(def)).containsExactly(TestRuns.TRIGGER, "a", TestRuns.SINK);
    assertThat(def.nodes().getFirst().type()).isEqualTo("API_TRIGGER");
    assertThat(def.nodes().get(1).config()).containsEntry("k", "v");
    assertThat(def.edges())
        .containsExactly(new Edge(TestRuns.TRIGGER, "a", "*"), new Edge("a", TestRuns.SINK, "*"));
  }

  @Test
  void aTerminalStepGetsNoSink() {
    assertThat(ids(cut("end", true))).containsExactly(TestRuns.TRIGGER, "end");
  }
}
