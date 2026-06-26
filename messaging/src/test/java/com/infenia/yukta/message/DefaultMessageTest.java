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
package com.infenia.yukta.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.HashMap;
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
            "node",
            "wf1");

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
            "p", null, null, null, null);

    assertNotNull(msg.getMetadata());
    assertNotNull(msg.getMessageHistory());
    assertNotNull(msg.getTimestamp());
  }

  @Test
  void testCompactConstructorNullCoercion() {
    DefaultMessage<String> msg =
        new DefaultMessage<>(
            "id1", "trace1", "corr1", "reply1", 1000L, "format", null, null, "seq1", 1, 5, 2, false,
            "orig", "reason", "detail", 0, "payload", null, "port1", "node1", "wf1");

    assertThat(msg.getMetadata()).isEmpty();
    assertThat(msg.getMetadata()).isNotNull();
    assertThat(msg.getMessageHistory()).isEmpty();
    assertThat(msg.getMessageHistory()).isNotNull();
    assertThat(msg.getTimestamp()).isGreaterThan(0L);
  }

  @Test
  void testWithTraceId() {
    DefaultMessage<String> original = DefaultMessage.create(UUID.randomUUID(), "payload");
    String newTraceId = "newTrace123";

    Message<String> updated = original.withTraceId(newTraceId);

    assertThat(updated.getTraceId()).isEqualTo(newTraceId);
    assertThat(updated.getMessageId()).isEqualTo(original.getMessageId());
    assertThat(updated.getPayload()).isEqualTo(original.getPayload());
    assertThat(updated.getCorrelationId()).isEqualTo(original.getCorrelationId());
  }

  @Test
  void testWithAddedHistory() {
    DefaultMessage<String> original =
        new DefaultMessage<>(
            "id",
            "trace",
            "corr",
            "reply",
            1000L,
            "fmt",
            Map.of(),
            List.of("node1"),
            "seq",
            1,
            5,
            1,
            false,
            null,
            null,
            null,
            0,
            "data",
            Instant.now(),
            "port",
            "node",
            "wf");

    Message<String> updated = original.withAddedHistory("node2");

    assertThat(updated.getMessageHistory()).containsExactly("node1", "node2");
    assertThat(original.getMessageHistory()).containsExactly("node1");
    assertThat(updated.getMessageId()).isEqualTo(original.getMessageId());
  }

  @Test
  void testWithSequence() {
    DefaultMessage<String> original = DefaultMessage.create(UUID.randomUUID(), "payload");

    Message<String> updated = original.withSequence("seq123", 3, 5);

    assertThat(updated.getSequenceId()).isEqualTo("seq123");
    assertThat(updated.getSequenceNumber()).isEqualTo(3);
    assertThat(updated.getSequenceSize()).isEqualTo(5);
    assertThat(updated.isLastInSequence()).isFalse();

    Message<String> lastMsg = original.withSequence("seq123", 5, 5);
    assertThat(lastMsg.isLastInSequence()).isTrue();
  }

  @Test
  void testMetadataImmutability() {
    Map<String, Object> originalMetadata = new HashMap<>();
    originalMetadata.put("key1", "value1");
    originalMetadata.put("key2", 42);

    DefaultMessage<String> msg =
        new DefaultMessage<>(
            "id",
            "trace",
            "corr",
            "reply",
            1000L,
            "fmt",
            originalMetadata,
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
            "data",
            Instant.now(),
            "port",
            "node",
            "wf");

    originalMetadata.put("key3", "malicious");
    originalMetadata.remove("key1");

    assertThat(msg.getMetadata()).containsEntry("key1", "value1");
    assertThat(msg.getMetadata()).containsEntry("key2", 42);
    assertThat(msg.getMetadata()).doesNotContainKey("key3");
    assertThat(msg.getMetadata()).hasSize(2);
  }

  @Test
  void testHistoryImmutability() {
    List<String> originalHistory = new java.util.ArrayList<>();
    originalHistory.add("node1");
    originalHistory.add("node2");

    DefaultMessage<String> msg =
        new DefaultMessage<>(
            "id",
            "trace",
            "corr",
            "reply",
            1000L,
            "fmt",
            Map.of(),
            originalHistory,
            null,
            0,
            0,
            0,
            false,
            null,
            null,
            null,
            0,
            "data",
            Instant.now(),
            "port",
            "node",
            "wf");

    originalHistory.add("node3");
    originalHistory.remove(0);

    assertThat(msg.getMessageHistory()).containsExactly("node1", "node2");
    assertThat(msg.getMessageHistory()).hasSize(2);
  }

  @Test
  void testCreateFactory() {
    UUID traceId = UUID.randomUUID();
    String payload = "testPayload";

    DefaultMessage<String> msg = DefaultMessage.create(traceId, payload);

    assertThat(msg.getMessageId()).isNotNull().isNotEmpty();
    assertThat(msg.getTraceId()).isEqualTo(traceId.toString());
    assertThat(msg.getPayload()).isEqualTo(payload);
    assertThat(msg.getCorrelationId()).isNull();
    assertThat(msg.getReplyTo()).isNull();
    assertThat(msg.getExpiration()).isZero();
    assertThat(msg.getFormatIndicator()).isNull();
    assertThat(msg.getMetadata()).isEmpty();
    assertThat(msg.getMessageHistory()).isEmpty();
    assertThat(msg.getPriority()).isZero();
    assertThat(msg.isControlMessage()).isFalse();
  }

  @Test
  void testFromFactory() {
    UUID traceId = UUID.randomUUID();
    String originalPayload = "original";
    DefaultMessage<String> original = DefaultMessage.create(traceId, originalPayload);

    Message<String> withHeaders =
        original
            .withCorrelationId("corrId")
            .withReplyTo("replyTo")
            .withHeader("headerKey", "headerValue");

    Integer newPayload = 42;
    DefaultMessage<Integer> from = DefaultMessage.from(withHeaders, newPayload);

    assertThat(from.getMessageId()).isEqualTo(withHeaders.getMessageId());
    assertThat(from.getTraceId()).isEqualTo(withHeaders.getTraceId());
    assertThat(from.getCorrelationId()).isEqualTo("corrId");
    assertThat(from.getReplyTo()).isEqualTo("replyTo");
    assertThat(from.getMetadata()).containsEntry("headerKey", "headerValue");
    assertThat(from.getPayload()).isEqualTo(42);
  }
}
