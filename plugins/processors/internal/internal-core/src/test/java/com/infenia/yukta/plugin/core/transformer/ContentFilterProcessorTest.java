// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.transformer;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class ContentFilterProcessorTest {

  private ContentFilterProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new ContentFilterProcessor();
  }

  @Test
  void testIncludePayload() {
    final Map<String, Object> config =
        Map.of("mode", "INCLUDE", "paths", List.of("user.name", "user.email"));
    final Map<String, Object> payload =
        Map.of(
            "user", Map.of("name", "John", "email", "john@example.com", "age", 30),
            "system", Map.of("id", "123"));
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), payload);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              Map<String, Object> result = (Map<String, Object>) m.getPayload();
              assertEquals(1, result.size());
              Map<String, Object> user = (Map<String, Object>) result.get("user");
              assertEquals(2, user.size());
              assertEquals("John", user.get("name"));
              assertEquals("john@example.com", user.get("email"));
              assertFalse(user.containsKey("age"));
              assertFalse(result.containsKey("system"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testIncludeWithSpel() {
    final Map<String, Object> config =
        Map.of("mode", "INCLUDE", "paths", List.of("#payload.user.name"));
    final Map<String, Object> payload = Map.of("user", Map.of("name", "John", "age", 30));
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), payload);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              Map<String, Object> result = (Map<String, Object>) m.getPayload();
              Map<String, Object> user = (Map<String, Object>) result.get("user");
              assertEquals("John", user.get("name"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testExcludePayload() {
    final Map<String, Object> config =
        Map.of("mode", "EXCLUDE", "paths", List.of("user.age", "system.id"));
    final Map<String, Object> payload =
        Map.of(
            "user", Map.of("name", "John", "age", 30),
            "system", Map.of("id", "123", "version", "1.0"));
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), payload);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              Map<String, Object> result = (Map<String, Object>) m.getPayload();
              Map<String, Object> user = (Map<String, Object>) result.get("user");
              assertEquals("John", user.get("name"));
              assertFalse(user.containsKey("age"));
              Map<String, Object> system = (Map<String, Object>) result.get("system");
              assertEquals("1.0", system.get("version"));
              assertFalse(system.containsKey("id"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testIncludeMetadata() {
    final Map<String, Object> config =
        Map.of("mode", "INCLUDE", "paths", List.of("metadata.appId", "user.name"));
    final Map<String, Object> payload = Map.of("user", Map.of("name", "John"));
    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), payload)
            .withMetadata(Map.of("appId", "YUKTA", "secret", "PASS"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              assertEquals("YUKTA", m.getMetadata().get("appId"));
              assertFalse(m.getMetadata().containsKey("secret"));
              assertTrue(m.getMetadata().containsKey("traceId")); // Technical header preserved
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testIncludeWithSpelMetadata() {
    final Map<String, Object> config =
        Map.of("mode", "INCLUDE", "paths", List.of("#metadata.appId", "user.name"));
    final Map<String, Object> payload = Map.of("user", Map.of("name", "John"));
    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), payload)
            .withMetadata(Map.of("appId", "YUKTA", "secret", "PASS"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              assertEquals("YUKTA", m.getMetadata().get("appId"));
              assertFalse(m.getMetadata().containsKey("secret"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testFlatten() {
    final Map<String, Object> config =
        Map.of(
            "mode", "INCLUDE", "paths", List.of("user.name", "user.address.city"), "flatten", true);
    final Map<String, Object> payload =
        Map.of(
            "user", Map.of("name", "John", "address", Map.of("city", "New York", "zip", "10001")));
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), payload);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              Map<String, Object> result = (Map<String, Object>) m.getPayload();
              assertEquals("John", result.get("user.name"));
              assertEquals("New York", result.get("user.address.city"));
              assertEquals(2, result.size());
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testStrictModeMissingPath() {
    final Map<String, Object> config =
        Map.of("mode", "INCLUDE", "paths", List.of("user.missing"), "strictMode", true);
    final Map<String, Object> payload = Map.of("user", Map.of("name", "John"));
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), payload);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectError(WorkflowExecutionException.class)
        .verify();
  }

  @Test
  void testErrorPortRouting() {
    final Map<String, Object> config =
        Map.of(
            "mode",
            "INCLUDE",
            "paths",
            List.of("user.missing"),
            "strictMode",
            true,
            "errorPort",
            "error-out");
    final Map<String, Object> payload = Map.of("user", Map.of("name", "John"));
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), payload);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              assertEquals("error-out", m.getSourcePort());
              assertEquals("Content Filter failed", m.getFailureReason());
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testStreamingPayload() {
    final Map<String, Object> config = Map.of("mode", "INCLUDE", "paths", List.of("name"));
    final List<Map<String, Object>> payload =
        List.of(Map.of("name", "John", "age", 30), Map.of("name", "Jane", "age", 25));
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), payload);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              assertEquals("John", ((Map) m.getPayload()).get("name"));
              assertFalse(((Map) m.getPayload()).containsKey("age"));
              return true;
            })
        .expectNextMatches(
            m -> {
              assertEquals("Jane", ((Map) m.getPayload()).get("name"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testFluxPayload() {
    final Map<String, Object> config = Map.of("mode", "INCLUDE", "paths", List.of("name"));
    final Flux<Map<String, Object>> payload =
        Flux.just(Map.of("name", "John", "age", 30), Map.of("name", "Jane", "age", 25));
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), payload);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              assertEquals("John", ((Map) m.getPayload()).get("name"));
              return true;
            })
        .expectNextMatches(
            m -> {
              assertEquals("Jane", ((Map) m.getPayload()).get("name"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testPrimitiveArrayPayload() {
    final Map<String, Object> config =
        Map.of("mode", "INCLUDE", "paths", List.of("payload"), "strictMode", false);
    final int[] payload = {1, 2, 3};
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), payload);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              System.out.println("Item: " + m.getPayload());
              return true;
            })
        .expectNextMatches(m -> true)
        .expectNextMatches(m -> true)
        .verifyComplete();
  }

  @Test
  void testNullPayload() {
    final Map<String, Object> config =
        Map.of("mode", "INCLUDE", "paths", List.of("something"), "strictMode", false);
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), null);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m -> {
              System.out.println("Null result: " + m.getPayload());
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testType() {
    assertEquals("CONTENT-FILTER", processor.getType());
  }

  @Test
  void testUiDesign() {
    assertTrue(processor.getUiDesign().isPresent());
    assertTrue(processor.getUiDesign().get().html().contains("C-Filter"));
  }
}
