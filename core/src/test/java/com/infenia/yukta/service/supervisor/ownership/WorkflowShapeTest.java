// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.ownership;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.testing.Fixtures;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** The group of each node of a workflow, and the nodes each kind of selector takes. */
@NoArgsConstructor
class WorkflowShapeTest {

  private static final String BUILD_COMPILE = "build.compile";
  private static final String BUILD_TEST = "build.test";
  private static final String DEPLOY_PROD = "deploy.prod";
  private static final String GROUP_CI = "ci";

  private static WorkflowShape shapeOf(final WorkflowDefinition.Node... nodes) {
    return WorkflowShape.from(Fixtures.workflow("w", "d", List.of(nodes), List.of()));
  }

  @Test
  void declaredGroupWinsOverTheIdPrefix() {
    final WorkflowShape shape =
        shapeOf(
            Fixtures.node(BUILD_TEST, "T", Map.of(), GROUP_CI),
            Fixtures.node(BUILD_COMPILE, "T", Map.of()));

    assertThat(shape.groupByNode())
        .containsEntry(BUILD_TEST, GROUP_CI)
        .containsEntry(BUILD_COMPILE, "build");
  }

  @Test
  void idWithoutDotAndWithoutGroupHasNoGroup() {
    final WorkflowShape shape =
        shapeOf(Fixtures.node("lone", "T", Map.of()), Fixtures.node(".hidden", "T", Map.of()));

    assertThat(shape.groupByNode()).containsEntry("lone", null).containsEntry(".hidden", null);
  }

  @Test
  void selectorsTakeIdPrefixOrGroup() {
    final WorkflowShape shape =
        shapeOf(
            Fixtures.node(BUILD_COMPILE, "T", Map.of()),
            Fixtures.node(BUILD_TEST, "T", Map.of(), GROUP_CI),
            Fixtures.node(DEPLOY_PROD, "T", Map.of()));

    assertThat(shape.take(BUILD_TEST)).containsExactly(BUILD_TEST);
    assertThat(shape.take("build.*")).containsExactly(BUILD_COMPILE, BUILD_TEST);
    assertThat(shape.take("group:ci")).containsExactly(BUILD_TEST);
    assertThat(shape.take("group:build")).containsExactly(BUILD_COMPILE);
    assertThat(shape.take("group:none")).isEmpty();
    assertThat(shape.take("missing")).isEmpty();
  }

  @Test
  void theShapeIsAnUnmodifiableCopy() {
    final Map<String, String> groups = Map.of("a.x", "a");
    final WorkflowShape shape = new WorkflowShape(groups);

    assertThat(shape.groupByNode()).containsOnlyKeys("a.x");
    assertThatThrownBy(() -> shape.groupByNode().put("b.y", "b"))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
