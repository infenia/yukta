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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.infenia.yukta.model.workflow.WorkflowNode;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoSettings;

@MockitoSettings
class TopologicalSortServiceTest {

  private final TopologicalSortService service = new TopologicalSortService();

  @Test
  void testComputeTopologicalOrder() {
    WorkflowNode n1 = new WorkflowNode("n1", "t", null);
    WorkflowNode n2 = new WorkflowNode("n2", "t", null);
    Map<String, List<WorkflowNode>> adj = Map.of("n1", List.of(n2), "n2", List.of());
    Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of(), "n2", List.of(n1));

    List<WorkflowNode> order = service.computeTopologicalOrder(List.of(n1, n2), adj, parents);
    assertEquals(2, order.size());
    assertEquals("n1", order.get(0).nodeId());
    assertEquals("n2", order.get(1).nodeId());
  }

  @Test
  void testComputeTopologicalOrderWithMissingAdjacencyKey() {
    WorkflowNode n1 = new WorkflowNode("n1", "t", null);
    WorkflowNode n2 = new WorkflowNode("n2", "t", null);
    Map<String, List<WorkflowNode>> adjNull = new HashMap<>();
    adjNull.put("n1", List.of(n2));
    Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of(), "n2", List.of(n1));

    List<WorkflowNode> order = service.computeTopologicalOrder(List.of(n1, n2), adjNull, parents);
    assertEquals(2, order.size());
    assertEquals("n1", order.get(0).nodeId());
    assertEquals("n2", order.get(1).nodeId());
  }

  @Test
  void testComputeTopologicalOrderWithCycle() {
    WorkflowNode n1 = new WorkflowNode("n1", "t", null);
    WorkflowNode n2 = new WorkflowNode("n2", "t", null);
    Map<String, List<WorkflowNode>> adj = Map.of("n1", List.of(n2), "n2", List.of(n1));
    Map<String, List<WorkflowNode>> parents = Map.of("n1", List.of(n2), "n2", List.of(n1));

    assertThrows(
        IllegalArgumentException.class,
        () -> service.computeTopologicalOrder(List.of(n1, n2), adj, parents));
  }

  @Test
  void testComputeTopologicalOrderMultipleNodes() {
    WorkflowNode n1 = new WorkflowNode("n1", "t", null);
    WorkflowNode n2 = new WorkflowNode("n2", "t", null);
    WorkflowNode n3 = new WorkflowNode("n3", "t", null);
    Map<String, List<WorkflowNode>> adj =
        Map.of("n1", List.of(n2), "n2", List.of(n3), "n3", List.of());
    Map<String, List<WorkflowNode>> parents =
        Map.of("n1", List.of(), "n2", List.of(n1), "n3", List.of(n2));

    List<WorkflowNode> order = service.computeTopologicalOrder(List.of(n1, n2, n3), adj, parents);
    assertEquals(3, order.size());
    assertEquals("n1", order.get(0).nodeId());
    assertEquals("n2", order.get(1).nodeId());
    assertEquals("n3", order.get(2).nodeId());
  }

  @Test
  void testComputeTopologicalOrderWithDiamondDependency() {
    WorkflowNode n1 = new WorkflowNode("n1", "t", null);
    WorkflowNode n2 = new WorkflowNode("n2", "t", null);
    WorkflowNode n3 = new WorkflowNode("n3", "t", null);
    WorkflowNode n4 = new WorkflowNode("n4", "t", null);
    Map<String, List<WorkflowNode>> adj =
        Map.of("n1", List.of(n2, n3), "n2", List.of(n4), "n3", List.of(n4), "n4", List.of());
    Map<String, List<WorkflowNode>> parents =
        Map.of("n1", List.of(), "n2", List.of(n1), "n3", List.of(n1), "n4", List.of(n2, n3));

    List<WorkflowNode> order =
        service.computeTopologicalOrder(List.of(n1, n2, n3, n4), adj, parents);
    assertEquals(4, order.size());
    assertEquals("n1", order.get(0).nodeId());
    assertEquals("n4", order.get(3).nodeId());
  }
}
