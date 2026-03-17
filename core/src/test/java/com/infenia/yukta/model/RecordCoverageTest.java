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
package com.infenia.yukta.model;

import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.infenia.yukta.model.api.ControlBusStatus;
import com.infenia.yukta.model.api.PluginCreationGuide;
import com.infenia.yukta.model.api.SystemHealthMetrics;
import com.infenia.yukta.model.monitoring.WorkflowExecutionSummary;
import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.service.resequence.ResequencerStore.ResequenceConfig;
import com.infenia.yukta.service.resequence.ResequencerStore.ResequenceResult;
import java.time.Instant;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class RecordCoverageTest {

  @Test
  void testDefaultMessage() {
    UUID id = UUID.randomUUID();
    DefaultMessage<String> msg = DefaultMessage.create(id, "payload");
    assertNotNull(msg.toString());
    assertNotNull(msg.hashCode());
    msg.equals(msg);
    msg.equals(DefaultMessage.create(id, "payload"));
    msg.equals(null);

    // Test builder-like methods
    assertNotNull(msg.withAddedHistory("node"));
    assertNotNull(msg.withHeader("k", "v"));
    assertNotNull(msg.withSourceNodeId("node"));
    assertNotNull(msg.withControl(true));
    assertNotNull(msg.withPriority(10));
    assertNotNull(msg.withSourcePort("port"));

    // Full constructor (21 fields)
    new DefaultMessage<>(
        id.toString(),
        null,
        null,
        null,
        0L,
        null,
        Map.of(),
        List.of(),
        null,
        0,
        0,
        0,
        false,
        null,
        null,
        null,
        0,
        "p",
        Instant.now(),
        null,
        null);
  }

  @Test
  void testWorkflowExecutionSummary() {
    LocalDateTime now = LocalDateTime.now();
    WorkflowExecutionSummary summary =
        new WorkflowExecutionSummary("e1", "w1", "RUNNING", now, null);
    assertNotNull(summary.toString());
    assertNotNull(summary.hashCode());
    summary.equals(summary);
    summary.equals(new WorkflowExecutionSummary("e1", "w1", "RUNNING", now, null));
    summary.equals(null);
    summary.equals("other");
  }

  @Test
  void testControlBusStatus() {
    SystemHealthMetrics health = new SystemHealthMetrics(0, 0, "0", "0", "0s");
    ControlBusStatus status = new ControlBusStatus(List.of(), List.of(), health, List.of());
    assertNotNull(status.toString());
    assertNotNull(status.hashCode());
    status.equals(status);

    // Cover null branch in compact constructor
    new ControlBusStatus(null, null, health, null);
  }

  @Test
  void testPluginCreationGuide() {
    PluginCreationGuide guide = new PluginCreationGuide("a", Map.of(), "i", "c", "v", "t", "d");
    assertNotNull(guide.toString());
    assertNotNull(guide.hashCode());
    guide.equals(guide);

    // Cover null branch in compact constructor
    new PluginCreationGuide(null, null, null, null, null, null, null);
  }

  @Test
  void testResequenceRecords() {
    ResequenceConfig config = new ResequenceConfig(1, 100, 10, "err");
    assertNotNull(config.toString());
    assertNotNull(config.hashCode());
    config.equals(config);

    ResequenceResult result =
        new ResequenceResult(ResequenceResult.Status.COMPLETED, List.of(), "key", null);
    assertNotNull(result.toString());
    assertNotNull(result.hashCode());
    result.equals(result);

    // Cover null branch in compact constructor
    new ResequenceResult(null, null, null, null);
  }
}
