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
package com.infenia.yukta.model.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;

class WorkflowTriggerRequestTest {
  @Test
  void shouldInitializeCorrectly() {
    Map<String, Object> payload = Map.of("key", "value");
    WorkflowTriggerRequest request = new WorkflowTriggerRequest("session-1", "workflow-1", payload);

    assertEquals("session-1", request.sessionId());
    assertEquals("workflow-1", request.workflowId());
    assertEquals(payload, request.payload());
  }

  @Test
  void shouldHandleNullPayload() {
    WorkflowTriggerRequest request = new WorkflowTriggerRequest("session-1", "workflow-1", null);

    assertNotNull(request.payload());
    assertTrue(request.payload().isEmpty());
  }

  @Test
  void shouldBeUnmodifiable() {
    Map<String, Object> source = new java.util.HashMap<>();
    source.put("key", "value");
    WorkflowTriggerRequest request = new WorkflowTriggerRequest("s", "w", source);

    source.put("newKey", "newValue");
    assertEquals(
        1, request.payload().size(), "Payload should not be affected by changes to source map");

    assertThrows(
        UnsupportedOperationException.class,
        () -> request.payload().put("test", "test"),
        "Payload map should be unmodifiable");
  }

  @Test
  void testEqualsAndHashCode() {
    Map<String, Object> payload1 = Map.of("k", "v");
    Map<String, Object> payload2 = Map.of("k", "v");
    WorkflowTriggerRequest req1 = new WorkflowTriggerRequest("s", "w", payload1);
    WorkflowTriggerRequest req2 = new WorkflowTriggerRequest("s", "w", payload2);
    WorkflowTriggerRequest req3 = new WorkflowTriggerRequest("s2", "w", payload1);

    assertEquals(req1, req2);
    assertEquals(req1.hashCode(), req2.hashCode());
    assertNotEquals(req1, req3);
  }

  @Test
  void testToString() {
    WorkflowTriggerRequest request = new WorkflowTriggerRequest("s", "w", Map.of());
    String toString = request.toString();
    assertTrue(toString.contains("sessionId=s"));
    assertTrue(toString.contains("workflowId=w"));
  }
}
