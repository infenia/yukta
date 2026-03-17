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
package com.infenia.yukta.plugin.message;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class DefaultMessageTest {

  @Test
  void testCoreAccessors() {
    String id = "id1";
    String traceId = "t1";
    String correlationId = "c1";
    String replyTo = "r1";
    Instant now = Instant.now();
    String payload = "data";

    DefaultMessage<String> msg =
        new DefaultMessage<>(
            id,
            traceId,
            correlationId,
            replyTo,
            1000L,
            "fmt",
            Map.of("k", "v"),
            List.of("h1"),
            "s1",
            1,
            10,
            5,
            true,
            "orig",
            "fail",
            "detail",
            2,
            payload,
            now,
            "port",
            "node");

    assertEquals(id, msg.getMessageId());
    assertEquals(traceId, msg.getTraceId());
    assertEquals(correlationId, msg.getCorrelationId());
    assertEquals(replyTo, msg.getReplyTo());
    assertEquals(1000L, msg.getExpiration());
    assertEquals("fmt", msg.getFormatIndicator());
    assertEquals(Map.of("k", "v"), msg.getMetadata());
    assertEquals(List.of("h1"), msg.getMessageHistory());
    assertEquals("s1", msg.getSequenceId());
    assertEquals(1, msg.getSequenceNumber());
    assertEquals(10, msg.getSequenceSize());
    assertEquals(5, msg.getPriority());
    assertTrue(msg.isControlMessage());
    assertEquals("orig", msg.getOrigDest());
    assertEquals("fail", msg.getFailureReason());
    assertEquals("detail", msg.getExceptionDetail());
    assertEquals(2, msg.getRetryCount());
    assertEquals(payload, msg.getPayload());
    assertEquals(now.toEpochMilli(), msg.getTimestamp());
    assertEquals("port", msg.getSourcePort());
    assertEquals("node", msg.getSourceNodeId());
    assertFalse(msg.isLastInSequence());
  }

  @Test
  void testSequenceLogic() {
    Message<String> msg = DefaultMessage.create(null, "p").withSequence("s", 5, 5);
    assertTrue(msg.isLastInSequence());

    Message<String> msg2 = DefaultMessage.create(null, "p").withSequence("s", 1, 5);
    assertFalse(msg2.isLastInSequence());
  }

  @Test
  void testFluentWitherMethods() {
    DefaultMessage<String> msg = DefaultMessage.create(UUID.randomUUID(), "p");

    assertEquals("t1", msg.withTraceId("t1").getTraceId());
    Instant now = Instant.now();
    assertEquals(now.toEpochMilli(), msg.withTimestamp(now).getTimestamp());
    assertEquals("c1", msg.withCorrelationId("c1").getCorrelationId());
    assertEquals("r1", msg.withReplyTo("r1").getReplyTo());
    assertEquals("p1", msg.withSourcePort("p1").getSourcePort());
    assertEquals("n1", msg.withSourceNodeId("n1").getSourceNodeId());
    assertEquals(500L, msg.withExpiration(500L).getExpiration());
    assertEquals("fmt", msg.withFormatIndicator("fmt").getFormatIndicator());
    assertTrue(msg.withAddedHistory("node").getMessageHistory().contains("node"));
    assertEquals(9, msg.withPriority(9).getPriority());
    assertTrue(msg.withControl(true).isControlMessage());

    var failed = msg.withFailure("o", "r", "d");
    assertEquals("o", failed.getOrigDest());
    assertEquals("r", failed.getFailureReason());

    assertEquals(5, msg.withRetryCount(5).getRetryCount());
    assertEquals(1, msg.withIncrementedRetry().getRetryCount());
    assertEquals(10, msg.withPayload(10).getPayload());
    assertEquals("v", msg.withHeader("k", "v").getMetadata().get("k"));
    assertEquals(Map.of("x", "y"), msg.withMetadata(Map.of("x", "y")).getMetadata());
  }

  @Test
  void testStaticFactories() {
    UUID tid = UUID.randomUUID();
    DefaultMessage<String> msg = DefaultMessage.create(tid, "p");
    assertEquals(tid.toString(), msg.getTraceId());

    DefaultMessage<Integer> from = DefaultMessage.from(msg, 100);
    assertEquals(msg.getMessageId(), from.getMessageId());
    assertEquals(100, from.getPayload());

    // Null traceId branch
    assertEquals(null, DefaultMessage.create(null, "p").getTraceId());
  }

  @Test
  void testCompactConstructorNulls() {
    DefaultMessage<String> msg =
        new DefaultMessage<>(
            "id", null, null, null, 0, null, null, null, null, 0, 0, 0, false, null, null, null, 0,
            "p", null, null, null);

    assertNotNull(msg.getMetadata());
    assertNotNull(msg.getMessageHistory());
    assertNotNull(msg.getTimestamp());
  }
}
