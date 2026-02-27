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
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.util.VariableResolver;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class ConstantProcessorTest {

  @Mock private VariableResolver resolver;
  private ConstantProcessor processor;
  private UUID traceId;

  @BeforeEach
  void setUp() {
    processor = new ConstantProcessor(resolver);
    traceId = UUID.randomUUID();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testEnrichPayload() {
    when(resolver.isStatic(any())).thenReturn(true);
    when(resolver.resolve("v1")).thenReturn(Mono.just("v1"));
    when(resolver.resolve("v2")).thenReturn(Mono.just("v2"));

    final Map<String, Object> config =
        Map.of(
            "mode", "ENRICH",
            "target", "PAYLOAD",
            "variables", Map.of("a.b", "v1", "c", "v2"));

    final Message message = Message.create(traceId, Map.of("a", Map.of("x", 1)));

    processor.initialize(config).block();

    StepVerifier.create(processor.process(Flux.just(message), config))
        .assertNext(
            result -> {
              Map<String, Object> payload = (Map<String, Object>) result.payload();
              assertEquals(1, ((Map<String, Object>) payload.get("a")).get("x"));
              assertEquals("v1", ((Map<String, Object>) payload.get("a")).get("b"));
              assertEquals("v2", payload.get("c"));
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testReplacePayload() {
    when(resolver.isStatic(any())).thenReturn(true);
    when(resolver.resolve("v1")).thenReturn(Mono.just("v1"));

    final Map<String, Object> config =
        Map.of(
            "mode", "REPLACE",
            "target", "PAYLOAD",
            "variables", Map.of("a", "v1"));

    final Message message = Message.create(traceId, Map.of("existing", "data"));

    processor.initialize(config).block();

    StepVerifier.create(processor.process(Flux.just(message), config))
        .assertNext(
            result -> {
              Map<String, Object> payload = (Map<String, Object>) result.payload();
              assertEquals(1, payload.size());
              assertEquals("v1", payload.get("a"));
            })
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testEnrichMetadata() {
    when(resolver.isStatic(any())).thenReturn(true);
    when(resolver.resolve("v1")).thenReturn(Mono.just("v1"));

    final Map<String, Object> config =
        Map.of(
            "mode", "ENRICH",
            "target", "METADATA",
            "variables", Map.of("metaKey", "v1"));

    final Message message = Message.create(traceId, "data");

    processor.initialize(config).block();

    StepVerifier.create(processor.process(Flux.just(message), config))
        .assertNext(
            result -> {
              assertEquals("v1", result.metadata().get("metaKey"));
              assertEquals("data", result.payload());
            })
        .verifyComplete();
  }

  @Test
  void testCollisionPolicyFail() {
    when(resolver.isStatic(any())).thenReturn(true);
    when(resolver.resolve("v1")).thenReturn(Mono.just("v1"));

    final Map<String, Object> config =
        Map.of(
            "mode", "ENRICH",
            "collisionPolicy", "FAIL",
            "variables", Map.of("a", "v1"));

    final Message message = Message.create(traceId, Map.of("a", "existing"));

    processor.initialize(config).block();

    StepVerifier.create(processor.process(Flux.just(message), config))
        .expectError(IllegalStateException.class)
        .verify();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testCollisionPolicySkip() {
    when(resolver.isStatic(any())).thenReturn(true);
    when(resolver.resolve("v1")).thenReturn(Mono.just("v1"));

    final Map<String, Object> config =
        Map.of(
            "mode", "ENRICH",
            "collisionPolicy", "SKIP",
            "variables", Map.of("a", "v1"));

    final Message message = Message.create(traceId, Map.of("a", "existing"));

    processor.initialize(config).block();

    StepVerifier.create(processor.process(Flux.just(message), config))
        .assertNext(
            result -> {
              Map<String, Object> payload = (Map<String, Object>) result.payload();
              assertEquals("existing", payload.get("a"));
            })
        .verifyComplete();
  }
}
