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
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class MapperProcessorTest {

  private MapperProcessor processor;
  private UUID traceId;

  @BeforeEach
  void setUp() {
    processor = new MapperProcessor();
    traceId = UUID.randomUUID();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testProjectionMode() {
    final Map<String, Object> config =
        Map.of(
            "mode",
            "PROJECTION",
            "mapping",
            Map.of(
                "fullName", "payload.firstName + ' ' + payload.lastName",
                "nested.age", "payload.age"),
            "dropOriginal",
            true);

    final Message<?> message =
        DefaultMessage.create(
            traceId,
            Map.of(
                "firstName", "John",
                "lastName", "Doe",
                "age", 30));

    processor.initialize(config).block();

    StepVerifier.create(processor.process(Flux.just(message), config))
        .assertNext(
            result -> {
              assertTrue(result.getPayload() instanceof Map);
              final Map<String, Object> payload = (Map<String, Object>) result.getPayload();
              assertEquals("John Doe", payload.get("fullName"));
              assertTrue(payload.get("nested") instanceof Map);
              assertEquals(30, ((Map<String, Object>) payload.get("nested")).get("age"));
              assertEquals(message.getMessageId(), result.getMessageId());
              assertEquals(traceId.toString(), result.getTraceId());
            })
        .verifyComplete();
  }

  @Test
  void testTemplateModeString() {
    final Map<String, Object> config =
        Map.of(
            "mode", "TEMPLATE",
            "mapping", "Hello {{payload.name}}!",
            "dropOriginal", true);

    final Message<?> message = DefaultMessage.create(traceId, Map.of("name", "World"));

    processor.initialize(config).block();

    StepVerifier.create(processor.process(Flux.just(message), config))
        .assertNext(
            result -> {
              assertEquals("Hello World!", result.getPayload());
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testTemplateModeMap() {
    final Map<String, Object> config =
        Map.of(
            "mode",
            "TEMPLATE",
            "mapping",
            Map.of("greeting", "Hello {{payload.name}}!"),
            "dropOriginal",
            true);

    final Message<?> message = DefaultMessage.create(traceId, Map.of("name", "World"));

    processor.initialize(config).block();

    StepVerifier.create(processor.process(Flux.just(message), config))
        .assertNext(
            result -> {
              assertTrue(result.getPayload() instanceof Map);
              final Map<String, Object> payload = (Map<String, Object>) result.getPayload();
              assertEquals("Hello World!", payload.get("greeting"));
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testScriptMode() {
    final Map<String, Object> config =
        Map.of(
            "mode", "SCRIPT",
            "mapping", "({ result: payload.a + payload.b })",
            "dropOriginal", true);

    final Message<?> message = DefaultMessage.create(traceId, Map.of("a", 10, "b", 20));

    processor.initialize(config).block();

    StepVerifier.create(processor.process(Flux.just(message), config))
        .assertNext(
            result -> {
              assertTrue(result.getPayload() instanceof Map);
              final Map<String, Object> payload = (Map<String, Object>) result.getPayload();
              assertEquals(30.0, ((Number) payload.get("result")).doubleValue());
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testEnrichMode() {
    final Map<String, Object> config =
        Map.of("mode", "PROJECTION", "mapping", Map.of("extra", "'new'"), "dropOriginal", false);

    final Message<?> message = DefaultMessage.create(traceId, Map.of("existing", "value"));

    processor.initialize(config).block();

    StepVerifier.create(processor.process(Flux.just(message), config))
        .assertNext(
            result -> {
              final Map<String, Object> payload = (Map<String, Object>) result.getPayload();
              assertEquals("value", payload.get("existing"));
              assertEquals("new", payload.get("extra"));
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testStrictModeFalse() {
    final Map<String, Object> config =
        Map.of(
            "mode",
            "PROJECTION",
            "mapping",
            Map.of(
                "present", "'ok'",
                "absent", "payload.missingField"),
            "strictMode",
            false,
            "dropOriginal",
            true);

    final Message<?> message = DefaultMessage.create(traceId, Map.of("some", "data"));

    processor.initialize(config).block();

    StepVerifier.create(processor.process(Flux.just(message), config))
        .assertNext(
            result -> {
              final Map<String, Object> payload = (Map<String, Object>) result.getPayload();
              assertEquals("ok", payload.get("present"));
              assertTrue(!payload.containsKey("absent"));
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testScriptModeArrayAndNested() {
    final Map<String, Object> config =
        Map.of(
            "mode",
            "SCRIPT",
            "mapping",
            "({ list: [1, 2, { x: 'y' }], nested: { a: 1 } })",
            "dropOriginal",
            true);

    final Message<?> message = DefaultMessage.create(traceId, Map.of());

    processor.initialize(config).block();

    StepVerifier.create(processor.process(Flux.just(message), config))
        .assertNext(
            result -> {
              final Map<String, Object> payload = (Map<String, Object>) result.getPayload();
              final List<Object> list = (List<Object>) payload.get("list");
              assertEquals(3, list.size());
              assertEquals(1, ((Number) list.get(0)).intValue());
              assertEquals("y", ((Map<String, Object>) list.get(2)).get("x"));
              assertEquals(
                  1, ((Number) ((Map<String, Object>) payload.get("nested")).get("a")).intValue());
            })
        .verifyComplete();
  }

  @Test
  void testTemplateModeError() {
    // Template not in cache
    final Map<String, Object> config =
        Map.of(
            "mode", "TEMPLATE",
            "mapping", "not cached");
    final Message<?> message = DefaultMessage.create(traceId, Map.of());

    // Skip initialize to trigger "Template not found in cache"
    StepVerifier.create(processor.process(Flux.just(message), config)).expectError().verify();
  }

  @Test
  void testScriptModeError() {
    final Map<String, Object> config =
        Map.of(
            "mode", "SCRIPT",
            "mapping", "throw new Error('fail')",
            "strictMode", true);
    final Message<?> message = DefaultMessage.create(traceId, Map.of());
    processor.initialize(config).block();

    StepVerifier.create(processor.process(Flux.just(message), config)).expectError().verify();
  }

  @Test
  void testProjectionDeepNesting() {
    final Map<String, Object> config =
        Map.of("mode", "PROJECTION", "mapping", Map.of("a.b.c.d", "payload.val"));
    final Message<?> message = DefaultMessage.create(traceId, Map.of("val", 42));
    processor.initialize(config).block();

    StepVerifier.create(processor.process(Flux.just(message), config))
        .assertNext(
            result -> {
              Map<String, Object> p = (Map<String, Object>) result.getPayload();
              assertEquals(
                  42,
                  ((Map<String, Object>)
                          ((Map<String, Object>) ((Map<String, Object>) p.get("a")).get("b"))
                              .get("c"))
                      .get("d"));
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testTemplateMapFailure() {
    final Map<String, Object> mapping = Map.of("a", "{{fail}}");
    final Map<String, Object> config =
        Map.of("mode", "TEMPLATE", "mapping", mapping, "strictMode", false);
    // Note: handlebars doesn't necessarily throw on missing field unless configured,
    // but we can test our cache missing or IO exception if we mock, but here it's easier to test
    // non-strict behavior.

    final Message<?> message = DefaultMessage.create(traceId, Map.of());
    processor.initialize(config).block();

    StepVerifier.create(processor.process(Flux.just(message), config))
        .assertNext(
            result -> {
              assertTrue(result.getPayload() instanceof Map);
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testSetNestedValueOverwrite() {
    final Map<String, Object> config =
        Map.of("mode", "PROJECTION", "mapping", Map.of("a.b", "'val'"), "dropOriginal", false);
    // 'a' is not a map initially
    final Message<?> message = DefaultMessage.create(traceId, Map.of("a", 1));
    processor.initialize(config).block();

    StepVerifier.create(processor.process(Flux.just(message), config))
        .assertNext(
            result -> {
              Map<String, Object> p = (Map<String, Object>) result.getPayload();
              assertTrue(p.get("a") instanceof Map);
              assertEquals("val", ((Map<String, Object>) p.get("a")).get("b"));
            })
        .verifyComplete();
  }

  @Test
  void testDetachValueBasic() {
    // We already test numbers, strings, maps via testScriptMode
    // Testing null return from script
    final Map<String, Object> config =
        Map.of(
            "mode", "SCRIPT",
            "mapping", "null",
            "dropOriginal", true);
    final Message<?> message = DefaultMessage.create(traceId, Map.of());
    processor.initialize(config).block();

    StepVerifier.create(processor.process(Flux.just(message), config))
        .assertNext(
            result -> {
              assertEquals(null, result.getPayload());
            })
        .verifyComplete();
  }

  @Test
  void testGetType() {
    assertEquals("MAPPER", processor.getType());
  }

  @Test
  void testScriptModeNonStrictFailure() {
    final Map<String, Object> config =
        Map.of(
            "mode",
            "SCRIPT",
            "mapping",
            "throw new Error('fail')",
            "strictMode",
            false,
            "dropOriginal",
            true);
    final Message<?> message = DefaultMessage.create(traceId, Map.of("a", 1));
    processor.initialize(config).block();

    StepVerifier.create(processor.process(Flux.just(message), config))
        .assertNext(
            result -> {
              assertEquals(Map.of(), result.getPayload());
            })
        .verifyComplete();
  }

  @Test
  void testScriptModeNonStrictEnrichFailure() {
    final Map<String, Object> config =
        Map.of(
            "mode",
            "SCRIPT",
            "mapping",
            "throw new Error('fail')",
            "strictMode",
            false,
            "dropOriginal",
            false);
    final Map<String, Object> originalPayload = Map.of("a", 1);
    final Message<?> message = DefaultMessage.create(traceId, originalPayload);
    processor.initialize(config).block();

    StepVerifier.create(processor.process(Flux.just(message), config))
        .assertNext(
            result -> {
              assertEquals(originalPayload, result.getPayload());
            })
        .verifyComplete();
  }

  @Test
  void testInitializeModes() {
    // PROJECTION
    processor.initialize(Map.of("mode", "PROJECTION", "mapping", Map.of("k", "v"))).block();
    // TEMPLATE Map
    processor.initialize(Map.of("mode", "TEMPLATE", "mapping", Map.of("k", "{{v}}"))).block();
    // TEMPLATE String
    processor.initialize(Map.of("mode", "TEMPLATE", "mapping", "{{v}}")).block();
    // SCRIPT
    processor.initialize(Map.of("mode", "SCRIPT", "mapping", "payload.x")).block();
  }

  @Test
  void testValidateConfig() {
    StepVerifier.create(processor.validateConfig(Map.of()))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(processor.validateConfig(Map.of("mode", "INVALID")))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(processor.validateConfig(Map.of("mode", "PROJECTION")))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(processor.validateConfig(Map.of("mode", "PROJECTION", "mapping", Map.of())))
        .verifyComplete();
  }

  @Test
  void testDetachValueBooleanAndNull() {
    final Map<String, Object> config =
        Map.of(
            "mode", "SCRIPT",
            "mapping", "({ b: true, n: null })",
            "dropOriginal", true);
    final Message<?> message = DefaultMessage.create(traceId, Map.of());
    processor.initialize(config).block();

    StepVerifier.create(processor.process(Flux.just(message), config))
        .assertNext(
            result -> {
              Map<String, Object> p = (Map<String, Object>) result.getPayload();
              assertEquals(true, p.get("b"));
              assertEquals(null, p.get("n"));
            })
        .verifyComplete();
  }

  @Test
  void testShutdown() {
    StepVerifier.create(processor.shutdown(Map.of())).verifyComplete();
  }

  @Test
  void testInitializeError() {
    // TEMPLATE compilation failure
    final Map<String, Object> config =
        Map.of(
            "mode", "TEMPLATE",
            "mapping", "{{#if}} unclosed");
    try {
      processor.initialize(config).block();
    } catch (Exception e) {
      assertTrue(e.getMessage().contains("compile Handlebars template"));
    }
  }

  @Test
  void testMetadataPreservation() {
    final Map<String, Object> config =
        Map.of("mode", "PROJECTION", "mapping", Map.of("a", "1"), "dropOriginal", true);

    final Map<String, Object> metadata = Map.of("key", "val");
    final Message<?> message =
        new DefaultMessage<>(
            UUID.randomUUID().toString(),
            traceId.toString(),
            null,
            null,
            0L,
            null,
            metadata,
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
            "payload",
            java.time.Instant.now(),
            "port1",
            "node1");

    processor.initialize(config).block();

    StepVerifier.create(processor.process(Flux.just(message), config))
        .assertNext(
            result -> {
              assertEquals(metadata, result.getMetadata());
              assertEquals("port1", result.getSourcePort());
              assertEquals("node1", result.getSourceNodeId());
              assertEquals(traceId.toString(), result.getTraceId());
            })
        .verifyComplete();
  }
}
