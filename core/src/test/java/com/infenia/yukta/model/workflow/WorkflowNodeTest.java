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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link WorkflowNode}. */
class WorkflowNodeTest {

  @Test
  void testConstructorAndGetters() {
    // Test with null config
    WorkflowNode node1 = new WorkflowNode("node1", "type1", null);
    assertEquals("node1", node1.nodeId());
    assertEquals("type1", node1.type());
    assertTrue(node1.config().isEmpty());

    // Test with empty config
    WorkflowNode node2 = new WorkflowNode("node2", "type2", Map.of());
    assertEquals("node2", node2.nodeId());
    assertEquals("type2", node2.type());
    assertTrue(node2.config().isEmpty());

    // Test with populated config
    Map<String, Object> config = new HashMap<>();
    config.put("key1", "value1");
    config.put("key2", 42);
    WorkflowNode node3 = new WorkflowNode("node3", "type3", config);
    assertEquals("node3", node3.nodeId());
    assertEquals("type3", node3.type());
    assertEquals(2, node3.config().size());
    assertEquals("value1", node3.config().get("key1"));
    assertEquals(42, node3.config().get("key2"));

    // Verify defensive copying - original map should not be affected
    config.put("key3", "value3");
    assertEquals(2, node3.config().size()); // Should still be 2, not 3
  }

  @Test
  void testEqualsAndHashCode() {
    Map<String, Object> config1 = Map.of("key1", "value1");
    Map<String, Object> config2 = Map.of("key1", "value1");
    Map<String, Object> config3 = Map.of("key1", "value2");

    WorkflowNode node1 = new WorkflowNode("node", "type", config1);
    WorkflowNode node2 = new WorkflowNode("node", "type", config2);
    WorkflowNode node3 = new WorkflowNode("node", "type", config3);
    WorkflowNode node4 = new WorkflowNode("node", "different", config1);
    WorkflowNode node5 = new WorkflowNode("different", "type", config1);

    // Same values should be equal
    assertEquals(node1, node2);
    assertEquals(node1.hashCode(), node2.hashCode());

    // Different config should not be equal
    assertNotEquals(node1, node3);
    assertNotEquals(node1.hashCode(), node3.hashCode());

    // Different type should not be equal
    assertNotEquals(node1, node4);
    assertNotEquals(node1.hashCode(), node4.hashCode());

    // Different nodeId should not be equal
    assertNotEquals(node1, node5);
    assertNotEquals(node1.hashCode(), node5.hashCode());

    // Reflexive
    assertEquals(node1, node1);
    assertEquals(node1.hashCode(), node1.hashCode());
  }

  @Test
  void testToString() {
    Map<String, Object> config = Map.of("key1", "value1", "key2", 42);
    WorkflowNode nodeWithConfig = new WorkflowNode("node", "type", config);
    WorkflowNode nodeWithoutConfig = new WorkflowNode("node", "type", null);

    String toStringWithConfig = nodeWithConfig.toString();
    String toStringWithoutConfig = nodeWithoutConfig.toString();

    // toString should contain all field values
    assertTrue(toStringWithConfig.contains("nodeId=node"));
    assertTrue(toStringWithConfig.contains("type=type"));
    assertTrue(toStringWithConfig.contains("key1=value1"));
    assertTrue(toStringWithConfig.contains("key2=42"));

    assertTrue(toStringWithoutConfig.contains("nodeId=node"));
    assertTrue(toStringWithoutConfig.contains("type=type"));
    assertTrue(toStringWithoutConfig.contains("config=")); // Should show empty config
  }
}
