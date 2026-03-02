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
package com.infenia.yukta.plugin.core.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

class RecipientListProcessorTest {

  private RecipientListProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new RecipientListProcessor();
  }

  @Test
  void testStaticMode() {
    final Map<String, Object> config =
        Map.of("mode", "STATIC", "recipients", List.of("port1", "port2"));

    final Message msg = DefaultMessage.create(UUID.randomUUID(), "payload");

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(Context.of("nodeId", "test-node")))
        .expectNextMatches(
            m -> "port1".equals(m.getSourcePort()) && m.getMessageHistory().contains("test-node"))
        .expectNextMatches(
            m -> "port2".equals(m.getSourcePort()) && m.getMessageHistory().contains("test-node"))
        .verifyComplete();
  }

  @Test
  void testDynamicModeList() {
    final Map<String, Object> config =
        Map.of(
            "mode", "DYNAMIC",
            "expression", "payload.destinations");

    final Message msg =
        DefaultMessage.create(UUID.randomUUID(), Map.of("destinations", List.of("p1", "p2")));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "p1".equals(m.getSourcePort()))
        .expectNextMatches(m -> "p2".equals(m.getSourcePort()))
        .verifyComplete();
  }

  @Test
  void testDynamicModeSingleString() {
    final Map<String, Object> config =
        Map.of(
            "mode", "DYNAMIC",
            "expression", "payload.target");

    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of("target", "p1"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "p1".equals(m.getSourcePort()))
        .verifyComplete();
  }

  @Test
  void testExternalModePayload() {
    final Map<String, Object> config =
        Map.of(
            "mode", "EXTERNAL",
            "sourceField", "payload.user.groups");

    final Message msg =
        DefaultMessage.create(UUID.randomUUID(), Map.of("user", Map.of("groups", List.of("g1"))));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "g1".equals(m.getSourcePort()))
        .verifyComplete();
  }

  @Test
  void testExternalModeMetadata() {
    final Map<String, Object> config =
        Map.of(
            "mode", "EXTERNAL",
            "sourceField", "metadata.recipients");

    final Message msg =
        DefaultMessage.create(UUID.randomUUID(), "data")
            .withMetadata(Map.of("recipients", List.of("m1")));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "m1".equals(m.getSourcePort()))
        .verifyComplete();
  }

  @Test
  void testZeroMatchesStatic() {
    final Map<String, Object> config = Map.of("mode", "STATIC", "recipients", List.of());

    final Message msg = DefaultMessage.create(UUID.randomUUID(), "data");

    StepVerifier.create(processor.process(Flux.just(msg), config)).verifyComplete();
  }

  @Test
  void testZeroMatchesDynamic() {
    final Map<String, Object> config =
        Map.of(
            "mode", "DYNAMIC",
            "expression", "payload.destinations");

    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of("destinations", List.of()));

    StepVerifier.create(processor.process(Flux.just(msg), config)).verifyComplete();
  }

  @Test
  void testSequentialDispatch() {
    final Map<String, Object> config =
        Map.of("mode", "STATIC", "recipients", List.of("p1", "p2"), "parallel", false);

    final Message msg = DefaultMessage.create(UUID.randomUUID(), "data");

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "p1".equals(m.getSourcePort()))
        .expectNextMatches(m -> "p2".equals(m.getSourcePort()))
        .verifyComplete();
  }

  @Test
  void testErrorPortOnFailure() {
    final Map<String, Object> config =
        Map.of(
            "mode", "DYNAMIC",
            "expression", "1 / 0", // Causes ArithmeticException
            "errorPort", "error_port");

    final Message msg = DefaultMessage.create(UUID.randomUUID(), "data");

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m ->
                "error_port".equals(m.getSourcePort())
                    && m.getFailureReason().contains("Recipient computation failed"))
        .verifyComplete();
  }

  @Test
  void testStrictModeError() {
    final Map<String, Object> config =
        Map.of(
            "mode", "DYNAMIC",
            "expression", "1 / 0",
            "strictMode", true);

    final Message msg = DefaultMessage.create(UUID.randomUUID(), "data");

    StepVerifier.create(processor.process(Flux.just(msg), config)).expectError().verify();
  }

  @Test
  void testGetOutputPorts() {
    // Static mode
    final Map<String, Object> staticConfig =
        Map.of(
            "mode", "STATIC",
            "recipients", List.of("p1", "p2"),
            "errorPort", "err");
    final List<String> staticPorts = processor.getOutputPorts(staticConfig);
    assertEquals(3, staticPorts.size());
    assertTrue(staticPorts.containsAll(List.of("p1", "p2", "err")));

    // Dynamic mode
    final Map<String, Object> dynamicConfig = Map.of("mode", "DYNAMIC");
    assertEquals(List.of("*"), processor.getOutputPorts(dynamicConfig));
  }

  @Test
  void testValidateConfig() {
    // Valid Static
    StepVerifier.create(
            processor.validateConfig(Map.of("mode", "STATIC", "recipients", List.of("p1"))))
        .verifyComplete();

    // Invalid Static
    StepVerifier.create(processor.validateConfig(Map.of("mode", "STATIC"))).expectError().verify();

    // Valid Dynamic
    StepVerifier.create(processor.validateConfig(Map.of("mode", "DYNAMIC", "expression", "exp")))
        .verifyComplete();

    // Invalid Dynamic
    StepVerifier.create(processor.validateConfig(Map.of("mode", "DYNAMIC"))).expectError().verify();
  }

  @Test
  void testUiDesign() {
    final Optional<UiDesign> design = processor.getUiDesign();
    assertTrue(design.isPresent());
    assertTrue(design.get().html().contains("Recipient List"));
  }
}
