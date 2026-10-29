// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.archive;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import com.infenia.yukta.model.workflow.PreparedWorkflow;
import com.infenia.yukta.model.workflow.WorkflowEdge;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.model.workflow.WorkflowTemplate;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@NoArgsConstructor
@SuppressWarnings("PMD.UseConcurrentHashMap")
class DefinitionVersionTest {

  private static final WorkflowEdge EDGE = new WorkflowEdge("a", "b", null);

  private static PreparedWorkflow prepared(
      final List<WorkflowNode> nodes, final List<WorkflowEdge> edges) {
    return new PreparedWorkflow(
        edges, Map.of(), Map.of(), Map.of(), nodes, mock(WorkflowTemplate.class));
  }

  @Test
  void sameContent_givesTheSameShortHexVersion_regardlessOfOrderings() {
    final Map<String, Object> first = new LinkedHashMap<>();
    first.put("x", 1);
    first.put("y", List.of(Map.of("k", "v"), "z"));
    final Map<String, Object> second = new LinkedHashMap<>();
    second.put("y", List.of(Map.of("k", "v"), "z"));
    second.put("x", 1);

    final String versionA =
        DefinitionVersion.of(
            prepared(
                List.of(new WorkflowNode("a", "t", first), new WorkflowNode("b", "t", Map.of())),
                List.of(EDGE)));
    final String versionB =
        DefinitionVersion.of(
            prepared(
                List.of(new WorkflowNode("b", "t", Map.of()), new WorkflowNode("a", "t", second)),
                List.of(EDGE)));

    assertThat(versionA).hasSize(16).matches("[0-9a-f]+").isEqualTo(versionB);
  }

  @Test
  void anyChangeToConfigTypeOrEdges_changesTheVersion() {
    final List<WorkflowNode> base =
        List.of(new WorkflowNode("a", "t", Map.of("c", 1)), new WorkflowNode("b", "t", Map.of()));
    final String baseVersion = DefinitionVersion.of(prepared(base, List.of(EDGE)));

    final String config =
        DefinitionVersion.of(
            prepared(
                List.of(
                    new WorkflowNode("a", "t", Map.of("c", 2)),
                    new WorkflowNode("b", "t", Map.of())),
                List.of(EDGE)));
    final String type =
        DefinitionVersion.of(
            prepared(
                List.of(
                    new WorkflowNode("a", "other", Map.of("c", 1)),
                    new WorkflowNode("b", "t", Map.of())),
                List.of(EDGE)));
    final String port =
        DefinitionVersion.of(prepared(base, List.of(new WorkflowEdge("a", "b", "error"))));
    final String noEdge = DefinitionVersion.of(prepared(base, List.of()));

    assertThat(List.of(config, type, port, noEdge))
        .doesNotContain(baseVersion)
        .doesNotHaveDuplicates();
  }

  @Test
  void parallelEdgesWithDifferentPorts_areOrderedByPort() {
    final List<WorkflowNode> nodes =
        List.of(new WorkflowNode("a", "t", Map.of()), new WorkflowNode("b", "t", Map.of()));
    final WorkflowEdge success = new WorkflowEdge("a", "b", "ok");
    final WorkflowEdge error = new WorkflowEdge("a", "b", "error");

    assertThat(DefinitionVersion.of(prepared(nodes, List.of(success, error))))
        .isEqualTo(DefinitionVersion.of(prepared(nodes, List.of(error, success))));
  }
}
