// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.filter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.PluginMetricsReporter;
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
        .expectErrorMatches(
            e -> e instanceof com.infenia.yukta.plugin.exception.FilterEvaluationException)
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

  @Test
  void testInitialize() {
    // SpEL
    StepVerifier.create(processor.initialize(Map.of("condition", "true", "engine", "SpEL")))
        .verifyComplete();
    // SIMPLE
    StepVerifier.create(
            processor.initialize(
                Map.of("condition", "payload.status == 'ACTIVE'", "engine", "SIMPLE")))
        .verifyComplete();
    // Error
    StepVerifier.create(processor.initialize(Map.of()))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testMetadata() {
    assertTrue(processor.getDescription().contains("predicate"));
    assertTrue(processor.getUsagePattern().contains("condition"));
  }

  @Test
  void testOptimizationHints() {
    assertTrue(processor.suppressOptimizationHint(Map.of("allowAfterHeavy", true)));
    assertEquals(false, processor.suppressOptimizationHint(Map.of()));
  }

  @Test
  void testValidateInContext() {
    final com.infenia.yukta.plugin.core.WorkflowContext context =
        new com.infenia.yukta.plugin.core.WorkflowContext(
            "node1",
            List.of(),
            List.of(new com.infenia.yukta.plugin.core.WorkflowContext.WorkflowEdge("s", "t", "p")));

    // Should log warning but complete
    processor.validateInContext(context, Map.of()).block();

    // Should skip warning
    processor.validateInContext(context, Map.of("allowAfterHeavy", true)).block();

    // No incoming edges
    processor
        .validateInContext(
            new com.infenia.yukta.plugin.core.WorkflowContext("node1", List.of(), List.of()),
            Map.of())
        .block();
  }

  @Test
  void testDefaultLifecycleMethods() {
    processor.prepare(Map.of()).block();
    processor.shutdown(Map.of()).block();
    processor.onControlSignal(null).block();
  }

  @Test
  void testEvaluateUnsupportedEngine() {
    final Map<String, Object> config = Map.of("condition", "true", "engine", "UNKNOWN");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of());

    // Switch to manual initialization if needed, but here we just test process with bad engine
    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(ctx -> ctx.put("nodeId", "test")))
        .expectErrorMatches(
            e ->
                e instanceof com.infenia.yukta.plugin.exception.FilterEvaluationException
                    && e.getCause().getMessage().contains("Unsupported engine"))
        .verify();
  }
}
