// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.message;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

    assertThat(msg.id()).isEqualTo(id);
    assertThat(msg.traceId()).isEqualTo(traceId);
    assertThat(msg.correlationId()).isEqualTo(correlationId);
    assertThat(msg.replyTo()).isEqualTo(replyTo);
    assertThat(msg.expiration()).isEqualTo(1000L);
    assertThat(msg.formatIndicator()).isEqualTo("fmt");
    assertThat(msg.metadata()).isEqualTo(Map.of("k", "v"));
    assertThat(msg.messageHistory()).isEqualTo(List.of("h1"));
    assertThat(msg.sequenceId()).isEqualTo("s1");
    assertThat(msg.sequenceNumber()).isEqualTo(1);
    assertThat(msg.sequenceSize()).isEqualTo(10);
    assertThat(msg.priority()).isEqualTo(5);
    assertThat(msg.controlMessage()).isTrue();
    assertThat(msg.origDest()).isEqualTo("orig");
    assertThat(msg.failureReason()).isEqualTo("fail");
    assertThat(msg.exceptionDetail()).isEqualTo("detail");
    assertThat(msg.retryCount()).isEqualTo(2);
    assertThat(msg.payload()).isEqualTo(payload);
    assertThat(msg.timestamp()).isEqualTo(now);
    assertThat(msg.sourcePort()).isEqualTo("port");
    assertThat(msg.sourceNodeId()).isEqualTo("node");
  }

  @Test
  void testFluentWitherMethods() {
    final DefaultMessage<String> msg = DefaultMessage.create(UUID.randomUUID(), "p");

    assertThat(msg.withTraceId("t1").traceId()).isEqualTo("t1");
    final Instant now = Instant.now();
    assertThat(msg.withTimestamp(now).timestamp()).isEqualTo(now);
    assertThat(msg.withCorrelationId("c1").correlationId()).isEqualTo("c1");
    assertThat(msg.withReplyTo("r1").replyTo()).isEqualTo("r1");
    assertThat(msg.withSourcePort("p1").sourcePort()).isEqualTo("p1");
    assertThat(msg.withSourceNodeId("n1").sourceNodeId()).isEqualTo("n1");
    assertThat(msg.withExpiration(500L).expiration()).isEqualTo(500L);
    assertThat(msg.withFormatIndicator("fmt").formatIndicator()).isEqualTo("fmt");
    assertThat(msg.withAddedHistory("node").messageHistory()).contains("node");
    assertThat(msg.withPriority(9).priority()).isEqualTo(9);
    assertThat(msg.withControl(true).controlMessage()).isTrue();

    final Message<String> failed = msg.withFailure("o", "r", "d");
    assertThat(failed.origDest()).isEqualTo("o");
    assertThat(failed.failureReason()).isEqualTo("r");

    assertThat(msg.withRetryCount(5).retryCount()).isEqualTo(5);
    assertThat(msg.withPayload(10).payload()).isEqualTo(10);
    assertThat(msg.withHeader("k", "v").metadata().get("k")).isEqualTo("v");
    assertThat(msg.withMetadata(Map.of("x", "y")).metadata()).isEqualTo(Map.of("x", "y"));
  }

  @Test
  void testStaticFactories() {
    final String msg = "msg";
    assertThat(DefaultMessage.create(null, msg).payload()).isEqualTo(msg);

    final String from = "from";
    assertThat(DefaultMessage.from(DefaultMessage.create(null, from), 10).payload()).isEqualTo(10);
  }

  @Test
  void testCompactConstructorDefaults() {
    final DefaultMessage<String> msg =
        new DefaultMessage<>(
            "id", null, null, null, 0, null, null, null, null, 0, 0, 0, false, null, null, null, 0,
            "p", null, null, null, null);
    assertThat(msg.metadata()).isNotNull();
    assertThat(msg.messageHistory()).isNotNull();
  }

  @Test
  void testCompactConstructorNullCoercion() {
    final DefaultMessage<String> msg =
        new DefaultMessage<>(
            "id1", "trace1", "corr1", "reply1", 1000L, "format", null, null, "seq1", 1, 5, 2, false,
            "orig", "reason", "detail", 0, "payload", null, "port1", "node1", "wf1");

    assertThat(msg.metadata()).isEmpty();
    assertThat(msg.metadata()).isNotNull();
    assertThat(msg.messageHistory()).isEmpty();
    assertThat(msg.messageHistory()).isNotNull();
    assertThat(msg.timestamp()).isNotNull();
  }

  @Test
  void testWithTraceId() {
    final DefaultMessage<String> original = DefaultMessage.create(UUID.randomUUID(), "payload");
    final String newTraceId = "newTrace123";

    final Message<String> updated = original.withTraceId(newTraceId);

    assertThat(updated.traceId()).isEqualTo(newTraceId);
    assertThat(updated.id()).isEqualTo(original.id());
    assertThat(updated.payload()).isEqualTo(original.payload());
    assertThat(updated.correlationId()).isEqualTo(original.correlationId());
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

    assertThat(updated.messageHistory()).containsExactly("node1", "node2");
    assertThat(updated.messageHistory()).hasSize(2);
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

    assertThat(msg.metadata()).containsEntry("key1", "value1");
    assertThat(msg.metadata()).containsEntry("key2", 42);
    assertThat(msg.metadata()).doesNotContainKey("key3");
    assertThat(msg.metadata()).hasSize(2);
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

    assertThat(msg.messageHistory()).containsExactly("node1", "node2");
    assertThat(msg.messageHistory()).hasSize(2);
  }

  @Test
  @SuppressWarnings("PMD.LawOfDemeter")
  void testCreateFactory() {
    final UUID traceId = UUID.randomUUID();
    final String payload = "testPayload";

    final DefaultMessage<String> msg = DefaultMessage.create(traceId, payload);

    assertThat(msg.id()).isNotNull().isNotEmpty();
    assertThat(msg.traceId()).isEqualTo(traceId.toString());
    assertThat(msg.payload()).isEqualTo(payload);
    assertThat(msg.correlationId()).isNull();
    assertThat(msg.replyTo()).isNull();
    assertThat(msg.expiration()).isZero();
    assertThat(msg.formatIndicator()).isNull();
    assertThat(msg.metadata()).isEmpty();
    assertThat(msg.messageHistory()).isEmpty();
    assertThat(msg.priority()).isZero();
    assertThat(msg.controlMessage()).isFalse();
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

    assertThat(from.id()).isEqualTo(withHeaders.id());
    assertThat(from.traceId()).isEqualTo(withHeaders.traceId());
    assertThat(from.correlationId()).isEqualTo("corrId");
    assertThat(from.replyTo()).isEqualTo("replyTo");
    assertThat(from.metadata()).containsEntry("headerKey", "headerValue");
    assertThat(from.payload()).isEqualTo(42);
  }

  @Test
  @SuppressWarnings("PMD.UseConcurrentHashMap")
  void testNullMetadataValuesAreDropped() {
    final Map<String, Object> raw = new HashMap<>();
    raw.put("keep", "v");
    raw.put("drop", null);
    final DefaultMessage<String> msg = DefaultMessage.create(null, "p").withMetadata(raw);
    assertThat(msg.metadata()).isEqualTo(Map.of("keep", "v"));
  }

  @Test
  void testWithHeaderNullValueRemovesKey() {
    final Message<String> msg = DefaultMessage.create(null, "p").withHeader("k", "v");
    assertThat(msg.withHeader("k", null).metadata()).isEmpty();
  }

  @Test
  void testWithHeaderNullKeyRejected() {
    final Message<String> msg = DefaultMessage.create(null, "p");
    assertThatThrownBy(() -> msg.withHeader(null, "v")).isInstanceOf(NullPointerException.class);
  }

  @Test
  void testHistoryIsCapped() {
    Message<String> msg = DefaultMessage.create(null, "p");
    for (int i = 0; i < DefaultMessage.MAX_HISTORY + 10; i++) {
      msg = msg.withAddedHistory("n" + i);
    }
    assertThat(msg.messageHistory()).hasSize(DefaultMessage.MAX_HISTORY);
    assertThat(msg.messageHistory().get(DefaultMessage.MAX_HISTORY - 1))
        .isEqualTo("n" + (DefaultMessage.MAX_HISTORY + 9));
    assertThat(msg.messageHistory().get(0)).isEqualTo("n10");
  }

  @Test
  void testFromKeepsTimestampPrecision() {
    final Instant precise = Instant.parse("2026-01-01T00:00:00.123456789Z");
    final Message<String> original = DefaultMessage.create(null, "p").withTimestamp(precise);
    assertThat(DefaultMessage.from(original, 1).timestamp()).isEqualTo(precise);
  }
}
