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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.infenia.yukta.plugin.DefaultMessage;
import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.PluginMetricsReporter;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

class FilterProcessorTest {

  private FilterProcessor processor;
  private PluginMetricsReporter reporter;

  @BeforeEach
  void setUp() {
    reporter = mock(PluginMetricsReporter.class);
    StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    beanFactory.addBean("reporter", reporter);
    processor = new FilterProcessor(beanFactory.getBeanProvider(PluginMetricsReporter.class));
  }

  @Test
  void testSpelMatch() {
    final Map<String, Object> config = Map.of("condition", "payload.status == 'ACTIVE'");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of("status", "ACTIVE"));

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(ctx -> ctx.put("nodeId", "test-node")))
        .expectNextMatches(
            m -> {
              assertEquals(msg.getPayload(), m.getPayload());
              assertEquals("default", m.getSourcePort());
              assertTrue(m.getMessageHistory().contains("test-node"));
              return true;
            })
        .verifyComplete();

    verify(reporter).incrementFilterCount("test-node", "MATCH");
  }

  @Test
  void testSpelVariablesMatch() {
    final Map<String, Object> config =
        Map.of("condition", "#payload.status == 'ACTIVE' && #metadata.source == 'REST'");
    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), Map.of("status", "ACTIVE"))
            .withMetadata(Map.of("source", "REST"));

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(ctx -> ctx.put("nodeId", "test-node")))
        .expectNextMatches(m -> "default".equals(m.getSourcePort()))
        .verifyComplete();

    verify(reporter).incrementFilterCount("test-node", "MATCH");
  }

  @Test
  void testSpelNoMatch() {
    final Map<String, Object> config = Map.of("condition", "payload.status == 'ACTIVE'");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of("status", "INACTIVE"));

    StepVerifier.create(processor.process(Flux.just(msg), config)).verifyComplete();
  }

  @Test
  void testSimpleEngineMatch() {
    final Map<String, Object> config =
        Map.of(
            "condition", "payload.status == 'ACTIVE'",
            "engine", "SIMPLE");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of("status", "ACTIVE"));

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(ctx -> ctx.put("nodeId", "test-node")))
        .expectNextMatches(
            m -> {
              assertEquals(msg.getPayload(), m.getPayload());
              assertEquals("default", m.getSourcePort());
              assertTrue(m.getMessageHistory().contains("test-node"));
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testDiscardPort() {
    final Map<String, Object> config =
        Map.of(
            "condition", "payload.status == 'ACTIVE'",
            "discardPort", "dead-letter");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of("status", "INACTIVE"));

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(ctx -> ctx.put("nodeId", "test-node")))
        .expectNextMatches(
            m -> {
              assertEquals("dead-letter", m.getSourcePort());
              assertTrue(m.getMessageHistory().contains("test-node"));
              return true;
            })
        .verifyComplete();

    verify(reporter).incrementFilterCount("test-node", "DISCARD");
  }

  @Test
  void testStrictModeError() {
    final Map<String, Object> config =
        Map.of("condition", "payload.invalidField > 100", "strictMode", true);
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of("status", "ACTIVE"));

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(ctx -> ctx.put("nodeId", "test-node")))
        .expectErrorMatches(e -> e instanceof com.infenia.yukta.plugin.FilterEvaluationException)
        .verify();

    verify(reporter).incrementFilterCount("test-node", "ERROR");
  }

  @Test
  void testNonStrictModeError() {
    final Map<String, Object> config =
        Map.of("condition", "payload.invalidField > 100", "strictMode", false);
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of("status", "ACTIVE"));

    StepVerifier.create(processor.process(Flux.just(msg), config)).verifyComplete();
  }

  @Test
  void testType() {
    assertEquals("FILTER", processor.getType());
  }

  @Test
  void testUiDesign() {
    assertTrue(processor.getUiDesign().isPresent());
    assertEquals(140, processor.getUiDesign().get().width());
    assertEquals(80, processor.getUiDesign().get().height());
    assertTrue(processor.getUiDesign().get().html().contains("svg"));
  }

  @Test
  void testOutputPorts() {
    assertEquals(List.of("default"), processor.getOutputPorts(Map.of()));
    assertEquals(
        List.of("default", "discard"), processor.getOutputPorts(Map.of("discardPort", "discard")));
  }

  @Test
  void testValidateConfig() {
    StepVerifier.create(processor.validateConfig(Map.of("condition", "true"))).verifyComplete();

    StepVerifier.create(processor.validateConfig(Map.of()))
        .expectError(IllegalArgumentException.class)
        .verify();

    StepVerifier.create(processor.validateConfig(Map.of("condition", "true", "engine", "INVALID")))
        .expectError(IllegalArgumentException.class)
        .verify();

    StepVerifier.create(processor.validateConfig(Map.of("condition", "true", "engine", "REGO")))
        .expectErrorMatches(e -> e.getMessage().contains("reserved"))
        .verify();
  }
}
