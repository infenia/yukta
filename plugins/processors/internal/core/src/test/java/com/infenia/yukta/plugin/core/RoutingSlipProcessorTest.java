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

import com.infenia.yukta.plugin.DefaultMessage;
import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.UiDesign;
import com.infenia.yukta.plugin.WorkflowExecutionException;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

class RoutingSlipProcessorTest {

  private RoutingSlipProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new RoutingSlipProcessor();
  }

  @Test
  void testStaticMode() {
    final Map<String, Object> config =
        Map.of("mode", "STATIC", "routingTable", List.of("port1", "port2", "port3"));

    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "payload");

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(Context.of("nodeId", "test-node")))
        .expectNextMatches(
            m ->
                "port1".equals(m.getSourcePort())
                    && m.getMessageHistory().contains("test-node")
                    && List.of("port1", "port2", "port3")
                        .equals(m.getMetadata().get("yukta.routing_slip"))
                    && Integer.valueOf(0).equals(m.getMetadata().get("yukta.routing_slip_index")))
        .verifyComplete();
  }

  @Test
  void testDynamicMode() {
    final Map<String, Object> config =
        Map.of(
            "mode", "DYNAMIC",
            "expression", "payload.steps",
            "slipPath", "custom.slip");

    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), Map.of("steps", List.of("stepA", "stepB")));

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(Context.of("nodeId", "test-node")))
        .expectNextMatches(
            m ->
                "stepA".equals(m.getSourcePort())
                    && List.of("stepA", "stepB").equals(m.getMetadata().get("custom.slip"))
                    && Integer.valueOf(0).equals(m.getMetadata().get("yukta.routing_slip_index")))
        .verifyComplete();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testCoercionSingleString() {
    final Map<String, Object> config =
        Map.of(
            "mode", "DYNAMIC",
            "expression", "'singlePort'");

    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m ->
                "singlePort".equals(m.getSourcePort())
                    && List.of("singlePort").equals(m.getMetadata().get("yukta.routing_slip")))
        .verifyComplete();
  }

  @Test
  void testCoercionArray() {
    final Map<String, Object> config =
        Map.of(
            "mode", "DYNAMIC",
            "expression", "new String[]{'a', 'b'}");

    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m ->
                "a".equals(m.getSourcePort())
                    && List.of("a", "b").equals(m.getMetadata().get("yukta.routing_slip")))
        .verifyComplete();
  }

  @Test
  void testErrorPortOnFailure() {
    final Map<String, Object> config =
        Map.of(
            "mode", "DYNAMIC",
            "expression", "1 / 0",
            "errorPort", "error-port");

    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m ->
                "error-port".equals(m.getSourcePort())
                    && "Routing slip computation failed".equals(m.getFailureReason()))
        .verifyComplete();
  }

  @Test
  void testStrictModeOnFailure() {
    final Map<String, Object> config =
        Map.of(
            "mode", "DYNAMIC",
            "expression", "1 / 0",
            "strictMode", true);

    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectError(WorkflowExecutionException.class)
        .verify();
  }

  @Test
  void testNonStrictModeOnFailure() {
    final Map<String, Object> config =
        Map.of(
            "mode", "DYNAMIC",
            "expression", "1 / 0",
            "strictMode", false);

    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");

    StepVerifier.create(processor.process(Flux.just(msg), config)).verifyComplete();
  }

  @Test
  void testEmptySlipFailure() {
    final Map<String, Object> config =
        Map.of(
            "mode", "DYNAMIC",
            "expression", "payload.nothing",
            "strictMode", true);

    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of());

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectError(WorkflowExecutionException.class)
        .verify();
  }

  @Test
  void testGetOutputPorts() {
    // Static
    final Map<String, Object> staticConfig =
        Map.of(
            "mode", "STATIC",
            "routingTable", List.of("p1", "p2"),
            "errorPort", "err");
    final List<String> staticPorts = processor.getOutputPorts(staticConfig);
    assertEquals(3, staticPorts.size());
    assertTrue(staticPorts.containsAll(List.of("p1", "p2", "err")));

    // Dynamic
    final Map<String, Object> dynamicConfig = Map.of("mode", "DYNAMIC");
    assertEquals(List.of("*"), processor.getOutputPorts(dynamicConfig));
  }

  @Test
  void testValidateConfig() {
    // Valid static
    StepVerifier.create(
            processor.validateConfig(Map.of("mode", "STATIC", "routingTable", List.of("p1"))))
        .verifyComplete();

    // Invalid static
    StepVerifier.create(processor.validateConfig(Map.of("mode", "STATIC"))).expectError().verify();

    // Valid dynamic
    StepVerifier.create(processor.validateConfig(Map.of("mode", "DYNAMIC", "expression", "exp")))
        .verifyComplete();

    // Invalid dynamic
    StepVerifier.create(processor.validateConfig(Map.of("mode", "DYNAMIC"))).expectError().verify();
  }

  @Test
  void testUiDesign() {
    final Optional<UiDesign> design = processor.getUiDesign();
    assertTrue(design.isPresent());
    assertTrue(design.get().html().contains("Routing Slip"));
    assertEquals(140, design.get().width());
    assertEquals(80, design.get().height());
  }
}
