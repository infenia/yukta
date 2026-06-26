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

import java.time.Instant;
import org.junit.jupiter.api.Test;

class ControlHeartbeatTest {

  @Test
  void fullConstructor_allParametersProvided_createsInstanceWithAllFields() {
    // Given
    String nodeId = "node-1";
    long uptime = 5000L;
    Instant timestamp = Instant.parse("2026-06-25T10:00:00Z");

    // When
    ControlHeartbeat heartbeat = new ControlHeartbeat(nodeId, uptime, timestamp);

    // Then
    assertThat(heartbeat.nodeId()).isEqualTo(nodeId);
    assertThat(heartbeat.uptime()).isEqualTo(uptime);
    assertThat(heartbeat.timestamp()).isEqualTo(timestamp);
  }

  @Test
  void convenienceConstructor_withoutTimestamp_setsTimestampToNow() {
    // Given
    String nodeId = "node-2";
    long uptime = 3000L;
    Instant beforeConstruction = Instant.now();

    // When
    ControlHeartbeat heartbeat = new ControlHeartbeat(nodeId, uptime);
    Instant afterConstruction = Instant.now();

    // Then
    assertThat(heartbeat.nodeId()).isEqualTo(nodeId);
    assertThat(heartbeat.uptime()).isEqualTo(uptime);
    assertThat(heartbeat.timestamp()).isNotNull();
    assertThat(heartbeat.timestamp()).isAfterOrEqualTo(beforeConstruction);
    assertThat(heartbeat.timestamp()).isBeforeOrEqualTo(afterConstruction);
  }

  @Test
  void record_sameFieldValues_areEqual() {
    // Given
    Instant timestamp = Instant.parse("2026-06-25T11:00:00Z");
    ControlHeartbeat heartbeat1 = new ControlHeartbeat("node-1", 5000L, timestamp);
    ControlHeartbeat heartbeat2 = new ControlHeartbeat("node-1", 5000L, timestamp);

    // When & Then
    assertThat(heartbeat1).isEqualTo(heartbeat2);
  }

  @Test
  void record_differentNodeId_areNotEqual() {
    // Given
    Instant timestamp = Instant.parse("2026-06-25T11:00:00Z");
    ControlHeartbeat heartbeat1 = new ControlHeartbeat("node-1", 5000L, timestamp);
    ControlHeartbeat heartbeat2 = new ControlHeartbeat("node-2", 5000L, timestamp);

    // When & Then
    assertThat(heartbeat1).isNotEqualTo(heartbeat2);
  }

  @Test
  void record_differentUptime_areNotEqual() {
    // Given
    Instant timestamp = Instant.parse("2026-06-25T11:00:00Z");
    ControlHeartbeat heartbeat1 = new ControlHeartbeat("node-1", 5000L, timestamp);
    ControlHeartbeat heartbeat2 = new ControlHeartbeat("node-1", 3000L, timestamp);

    // When & Then
    assertThat(heartbeat1).isNotEqualTo(heartbeat2);
  }

  @Test
  void record_differentTimestamp_areNotEqual() {
    // Given
    ControlHeartbeat heartbeat1 =
        new ControlHeartbeat("node-1", 5000L, Instant.parse("2026-06-25T11:00:00Z"));
    ControlHeartbeat heartbeat2 =
        new ControlHeartbeat("node-1", 5000L, Instant.parse("2026-06-25T12:00:00Z"));

    // When & Then
    assertThat(heartbeat1).isNotEqualTo(heartbeat2);
  }

  @Test
  void record_sameValues_haveSameHashCode() {
    // Given
    Instant timestamp = Instant.parse("2026-06-25T11:00:00Z");
    ControlHeartbeat heartbeat1 = new ControlHeartbeat("node-1", 5000L, timestamp);
    ControlHeartbeat heartbeat2 = new ControlHeartbeat("node-1", 5000L, timestamp);

    // When & Then
    assertThat(heartbeat1.hashCode()).isEqualTo(heartbeat2.hashCode());
  }

  @Test
  void record_differentValues_likelyDifferentHashCode() {
    // Given
    ControlHeartbeat heartbeat1 =
        new ControlHeartbeat("node-1", 5000L, Instant.parse("2026-06-25T11:00:00Z"));
    ControlHeartbeat heartbeat2 =
        new ControlHeartbeat("node-2", 3000L, Instant.parse("2026-06-25T12:00:00Z"));

    // When & Then
    assertThat(heartbeat1.hashCode()).isNotEqualTo(heartbeat2.hashCode());
  }

  @Test
  void record_accessor_nodeIdReturnsCorrectValue() {
    // Given
    String nodeId = "test-node";
    ControlHeartbeat heartbeat =
        new ControlHeartbeat(nodeId, 1000L, Instant.parse("2026-06-25T10:00:00Z"));

    // When
    String result = heartbeat.nodeId();

    // Then
    assertThat(result).isEqualTo(nodeId);
  }

  @Test
  void record_accessor_uptimeReturnsCorrectValue() {
    // Given
    long uptime = 12345L;
    ControlHeartbeat heartbeat =
        new ControlHeartbeat("node-1", uptime, Instant.parse("2026-06-25T10:00:00Z"));

    // When
    long result = heartbeat.uptime();

    // Then
    assertThat(result).isEqualTo(uptime);
  }

  @Test
  void record_accessor_timestampReturnsCorrectValue() {
    // Given
    Instant timestamp = Instant.parse("2026-06-25T10:00:00Z");
    ControlHeartbeat heartbeat = new ControlHeartbeat("node-1", 5000L, timestamp);

    // When
    Instant result = heartbeat.timestamp();

    // Then
    assertThat(result).isEqualTo(timestamp);
  }

  @Test
  void toString_containsAllFieldValues() {
    // Given
    String nodeId = "node-1";
    long uptime = 5000L;
    Instant timestamp = Instant.parse("2026-06-25T10:00:00Z");
    ControlHeartbeat heartbeat = new ControlHeartbeat(nodeId, uptime, timestamp);

    // When
    String result = heartbeat.toString();

    // Then
    assertThat(result).contains("node-1").contains("5000").contains("2026-06-25");
  }

  @Test
  void record_nullComparison_isNotEqual() {
    // Given
    ControlHeartbeat heartbeat =
        new ControlHeartbeat("node-1", 5000L, Instant.parse("2026-06-25T10:00:00Z"));

    // When & Then
    assertThat(heartbeat).isNotEqualTo(null);
  }

  @Test
  void equals_withDifferentType_returnsFalse() {
    // Given
    ControlHeartbeat heartbeat =
        new ControlHeartbeat("node-1", 5000L, Instant.parse("2026-06-25T10:00:00Z"));
    String other = "not a heartbeat";

    // When & Then
    assertThat(heartbeat).isNotEqualTo(other);
  }
}
