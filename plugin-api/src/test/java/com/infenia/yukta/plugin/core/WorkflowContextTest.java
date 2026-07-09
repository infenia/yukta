// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
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
