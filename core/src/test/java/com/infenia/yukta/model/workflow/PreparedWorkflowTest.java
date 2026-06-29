/*
 * Copyright 2026 Infenia Private Limited
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.infenia.yukta.model.workflow;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;

/** Tests for {@link PreparedWorkflow}. */
@NoArgsConstructor
class PreparedWorkflowTest {

  @Test
  void testPreparedWorkflowConstructor() {
    final WorkflowNode node1 = new WorkflowNode("n1", "t", null);
    final WorkflowNode node2 = new WorkflowNode("n2", "t", null);

    final List<WorkflowEdge> edges = List.of(new WorkflowEdge("n1", "n2", null));
    final Map<String, List<WorkflowNode>> adj = Map.of("n1", List.of(node2), "n2", List.of());
    final Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of(), "n2", List.of(node1));
    final List<WorkflowNode> order = List.of(node1, node2);

    final PreparedWorkflow prepared =
        new PreparedWorkflow(edges, adj, parents, Map.of(), order, (id, p) -> Mono.empty());
    assertThat(prepared.edges()).isEqualTo(edges);
    assertThat(prepared.topologicalOrder()).hasSize(2);
    assertThat(prepared.topologicalOrder().get(0).nodeId()).isEqualTo("n1");
    assertThat(prepared.topologicalOrder().get(1).nodeId()).isEqualTo("n2");
  }
}
