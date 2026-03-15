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
package com.infenia.yukta.model.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowDefinitionTest {

  @Test
  void testWorkflowDefinition() {
    Map<String, Object> config = Map.of("k", "v");
    WorkflowDefinition.Node node = new WorkflowDefinition.Node("n", "t", config);
    assertEquals("n", node.nodeId());
    assertEquals("t", node.type());
    assertEquals(config, node.config());

    WorkflowDefinition.Edge edge = new WorkflowDefinition.Edge("s", "t", "p");
    assertEquals("s", edge.source());
    assertEquals("t", edge.target());
    assertEquals("p", edge.sourcePort());

    WorkflowDefinition.Edge edge2 = new WorkflowDefinition.Edge("s", "t");
    assertNull(edge2.sourcePort());

    WorkflowDefinition def = new WorkflowDefinition("d", List.of(node), null);
    assertEquals("d", def.description());
    assertEquals(1, def.nodes().size());
    assertNotNull(def.edges());
    assertTrue(def.edges().isEmpty());

    WorkflowDefinition defNullNodes = new WorkflowDefinition("d", null, List.of(edge));
    assertNotNull(defNullNodes.nodes());
    assertTrue(defNullNodes.nodes().isEmpty());
    assertEquals(1, defNullNodes.edges().size());
  }

  @Test
  void testWorkflowDefinitionImmutability() {
    WorkflowDefinition.Node node = new WorkflowDefinition.Node("n1", "t1", null);
    WorkflowDefinition.Edge edge = new WorkflowDefinition.Edge("n1", "n2");
    WorkflowDefinition def = new WorkflowDefinition("desc", List.of(node), List.of(edge));

    assertNotNull(def.nodes());
    assertNotNull(def.edges());
    assertEquals(1, def.nodes().size());
  }
}
