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
package com.infenia.yukta.service.orchestrator.preparator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.model.workflow.WorkflowNode;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link TopologicalSortService}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.ShortVariable", "PMD.TooManyMethods"})
class TopologicalSortServiceTest {

  /** Instance under test. */
  private TopologicalSortService sortService;

  @BeforeEach
  void setUp() {
    sortService = new TopologicalSortService();
  }

  @Test
  void computeTopologicalOrder_linearChain_returnsCorrectOrder() {
    // Given
    final WorkflowNode n1 = new WorkflowNode("n1", "t", Map.of());
    final WorkflowNode n2 = new WorkflowNode("n2", "t", Map.of());

    final List<WorkflowNode> nodes = List.of(n1, n2);
    final Map<String, List<WorkflowNode>> adj = Map.of("n1", List.of(n2), "n2", List.of());
    final Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of(), "n2", List.of(n1));

    // When
    final List<WorkflowNode> actualOrder = sortService.computeTopologicalOrder(nodes, adj, parents);

    // Then
    assertThat(actualOrder).hasSize(2);
    assertThat(actualOrder.get(0).nodeId()).isEqualTo("n1");
    assertThat(actualOrder.get(1).nodeId()).isEqualTo("n2");
  }

  @Test
  void computeTopologicalOrder_cycleInGraph_throwsIllegalArgumentException() {
    // Given
    final WorkflowNode n1 = new WorkflowNode("n1", "t", Map.of());
    final WorkflowNode n2 = new WorkflowNode("n2", "t", Map.of());

    final List<WorkflowNode> nodes = List.of(n1, n2);
    final Map<String, List<WorkflowNode>> adj = Map.of("n1", List.of(n2), "n2", List.of(n1));
    final Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of(n2), "n2", List.of(n1));

    // When-Then
    assertThatThrownBy(() -> sortService.computeTopologicalOrder(nodes, adj, parents))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Workflow DAG contains cycles or is invalid");
  }

  @Test
  void computeTopologicalOrder_branchedDag_returnsCorrectOrder() {
    // Given
    final WorkflowNode n1 = new WorkflowNode("n1", "t", Map.of());
    final WorkflowNode n2 = new WorkflowNode("n2", "t", Map.of());
    final WorkflowNode n3 = new WorkflowNode("n3", "t", Map.of());

    final List<WorkflowNode> nodes = List.of(n1, n2, n3);
    final Map<String, List<WorkflowNode>> adj =
        Map.of("n1", List.of(n2, n3), "n2", List.of(), "n3", List.of());
    final Map<String, List<WorkflowNode>> parents =
        Map.of("n1", List.of(), "n2", List.of(n1), "n3", List.of(n1));

    // When
    final List<WorkflowNode> actualOrder = sortService.computeTopologicalOrder(nodes, adj, parents);

    // Then
    assertThat(actualOrder).hasSize(3);
    assertThat(actualOrder.get(0).nodeId()).isEqualTo("n1");
    assertThat(actualOrder)
        .extracting(WorkflowNode::nodeId)
        .containsExactlyInAnyOrder("n1", "n2", "n3");
  }

  @Test
  void computeTopologicalOrder_singleNode_returnsNodeInOrder() {
    // Given
    final WorkflowNode n1 = new WorkflowNode("n1", "t", Map.of());

    final List<WorkflowNode> nodes = List.of(n1);
    final Map<String, List<WorkflowNode>> adj = Map.of("n1", List.of());
    final Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of());

    // When
    final List<WorkflowNode> actualOrder = sortService.computeTopologicalOrder(nodes, adj, parents);

    // Then
    assertThat(actualOrder).hasSize(1);
    assertThat(actualOrder.get(0).nodeId()).isEqualTo("n1");
  }

  @Test
  void computeTopologicalOrder_emptyNodeList_returnsEmptyList() {
    // Given
    final List<WorkflowNode> nodes = List.of();
    final Map<String, List<WorkflowNode>> adj = Map.of();
    final Map<String, List<WorkflowNode>> parents = Map.of();

    // When
    final List<WorkflowNode> actualOrder = sortService.computeTopologicalOrder(nodes, adj, parents);

    // Then
    assertThat(actualOrder).isEmpty();
  }

  @Test
  void computeTopologicalOrder_multipleRoots_returnsAllNodes() {
    // Given
    final WorkflowNode n1 = new WorkflowNode("n1", "t", Map.of());
    final WorkflowNode n2 = new WorkflowNode("n2", "t", Map.of());
    final WorkflowNode n3 = new WorkflowNode("n3", "t", Map.of());

    final List<WorkflowNode> nodes = List.of(n1, n2, n3);
    final Map<String, List<WorkflowNode>> adj =
        Map.of("n1", List.of(), "n2", List.of(), "n3", List.of());
    final Map<String, List<WorkflowNode>> parents =
        Map.of("n1", List.of(), "n2", List.of(), "n3", List.of());

    // When
    final List<WorkflowNode> actualOrder = sortService.computeTopologicalOrder(nodes, adj, parents);

    // Then
    assertThat(actualOrder)
        .hasSize(3)
        .extracting(WorkflowNode::nodeId)
        .containsExactlyInAnyOrder("n1", "n2", "n3");
  }

  @Test
  void computeTopologicalOrder_mergedDag_respectsDependencyOrder() {
    // Given - Diamond DAG: n1 and n2 both point to n3, n3 points to n4
    final WorkflowNode n1 = new WorkflowNode("n1", "t", Map.of());
    final WorkflowNode n2 = new WorkflowNode("n2", "t", Map.of());
    final WorkflowNode n3 = new WorkflowNode("n3", "t", Map.of());
    final WorkflowNode n4 = new WorkflowNode("n4", "t", Map.of());

    final List<WorkflowNode> nodes = List.of(n1, n2, n3, n4);
    final Map<String, List<WorkflowNode>> adj =
        Map.of(
            "n1", List.of(n3),
            "n2", List.of(n3),
            "n3", List.of(n4),
            "n4", List.of());
    final Map<String, List<WorkflowNode>> parents =
        Map.of(
            "n1", List.of(),
            "n2", List.of(),
            "n3", List.of(n1, n2),
            "n4", List.of(n3));

    // When
    final List<WorkflowNode> actualOrder = sortService.computeTopologicalOrder(nodes, adj, parents);

    // Then
    final List<String> nodeIds = actualOrder.stream().map(WorkflowNode::nodeId).toList();
    assertThat(nodeIds).hasSize(4);
    assertThat(nodeIds.indexOf("n1")).isLessThan(nodeIds.indexOf("n3"));
    assertThat(nodeIds.indexOf("n2")).isLessThan(nodeIds.indexOf("n3"));
    assertThat(nodeIds.indexOf("n3")).isLessThan(nodeIds.indexOf("n4"));
  }

  @Test
  void computeTopologicalOrder_complexDagWithMultipleBranches_returnsValidOrder() {
    // Given - Complex DAG with multiple branches and merges
    final WorkflowNode n1 = new WorkflowNode("n1", "t", Map.of());
    final WorkflowNode n2 = new WorkflowNode("n2", "t", Map.of());
    final WorkflowNode n3 = new WorkflowNode("n3", "t", Map.of());
    final WorkflowNode n4 = new WorkflowNode("n4", "t", Map.of());
    final WorkflowNode n5 = new WorkflowNode("n5", "t", Map.of());

    final List<WorkflowNode> nodes = List.of(n1, n2, n3, n4, n5);
    final Map<String, List<WorkflowNode>> adj =
        Map.of(
            "n1", List.of(n2, n3),
            "n2", List.of(n4),
            "n3", List.of(n4),
            "n4", List.of(n5),
            "n5", List.of());
    final Map<String, List<WorkflowNode>> parents =
        Map.of(
            "n1", List.of(),
            "n2", List.of(n1),
            "n3", List.of(n1),
            "n4", List.of(n2, n3),
            "n5", List.of(n4));

    // When
    final List<WorkflowNode> actualOrder = sortService.computeTopologicalOrder(nodes, adj, parents);

    // Then - Verify all nodes are present and dependencies are respected
    final List<String> nodeIds = actualOrder.stream().map(WorkflowNode::nodeId).toList();
    assertThat(nodeIds).hasSize(5).containsExactlyInAnyOrder("n1", "n2", "n3", "n4", "n5");
    assertThat(nodeIds.indexOf("n1")).isLessThan(nodeIds.indexOf("n2"));
    assertThat(nodeIds.indexOf("n1")).isLessThan(nodeIds.indexOf("n3"));
    assertThat(nodeIds.indexOf("n2")).isLessThan(nodeIds.indexOf("n4"));
    assertThat(nodeIds.indexOf("n3")).isLessThan(nodeIds.indexOf("n4"));
    assertThat(nodeIds.indexOf("n4")).isLessThan(nodeIds.indexOf("n5"));
  }

  @Test
  void computeTopologicalOrder_selfCycle_throwsIllegalArgumentException() {
    // Given
    final WorkflowNode n1 = new WorkflowNode("n1", "t", Map.of());

    final List<WorkflowNode> nodes = List.of(n1);
    final Map<String, List<WorkflowNode>> adj = Map.of("n1", List.of(n1));
    final Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of(n1));

    // When-Then
    assertThatThrownBy(() -> sortService.computeTopologicalOrder(nodes, adj, parents))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Workflow DAG contains cycles or is invalid");
  }

  @Test
  void computeTopologicalOrder_cycleInSubgraph_throwsIllegalArgumentException() {
    // Given - Acyclic root, but cycle in subgraph: n1 -> n2 -> n3 -> n2 (cycle)
    final WorkflowNode n1 = new WorkflowNode("n1", "t", Map.of());
    final WorkflowNode n2 = new WorkflowNode("n2", "t", Map.of());
    final WorkflowNode n3 = new WorkflowNode("n3", "t", Map.of());

    final List<WorkflowNode> nodes = List.of(n1, n2, n3);
    final Map<String, List<WorkflowNode>> adj =
        Map.of("n1", List.of(n2), "n2", List.of(n3), "n3", List.of(n2));
    final Map<String, List<WorkflowNode>> parents =
        Map.of("n1", List.of(), "n2", List.of(n1, n3), "n3", List.of(n2));

    // When-Then
    assertThatThrownBy(() -> sortService.computeTopologicalOrder(nodes, adj, parents))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("Workflow DAG contains cycles or is invalid");
  }

  @Test
  void computeTopologicalOrder_nullChildrenListHandling_processesSuccessfully() {
    // Given - Leaf nodes have null children (no entry in adjacency map)
    final WorkflowNode n1 = new WorkflowNode("n1", "t", Map.of());
    final WorkflowNode n2 = new WorkflowNode("n2", "t", Map.of());
    final WorkflowNode n3 = new WorkflowNode("n3", "t", Map.of());

    final List<WorkflowNode> nodes = List.of(n1, n2, n3);
    // Missing entries for n2 and n3 means null children
    @SuppressWarnings("PMD.UseConcurrentHashMap")
    final Map<String, List<WorkflowNode>> adj = new java.util.HashMap<>();
    adj.put("n1", List.of(n2, n3));

    final Map<String, List<WorkflowNode>> parents =
        Map.of("n1", List.of(), "n2", List.of(n1), "n3", List.of(n1));

    // When
    final List<WorkflowNode> actualOrder = sortService.computeTopologicalOrder(nodes, adj, parents);

    // Then - Should handle null children gracefully
    assertThat(actualOrder).hasSize(3);
    assertThat(actualOrder.get(0).nodeId()).isEqualTo("n1");
    assertThat(actualOrder)
        .extracting(WorkflowNode::nodeId)
        .containsExactlyInAnyOrder("n1", "n2", "n3");
  }

  @Test
  void computeTopologicalOrder_disconnectedComponents_returnsAllNodes() {
    // Given - Two independent components: n1->n2 and n3->n4
    final WorkflowNode n1 = new WorkflowNode("n1", "t", Map.of());
    final WorkflowNode n2 = new WorkflowNode("n2", "t", Map.of());
    final WorkflowNode n3 = new WorkflowNode("n3", "t", Map.of());
    final WorkflowNode n4 = new WorkflowNode("n4", "t", Map.of());

    final List<WorkflowNode> nodes = List.of(n1, n2, n3, n4);
    final Map<String, List<WorkflowNode>> adj =
        Map.of(
            "n1", List.of(n2),
            "n2", List.of(),
            "n3", List.of(n4),
            "n4", List.of());
    final Map<String, List<WorkflowNode>> parents =
        Map.of(
            "n1", List.of(),
            "n2", List.of(n1),
            "n3", List.of(),
            "n4", List.of(n3));

    // When
    final List<WorkflowNode> actualOrder = sortService.computeTopologicalOrder(nodes, adj, parents);

    // Then
    final List<String> nodeIds = actualOrder.stream().map(WorkflowNode::nodeId).toList();
    assertThat(nodeIds).hasSize(4).containsExactlyInAnyOrder("n1", "n2", "n3", "n4");
    assertThat(nodeIds.indexOf("n1")).isLessThan(nodeIds.indexOf("n2"));
    assertThat(nodeIds.indexOf("n3")).isLessThan(nodeIds.indexOf("n4"));
  }

  @Test
  void computeTopologicalOrder_linearChainOfFiveNodes_returnsCorrectSequence() {
    // Given - Linear chain: n1 -> n2 -> n3 -> n4 -> n5
    final WorkflowNode n1 = new WorkflowNode("n1", "t", Map.of());
    final WorkflowNode n2 = new WorkflowNode("n2", "t", Map.of());
    final WorkflowNode n3 = new WorkflowNode("n3", "t", Map.of());
    final WorkflowNode n4 = new WorkflowNode("n4", "t", Map.of());
    final WorkflowNode n5 = new WorkflowNode("n5", "t", Map.of());

    final List<WorkflowNode> nodes = List.of(n1, n2, n3, n4, n5);
    final Map<String, List<WorkflowNode>> adj =
        Map.of(
            "n1", List.of(n2),
            "n2", List.of(n3),
            "n3", List.of(n4),
            "n4", List.of(n5),
            "n5", List.of());
    final Map<String, List<WorkflowNode>> parents =
        Map.of(
            "n1", List.of(),
            "n2", List.of(n1),
            "n3", List.of(n2),
            "n4", List.of(n3),
            "n5", List.of(n4));

    // When
    final List<WorkflowNode> actualOrder = sortService.computeTopologicalOrder(nodes, adj, parents);

    // Then
    assertThat(actualOrder)
        .extracting(WorkflowNode::nodeId)
        .containsExactly("n1", "n2", "n3", "n4", "n5");
  }
}
