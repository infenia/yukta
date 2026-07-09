// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.model.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link ControlConfiguration}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals", "PMD.UseConcurrentHashMap"})
class ControlConfigurationTest {

  @Test
  void compactConstructor_validMapProvided_createsInstanceWithCopiedConfig() {
    // Given
    final String nodeId = "node-1";
    final Map<String, Object> config = Map.of("key1", "value1", "key2", 42);

    // When
    final ControlConfiguration controlConfig = new ControlConfiguration(nodeId, config);

    // Then
    assertThat(controlConfig.nodeId()).isEqualTo(nodeId);
    assertThat(controlConfig.config()).containsEntry("key1", "value1").containsEntry("key2", 42);
  }

  @Test
  void compactConstructor_mapDefensivelyCopied_originalMapMutationsHaveNoEffect() {
    // Given
    final String nodeId = "node-1";
    final Map<String, Object> originalMap = new HashMap<>();
    originalMap.put("key1", "value1");
    final ControlConfiguration controlConfig = new ControlConfiguration(nodeId, originalMap);

    // When
    originalMap.put("key2", "value2");

    // Then
    assertThat(controlConfig.config()).containsKey("key1");
    assertThat(controlConfig.config()).doesNotContainKey("key2");
  }

  @Test
  void compactConstructor_storedMapIsUnmodifiable_mutationsThrowException() {
    // Given
    final String nodeId = "node-1";
    final Map<String, Object> config = Map.of("key1", "value1");
    final ControlConfiguration controlConfig = new ControlConfiguration(nodeId, config);

    // When & Then
    assertThatThrownBy(() -> controlConfig.config().put("key2", "value2"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void record_sameFieldValues_areEqual() {
    // Given
    final Map<String, Object> config = Map.of("key1", "value1");
    final ControlConfiguration config1 = new ControlConfiguration("node-1", config);
    final ControlConfiguration config2 = new ControlConfiguration("node-1", config);

    // When & Then
    assertThat(config1).isEqualTo(config2);
  }

  @Test
  void record_differentNodeId_areNotEqual() {
    // Given
    final Map<String, Object> config = Map.of("key1", "value1");
    final ControlConfiguration config1 = new ControlConfiguration("node-1", config);
    final ControlConfiguration config2 = new ControlConfiguration("node-2", config);

    // When & Then
    assertThat(config1).isNotEqualTo(config2);
  }

  @Test
  void record_differentConfig_areNotEqual() {
    // Given
    final Map<String, Object> config1 = Map.of("key1", "value1");
    final Map<String, Object> config2 = Map.of("key2", "value2");
    final ControlConfiguration controlConfig1 = new ControlConfiguration("node-1", config1);
    final ControlConfiguration controlConfig2 = new ControlConfiguration("node-1", config2);

    // When & Then
    assertThat(controlConfig1).isNotEqualTo(controlConfig2);
  }

  @Test
  void record_sameValues_haveSameHashCode() {
    // Given
    final Map<String, Object> config = Map.of("key1", "value1");
    final ControlConfiguration config1 = new ControlConfiguration("node-1", config);
    final ControlConfiguration config2 = new ControlConfiguration("node-1", config);

    // When & Then
    assertThat(config1.hashCode()).isEqualTo(config2.hashCode());
  }

  @Test
  void record_differentValues_likelyDifferentHashCode() {
    // Given
    final Map<String, Object> config1 = Map.of("key1", "value1");
    final Map<String, Object> config2 = Map.of("key2", "value2");
    final ControlConfiguration controlConfig1 = new ControlConfiguration("node-1", config1);
    final ControlConfiguration controlConfig2 = new ControlConfiguration("node-2", config2);

    // When & Then
    assertThat(controlConfig1.hashCode()).isNotEqualTo(controlConfig2.hashCode());
  }

  @Test
  void record_accessor_nodeIdReturnsCorrectValue() {
    // Given
    final String nodeId = "test-node";
    final Map<String, Object> config = Map.of();
    final ControlConfiguration controlConfig = new ControlConfiguration(nodeId, config);

    // When
    final String result = controlConfig.nodeId();

    // Then
    assertThat(result).isEqualTo(nodeId);
  }

  @Test
  void record_accessor_configReturnsCorrectValue() {
    // Given
    final String nodeId = "node-1";
    final Map<String, Object> config = Map.of("key1", "value1", "key2", 42);
    final ControlConfiguration controlConfig = new ControlConfiguration(nodeId, config);

    // When
    final Map<String, Object> result = controlConfig.config();

    // Then
    assertThat(result).containsEntry("key1", "value1").containsEntry("key2", 42);
  }

  @Test
  @SuppressWarnings("PMD.LinguisticNaming")
  void toString_containsAllFieldValues() {
    // Given
    final String nodeId = "node-1";
    final Map<String, Object> config = Map.of("key1", "value1");
    final ControlConfiguration controlConfig = new ControlConfiguration(nodeId, config);

    // When
    final String result = controlConfig.toString();

    // Then
    assertThat(result).contains("node-1").contains("key1");
  }

  @Test
  void record_nullComparison_isNotEqual() {
    // Given
    final Map<String, Object> config = Map.of();
    final ControlConfiguration controlConfig = new ControlConfiguration("node-1", config);

    // When & Then
    assertThat(controlConfig).isNotEqualTo(null);
  }

  @Test
  void equals_withDifferentType_returnsFalse() {
    // Given
    final Map<String, Object> config = Map.of();
    final ControlConfiguration controlConfig = new ControlConfiguration("node-1", config);
    final String other = "not a control configuration";

    // When & Then
    assertThat(controlConfig).isNotEqualTo(other);
  }
}
