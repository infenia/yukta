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

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Unit tests for {@link WorkflowEdge}. */
class WorkflowEdgeTest {

  @Test
  void testConstructorAndGetters() {
    // Test with null sourcePort
    WorkflowEdge edge1 = new WorkflowEdge("source1", "target1", null);
    assertEquals("source1", edge1.source());
    assertEquals("target1", edge1.target());
    assertNull(edge1.sourcePort());

    // Test with non-null sourcePort
    WorkflowEdge edge2 = new WorkflowEdge("source2", "target2", "port1");
    assertEquals("source2", edge2.source());
    assertEquals("target2", edge2.target());
    assertEquals("port1", edge2.sourcePort());
  }

  @Test
  void testEqualsAndHashCode() {
    WorkflowEdge edge1 = new WorkflowEdge("source", "target", null);
    WorkflowEdge edge2 = new WorkflowEdge("source", "target", null);
    WorkflowEdge edge3 = new WorkflowEdge("source", "target", "port");
    WorkflowEdge edge4 = new WorkflowEdge("different", "target", null);

    // Same values should be equal
    assertEquals(edge1, edge2);
    assertEquals(edge1.hashCode(), edge2.hashCode());

    // Different sourcePort should not be equal
    assertNotEquals(edge1, edge3);
    assertNotEquals(edge1.hashCode(), edge3.hashCode());

    // Different source should not be equal
    assertNotEquals(edge1, edge4);
    assertNotEquals(edge1.hashCode(), edge4.hashCode());

    // Reflexive
    assertEquals(edge1, edge1);
    assertEquals(edge1.hashCode(), edge1.hashCode());
  }

  @Test
  void testToString() {
    WorkflowEdge edgeWithNullPort = new WorkflowEdge("source", "target", null);
    WorkflowEdge edgeWithPort = new WorkflowEdge("source", "target", "port");

    String toStringWithNull = edgeWithNullPort.toString();
    String toStringWithPort = edgeWithPort.toString();

    // toString should contain all field values
    assertEquals("WorkflowEdge[source=source, target=target, sourcePort=null]", toStringWithNull);
    assertEquals("WorkflowEdge[source=source, target=target, sourcePort=port]", toStringWithPort);
  }
}
