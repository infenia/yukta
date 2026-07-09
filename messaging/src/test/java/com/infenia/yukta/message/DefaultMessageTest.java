// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** Tests for {@link DefaultMessage}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals", "PMD.ShortVariable"})
class DefaultMessageTest {

  @Test
  void testCoreAccessors() {
    final String id = "id1";
    final String traceId = "t1";
    final String correlationId = "c1";
    final String replyTo = "r1";
    final Instant now = Instant.now();
    final String payload = "data";

    final DefaultMessage<String> msg =
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

    assertThat(msg.getMessageId()).isEqualTo(id);
    assertThat(msg.getTraceId()).isEqualTo(traceId);
    assertThat(msg.getCorrelationId()).isEqualTo(correlationId);
    assertThat(msg.getReplyTo()).isEqualTo(replyTo);
    assertThat(msg.getExpiration()).isEqualTo(1000L);
    assertThat(msg.getFormatIndicator()).isEqualTo("fmt");
    assertThat(msg.getMetadata()).isEqualTo(Map.of("k", "v"));
    assertThat(msg.getMessageHistory()).isEqualTo(List.of("h1"));
    assertThat(msg.getSequenceId()).isEqualTo("s1");
    assertThat(msg.getSequenceNumber()).isEqualTo(1);
    assertThat(msg.getSequenceSize()).isEqualTo(10);
    assertThat(msg.getPriority()).isEqualTo(5);
    assertThat(msg.isControlMessage()).isTrue();
    assertThat(msg.getOrigDest()).isEqualTo("orig");
    assertThat(msg.getFailureReason()).isEqualTo("fail");
    assertThat(msg.getExceptionDetail()).isEqualTo("detail");
    assertThat(msg.getRetryCount()).isEqualTo(2);
    assertThat(msg.getPayload()).isEqualTo(payload);
    assertThat(msg.getTimestamp()).isEqualTo(now.toEpochMilli());
    assertThat(msg.getSourcePort()).isEqualTo("port");
    assertThat(msg.getSourceNodeId()).isEqualTo("node");
    assertThat(msg.isLastInSequence()).isFalse();
  }

  @Test
  void testSequenceLogic() {
    final Message<String> msg = DefaultMessage.create(null, "p").withSequence("s", 5, 5);
    assertThat(msg.isLastInSequence()).isTrue();
  }

  @Test
  void testSequenceLogic2() {
    final Message<String> msg2 = DefaultMessage.create(null, "p").withSequence("s", 1, 5);
    assertThat(msg2.isLastInSequence()).isFalse();
  }

  @Test
  void testSequenceLogicZeroSize() {
    final Message<String> msg = DefaultMessage.create(null, "p").withSequence("s", 0, 0);
    assertThat(msg.isLastInSequence()).isFalse();
  }

  @Test
  void testFluentWitherMethods() {
    final DefaultMessage<String> msg = DefaultMessage.create(UUID.randomUUID(), "p");

    assertThat(msg.withTraceId("t1").getTraceId()).isEqualTo("t1");
    final Instant now = Instant.now();
    assertThat(msg.withTimestamp(now).getTimestamp()).isEqualTo(now.toEpochMilli());
    assertThat(msg.withCorrelationId("c1").getCorrelationId()).isEqualTo("c1");
    assertThat(msg.withReplyTo("r1").getReplyTo()).isEqualTo("r1");
    assertThat(msg.withSourcePort("p1").getSourcePort()).isEqualTo("p1");
    assertThat(msg.withSourceNodeId("n1").getSourceNodeId()).isEqualTo("n1");
    assertThat(msg.withExpiration(500L).getExpiration()).isEqualTo(500L);
    assertThat(msg.withFormatIndicator("fmt").getFormatIndicator()).isEqualTo("fmt");
    assertThat(msg.withAddedHistory("node").getMessageHistory()).contains("node");
    assertThat(msg.withPriority(9).getPriority()).isEqualTo(9);
    assertThat(msg.withControl(true).isControlMessage()).isTrue();

    final Message<String> failed = msg.withFailure("o", "r", "d");
    assertThat(failed.getOrigDest()).isEqualTo("o");
    assertThat(failed.getFailureReason()).isEqualTo("r");

    assertThat(msg.withRetryCount(5).getRetryCount()).isEqualTo(5);
    assertThat(msg.withIncrementedRetry().getRetryCount()).isEqualTo(1);
    assertThat(msg.withPayload(10).getPayload()).isEqualTo(10);
    assertThat(msg.withHeader("k", "v").getMetadata().get("k")).isEqualTo("v");
    assertThat(msg.withMetadata(Map.of("x", "y")).getMetadata()).isEqualTo(Map.of("x", "y"));
  }

  @Test
  void testStaticFactories() {
    final String msg = "msg";
    assertThat(DefaultMessage.create(null, msg).getPayload()).isEqualTo(msg);

    final String from = "from";
    assertThat(DefaultMessage.from(DefaultMessage.create(null, from), 10).getPayload())
        .isEqualTo(10);
  }

  @Test
  void testCompactConstructorDefaults() {
    final DefaultMessage<String> msg =
        new DefaultMessage<>(
            "id", null, null, null, 0, null, null, null, null, 0, 0, 0, false, null, null, null, 0,
            "p", null, null, null, null);
    assertThat(msg.getMetadata()).isNotNull();
    assertThat(msg.getMessageHistory()).isNotNull();
  }

  @Test
  void testCompactConstructorNullCoercion() {
    final DefaultMessage<String> msg =
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
    final DefaultMessage<String> original = DefaultMessage.create(UUID.randomUUID(), "payload");
    final String newTraceId = "newTrace123";

    final Message<String> updated = original.withTraceId(newTraceId);

    assertThat(updated.getTraceId()).isEqualTo(newTraceId);
    assertThat(updated.getMessageId()).isEqualTo(original.getMessageId());
    assertThat(updated.getPayload()).isEqualTo(original.getPayload());
    assertThat(updated.getCorrelationId()).isEqualTo(original.getCorrelationId());
  }

  @Test
  void testWithAddedHistory() {
    final DefaultMessage<String> original =
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

    final Message<String> updated = original.withAddedHistory("node2");

    assertThat(updated.getMessageHistory()).containsExactly("node1", "node2");
    assertThat(updated.getMessageHistory()).hasSize(2);
  }

  @Test
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  void testMetadataImmutability() {
    final Map<String, Object> originalMetadata = new HashMap<>();
    originalMetadata.put("key1", "value1");
    originalMetadata.put("key2", 42);

    final DefaultMessage<String> msg =
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
    final List<String> originalHistory = new java.util.ArrayList<>();
    originalHistory.add("node1");
    originalHistory.add("node2");

    final DefaultMessage<String> msg =
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
    originalHistory.removeFirst();

    assertThat(msg.getMessageHistory()).containsExactly("node1", "node2");
    assertThat(msg.getMessageHistory()).hasSize(2);
  }

  @Test
  @SuppressWarnings("PMD.LawOfDemeter")
  void testCreateFactory() {
    final UUID traceId = UUID.randomUUID();
    final String payload = "testPayload";

    final DefaultMessage<String> msg = DefaultMessage.create(traceId, payload);

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
    final UUID traceId = UUID.randomUUID();
    final String originalPayload = "original";
    final DefaultMessage<String> original = DefaultMessage.create(traceId, originalPayload);

    final Message<String> withHeaders =
        original
            .withCorrelationId("corrId")
            .withReplyTo("replyTo")
            .withHeader("headerKey", "headerValue");

    final Integer newPayload = 42;
    final DefaultMessage<Integer> from = DefaultMessage.from(withHeaders, newPayload);

    assertThat(from.getMessageId()).isEqualTo(withHeaders.getMessageId());
    assertThat(from.getTraceId()).isEqualTo(withHeaders.getTraceId());
    assertThat(from.getCorrelationId()).isEqualTo("corrId");
    assertThat(from.getReplyTo()).isEqualTo("replyTo");
    assertThat(from.getMetadata()).containsEntry("headerKey", "headerValue");
    assertThat(from.getPayload()).isEqualTo(42);
  }
}
