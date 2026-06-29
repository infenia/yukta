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

import java.util.HashMap;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Unit tests for {@link WorkflowNode}. */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UseConcurrentHashMap"})
@NoArgsConstructor
class WorkflowNodeTest {

  @Test
  void testConstructorAndGetters() {
    // Test with null config
    final WorkflowNode node1 = new WorkflowNode("node1", "type1", null);
    assertThat(node1.nodeId()).isEqualTo("node1");
    assertThat(node1.type()).isEqualTo("type1");
    assertThat(node1.config()).isEmpty();

    // Test with empty config
    final WorkflowNode node2 = new WorkflowNode("node2", "type2", Map.of());
    assertThat(node2.nodeId()).isEqualTo("node2");
    assertThat(node2.type()).isEqualTo("type2");
    assertThat(node2.config()).isEmpty();

    // Test with populated config
    final Map<String, Object> config = new HashMap<>();
    config.put("key1", "value1");
    config.put("key2", 42);
    final WorkflowNode node3 = new WorkflowNode("node3", "type3", config);
    assertThat(node3.nodeId()).isEqualTo("node3");
    assertThat(node3.type()).isEqualTo("type3");
    assertThat(node3.config()).hasSize(2);
    assertThat(node3.config().get("key1")).isEqualTo("value1");
    assertThat(node3.config().get("key2")).isEqualTo(42);

    // Verify defensive copying - original map should not be affected
    config.put("key3", "value3");
    assertThat(node3.config()).hasSize(2);
  }

  @Test
  void testEqualsAndHashCode() {
    final Map<String, Object> config1 = Map.of("key1", "value1");
    final Map<String, Object> config2 = Map.of("key1", "value1");
    final Map<String, Object> config3 = Map.of("key1", "value2");

    final WorkflowNode node1 = new WorkflowNode("node", "type", config1);
    final WorkflowNode node2 = new WorkflowNode("node", "type", config2);
    final WorkflowNode node3 = new WorkflowNode("node", "type", config3);
    final WorkflowNode node4 = new WorkflowNode("node", "different", config1);
    final WorkflowNode node5 = new WorkflowNode("different", "type", config1);

    // Same values should be equal
    assertThat(node1).isEqualTo(node2);
    assertThat(node1.hashCode()).isEqualTo(node2.hashCode());

    // Different config should not be equal
    assertThat(node1).isNotEqualTo(node3);
    assertThat(node1.hashCode()).isNotEqualTo(node3.hashCode());

    // Different type should not be equal
    assertThat(node1).isNotEqualTo(node4);
    assertThat(node1.hashCode()).isNotEqualTo(node4.hashCode());

    // Different nodeId should not be equal
    assertThat(node1).isNotEqualTo(node5);
    assertThat(node1.hashCode()).isNotEqualTo(node5.hashCode());
  }

  @Test
  void testToString() {
    final Map<String, Object> config = Map.of("key1", "value1", "key2", 42);
    final WorkflowNode nodeWithConfig = new WorkflowNode("node", "type", config);
    final WorkflowNode nodeWithoutConfig = new WorkflowNode("node", "type", null);

    final String toStringWithConfig = nodeWithConfig.toString();
    final String toStringWithoutConfig = nodeWithoutConfig.toString();

    // toString should contain all field values
    assertThat(toStringWithConfig).contains("nodeId=node");
    assertThat(toStringWithConfig).contains("type=type");
    assertThat(toStringWithConfig).contains("key1=value1");
    assertThat(toStringWithConfig).contains("key2=42");

    assertThat(toStringWithoutConfig).contains("nodeId=node");
    assertThat(toStringWithoutConfig).contains("type=type");
    assertThat(toStringWithoutConfig).contains("config=");
  }
}
