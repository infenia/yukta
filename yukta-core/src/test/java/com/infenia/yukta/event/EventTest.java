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
package com.infenia.yukta.event;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import java.util.Map;
import org.junit.jupiter.api.Test;

class EventTest {

  @Test
  void testTaskStatusEvent() {
    TaskStatusEvent event =
        TaskStatusEvent.create("exec1", "node1", "module1", "SUCCESS", Map.of("k", "v"));
    assertEquals("exec1", event.executionId());
    assertEquals("node1", event.nodeId());
    assertEquals("SUCCESS", event.status());
    assertNotNull(event.timestamp());
    assertNotNull(event.metadata());

    // Coverage for null metadata
    TaskStatusEvent event2 = TaskStatusEvent.create("exec1", "node1", "module1", "SUCCESS", null);
    assertNotNull(event2.metadata());

    // Trigger metadata() override directly for full coverage
    TaskStatusEvent event3 = new TaskStatusEvent("e", "n", "m", "s", Map.of("x", "y"), null);
    assertNotNull(event3.metadata());

    TaskStatusEvent event4 = new TaskStatusEvent("e", "n", "m", "s", null, null);
    assertNotNull(event4.metadata());
  }

  @Test
  void testWorkflowLogEvent() {
    WorkflowLogEvent event = WorkflowLogEvent.create("exec1", "message");
    assertEquals("exec1", event.executionId());
    assertEquals("message", event.line());
    assertNotNull(event.timestamp());
  }

  @Test
  void testWorkflowStatusEvent() {
    WorkflowStatusEvent event = WorkflowStatusEvent.create("exec1", "COMPLETED");
    assertEquals("exec1", event.executionId());
    assertEquals("COMPLETED", event.status());
    assertNotNull(event.timestamp());
  }
}
