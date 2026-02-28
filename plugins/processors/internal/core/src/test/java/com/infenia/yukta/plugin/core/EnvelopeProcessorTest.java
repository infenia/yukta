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
package com.infenia.yukta.plugin.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.plugin.DefaultMessage;
import com.infenia.yukta.plugin.Message;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class EnvelopeProcessorTest {

  private EnvelopeProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new EnvelopeProcessor();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testWrapWithHeadersAndPromotion() {
    final Map<String, Object> config =
        Map.of(
            "mode",
            "WRAP",
            "headers",
            Map.of("x-tenant", "'TENANT-A'", "x-timestamp", "#payload.ts"),
            "promote",
            Map.of("x-order-id", "order.id"));

    final Map<String, Object> payload =
        Map.of("ts", 123456789L, "order", Map.of("id", "ORD-123", "amount", 100.0));
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), payload);

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(ctx -> ctx.put("nodeId", "wrap-node")))
        .expectNextMatches(
            m -> {
              assertEquals("default", m.getSourcePort());
              assertTrue(m.getMessageHistory().contains("wrap-node"));
              assertEquals("TENANT-A", m.getMetadata().get("x-tenant"));
              assertEquals(123456789L, m.getMetadata().get("x-timestamp"));
              assertEquals("ORD-123", m.getMetadata().get("x-order-id"));
              assertEquals(payload, m.getPayload());
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testWrapStrictModeFailure() {
    final Map<String, Object> config = new java.util.HashMap<>();
    config.put("mode", "WRAP");
    config.put("headers", Map.of("x-invalid", "#payload.missing"));
    config.put("strictMode", true);

    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of("foo", "bar"));

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(ctx -> ctx.put("nodeId", "wrap-node")))
        .expectNextMatches(
            m -> {
              assertEquals("error", m.getSourcePort());
              Object failureReason = m.getMetadata().get("failureReason");
              assertNotNull(failureReason, "failureReason should not be null");
              return true;
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testUnwrapWithExtractionAndUnpromotion() {
    final Map<String, Object> config =
        Map.of(
            "mode",
            "UNWRAP",
            "envelopeKey",
            "body",
            "promote",
            Map.of("x-tenant", "tenantId"),
            "headers",
            Map.of("x-technical", "anything"));

    final Map<String, Object> envelope = Map.of("body", Map.of("data", "hello"), "other", "stuff");
    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), envelope)
            .withHeader("x-tenant", "TENANT-B")
            .withHeader("x-technical", "to-be-removed")
            .withHeader("x-other", "keep-me");

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(ctx -> ctx.put("nodeId", "unwrap-node")))
        .expectNextMatches(
            m -> {
              assertEquals("default", m.getSourcePort());
              assertTrue(m.getMessageHistory().contains("unwrap-node"));

              Map<String, Object> resultPayload = (Map<String, Object>) m.getPayload();
              assertEquals("hello", resultPayload.get("data"));
              assertEquals("TENANT-B", resultPayload.get("tenantId"));

              assertFalse(m.getMetadata().containsKey("x-technical"));
              assertTrue(m.getMetadata().containsKey("x-other"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testUnwrapClearAllMetadata() {
    final Map<String, Object> config =
        Map.of(
            "mode", "UNWRAP",
            "envelopeKey", "body");

    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), Map.of("body", "hello"))
            .withHeader("x-tech", "remove");

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              assertEquals("hello", m.getPayload());
              assertFalse(m.getMetadata().containsKey("x-tech"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testUnwrapStrictModeFailure() {
    final Map<String, Object> config =
        Map.of(
            "mode", "UNWRAP",
            "envelopeKey", "missing",
            "strictMode", true);

    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of("foo", "bar"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              assertEquals("error", m.getSourcePort());
              assertTrue(((String) m.getMetadata().get("failureReason")).contains("Unwrap failed"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testMetadataSafeListPreservation() {
    final Map<String, Object> config = Map.of("mode", "UNWRAP");
    final String traceId = UUID.randomUUID().toString();
    final Message<?> msg =
        new DefaultMessage<>(
            UUID.randomUUID().toString(),
            traceId,
            null,
            null,
            0L,
            null,
            Map.of("foo", "bar"),
            java.util.List.of("node1"),
            null,
            0,
            0,
            0,
            false,
            null,
            null,
            null,
            0,
            "payload",
            java.time.Instant.now(),
            null,
            null);

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(ctx -> ctx.put("nodeId", "node2")))
        .expectNextMatches(
            m -> {
              assertEquals(traceId, m.getTraceId());
              assertTrue(m.getMessageHistory().contains("node1"));
              assertTrue(m.getMessageHistory().contains("node2"));
              assertFalse(m.getMetadata().containsKey("foo"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testType() {
    assertEquals("ENVELOPE", processor.getType());
  }

  @Test
  void testUiDesign() {
    assertTrue(processor.getUiDesign().isPresent());
    assertEquals(140, processor.getUiDesign().get().width());
    assertEquals(80, processor.getUiDesign().get().height());
    assertTrue(processor.getUiDesign().get().html().contains("Envelope"));
  }

  @Test
  void testOutputPorts() {
    assertEquals(java.util.List.of("default", "error"), processor.getOutputPorts(Map.of()));
  }

  @Test
  void testValidateConfig() {
    StepVerifier.create(processor.validateConfig(Map.of("mode", "WRAP"))).verifyComplete();
    StepVerifier.create(processor.validateConfig(Map.of("mode", "UNWRAP"))).verifyComplete();
    StepVerifier.create(processor.validateConfig(Map.of("mode", "INVALID")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }
}
