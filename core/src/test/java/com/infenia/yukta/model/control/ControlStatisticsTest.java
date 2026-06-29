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
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link ControlStatistics}. */
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
@NoArgsConstructor
class ControlStatisticsTest {

  @Test
  void testConstructorWithMetrics() {
    // Given
    final String nodeId = "node-1";
    final double throughput = 100.5;
    final double latency = 50.0;
    final Map<String, Object> metrics = Map.of("metric1", "value1", "metric2", 42);

    // When
    final ControlStatistics stats = new ControlStatistics(nodeId, throughput, latency, metrics);

    // Then
    assertThat(stats.nodeId()).isEqualTo(nodeId);
    assertThat(stats.throughput()).isEqualTo(throughput);
    assertThat(stats.latency()).isEqualTo(latency);
    assertThat(stats.metrics()).containsEntry("metric1", "value1").containsEntry("metric2", 42);
  }

  @Test
  void testConstructorWithEmptyMetrics() {
    // Given
    final String nodeId = "node-2";
    final double throughput = 200.0;
    final double latency = 30.5;
    final Map<String, Object> metrics = Map.of();

    // When
    final ControlStatistics stats = new ControlStatistics(nodeId, throughput, latency, metrics);

    // Then
    assertThat(stats.metrics()).isEmpty();
  }

  @Test
  void testConstructorWithNullMetrics() {
    // Given
    final String nodeId = "node-3";
    final double throughput = 150.0;
    final double latency = 40.0;

    // When
    final ControlStatistics stats = new ControlStatistics(nodeId, throughput, latency);

    // Then
    assertThat(stats.metrics()).isEmpty();
  }

  @Test
  void testMetricsImmutability() {
    // Given
    final String nodeId = "node-1";
    final Map<String, Object> metrics = Map.of("key1", "value1");
    final ControlStatistics stats = new ControlStatistics(nodeId, 100.0, 50.0, metrics);

    // When & Then
    assertThatThrownBy(() -> stats.metrics().put("key2", "value2"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void testAccessorMethods() {
    // Given
    final String nodeId = "test-node";
    final double throughput = 123.45;
    final double latency = 67.89;
    final Map<String, Object> metrics = Map.of("key", "value");
    final ControlStatistics stats = new ControlStatistics(nodeId, throughput, latency, metrics);

    // When & Then
    assertThat(stats.nodeId()).isEqualTo(nodeId);
    assertThat(stats.throughput()).isEqualTo(throughput);
    assertThat(stats.latency()).isEqualTo(latency);
    assertThat(stats.metrics()).containsEntry("key", "value");
  }

  @Test
  void testEqualsAndHashCode() {
    // Given
    final Map<String, Object> metrics = Map.of("key", "value");
    final ControlStatistics stats1 = new ControlStatistics("node-1", 100.0, 50.0, metrics);
    final ControlStatistics stats2 = new ControlStatistics("node-1", 100.0, 50.0, metrics);

    // When & Then
    assertThat(stats1).isEqualTo(stats2);
    assertThat(stats1.hashCode()).isEqualTo(stats2.hashCode());
  }

  @Test
  void testEqualsAndHashCodeDifferent() {
    // Given
    final Map<String, Object> metrics1 = Map.of("key1", "value1");
    final Map<String, Object> metrics2 = Map.of("key2", "value2");
    final ControlStatistics stats1 = new ControlStatistics("node-1", 100.0, 50.0, metrics1);
    final ControlStatistics stats2 = new ControlStatistics("node-2", 200.0, 30.0, metrics2);

    // When & Then
    assertThat(stats1).isNotEqualTo(stats2);
  }

  @Test
  void testToString() {
    // Given
    final String nodeId = "node-1";
    final ControlStatistics stats = new ControlStatistics(nodeId, 100.0, 50.0);

    // When
    final String result = stats.toString();

    // Then
    assertThat(result).contains("node-1").contains("100.0").contains("50.0");
  }

  @Test
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  void testMetricsPreservesContent() {
    // Given
    final Map<String, Object> metrics = new HashMap<>();
    metrics.put("stringMetric", "value");
    metrics.put("numberMetric", 42);
    metrics.put("doubleMetric", Math.PI);
    final ControlStatistics stats = new ControlStatistics("node-1", 100.0, 50.0, metrics);

    // When
    final Map<String, Object> result = stats.metrics();

    // Then
    assertThat(result)
        .containsEntry("stringMetric", "value")
        .containsEntry("numberMetric", 42)
        .containsEntry("doubleMetric", Math.PI);
  }

  @Test
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  void defensiveCopyPreventsOriginalMapMutations() {
    // Given
    final Map<String, Object> originalMap = new HashMap<>();
    originalMap.put("key1", "value1");
    final ControlStatistics stats = new ControlStatistics("node-1", 100.0, 50.0, originalMap);

    // When
    originalMap.put("key2", "value2");

    // Then
    assertThat(stats.metrics()).containsKey("key1");
    assertThat(stats.metrics()).doesNotContainKey("key2");
  }
}
