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
package com.infenia.yukta.plugin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import org.junit.jupiter.api.Test;

class WorkflowContextTest {

  @Test
  void testWorkflowContextAndEdge() {
    WorkflowContext.WorkflowEdge edge = new WorkflowContext.WorkflowEdge("s1", "t1", "p1");
    assertEquals("s1", edge.source());
    assertEquals("t1", edge.target());
    assertEquals("p1", edge.sourcePort());

    WorkflowContext context = new WorkflowContext("node1", List.of(edge), List.of());
    assertEquals("node1", context.nodeId());
    assertEquals(1, context.outgoingEdges().size());
    assertTrue(context.incomingEdges().isEmpty());

    // Null branch coverage
    WorkflowContext context2 = new WorkflowContext("node2", List.of(), List.of());
    assertNotNull(context2.outgoingEdges());
    assertNotNull(context2.incomingEdges());
  }
}
