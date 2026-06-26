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
package com.infenia.yukta.model.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ControlConfigurationTest {

  @Test
  void compactConstructor_validMapProvided_createsInstanceWithCopiedConfig() {
    // Given
    String nodeId = "node-1";
    Map<String, Object> config = Map.of("key1", "value1", "key2", 42);

    // When
    ControlConfiguration controlConfig = new ControlConfiguration(nodeId, config);

    // Then
    assertThat(controlConfig.nodeId()).isEqualTo(nodeId);
    assertThat(controlConfig.config()).containsEntry("key1", "value1").containsEntry("key2", 42);
  }

  @Test
  void compactConstructor_mapDefensivelyCopied_originalMapMutationsHaveNoEffect() {
    // Given
    String nodeId = "node-1";
    Map<String, Object> originalMap = new HashMap<>();
    originalMap.put("key1", "value1");
    ControlConfiguration controlConfig = new ControlConfiguration(nodeId, originalMap);

    // When
    originalMap.put("key2", "value2");

    // Then
    assertThat(controlConfig.config()).containsKey("key1");
    assertThat(controlConfig.config()).doesNotContainKey("key2");
  }

  @Test
  void compactConstructor_storedMapIsUnmodifiable_mutationsThrowException() {
    // Given
    String nodeId = "node-1";
    Map<String, Object> config = Map.of("key1", "value1");
    ControlConfiguration controlConfig = new ControlConfiguration(nodeId, config);

    // When & Then
    assertThatThrownBy(() -> controlConfig.config().put("key2", "value2"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void record_sameFieldValues_areEqual() {
    // Given
    Map<String, Object> config = Map.of("key1", "value1");
    ControlConfiguration config1 = new ControlConfiguration("node-1", config);
    ControlConfiguration config2 = new ControlConfiguration("node-1", config);

    // When & Then
    assertThat(config1).isEqualTo(config2);
  }

  @Test
  void record_differentNodeId_areNotEqual() {
    // Given
    Map<String, Object> config = Map.of("key1", "value1");
    ControlConfiguration config1 = new ControlConfiguration("node-1", config);
    ControlConfiguration config2 = new ControlConfiguration("node-2", config);

    // When & Then
    assertThat(config1).isNotEqualTo(config2);
  }

  @Test
  void record_differentConfig_areNotEqual() {
    // Given
    Map<String, Object> config1 = Map.of("key1", "value1");
    Map<String, Object> config2 = Map.of("key2", "value2");
    ControlConfiguration controlConfig1 = new ControlConfiguration("node-1", config1);
    ControlConfiguration controlConfig2 = new ControlConfiguration("node-1", config2);

    // When & Then
    assertThat(controlConfig1).isNotEqualTo(controlConfig2);
  }

  @Test
  void record_sameValues_haveSameHashCode() {
    // Given
    Map<String, Object> config = Map.of("key1", "value1");
    ControlConfiguration config1 = new ControlConfiguration("node-1", config);
    ControlConfiguration config2 = new ControlConfiguration("node-1", config);

    // When & Then
    assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
  }

  @Test
  void record_differentValues_likelyDifferentHashCode() {
    // Given
    Map<String, Object> config1 = Map.of("key1", "value1");
    Map<String, Object> config2 = Map.of("key2", "value2");
    ControlConfiguration controlConfig1 = new ControlConfiguration("node-1", config1);
    ControlConfiguration controlConfig2 = new ControlConfiguration("node-2", config2);

    // When & Then
    assertThat(controlConfig1.hashCode()).isNotEqualTo(controlConfig2.hashCode());
  }

  @Test
  void record_accessor_nodeIdReturnsCorrectValue() {
    // Given
    String nodeId = "test-node";
    Map<String, Object> config = Map.of();
    ControlConfiguration controlConfig = new ControlConfiguration(nodeId, config);

    // When
    String result = controlConfig.nodeId();

    // Then
    assertThat(result).isEqualTo(nodeId);
  }

  @Test
  void record_accessor_configReturnsCorrectValue() {
    // Given
    String nodeId = "node-1";
    Map<String, Object> config = Map.of("key1", "value1", "key2", 42);
    ControlConfiguration controlConfig = new ControlConfiguration(nodeId, config);

    // When
    Map<String, Object> result = controlConfig.config();

    // Then
    assertThat(result).containsEntry("key1", "value1").containsEntry("key2", 42);
  }

  @Test
  void toString_containsAllFieldValues() {
    // Given
    String nodeId = "node-1";
    Map<String, Object> config = Map.of("key1", "value1");
    ControlConfiguration controlConfig = new ControlConfiguration(nodeId, config);

    // When
    String result = controlConfig.toString();

    // Then
    assertThat(result).contains("node-1").contains("key1");
  }

  @Test
  void record_nullComparison_isNotEqual() {
    // Given
    Map<String, Object> config = Map.of();
    ControlConfiguration controlConfig = new ControlConfiguration("node-1", config);

    // When & Then
    assertThat(controlConfig).isNotEqualTo(null);
  }

  @Test
  void equals_withDifferentType_returnsFalse() {
    // Given
    Map<String, Object> config = Map.of();
    ControlConfiguration controlConfig = new ControlConfiguration("node-1", config);
    String other = "not a control configuration";

    // When & Then
    assertThat(controlConfig).isNotEqualTo(other);
  }
}
