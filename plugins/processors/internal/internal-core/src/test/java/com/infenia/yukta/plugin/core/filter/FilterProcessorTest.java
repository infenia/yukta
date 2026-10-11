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
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.support.StaticListableBeanFactory;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Filter processor test. */
@SuppressWarnings("PMD.TooManyMethods")
@NoArgsConstructor
class FilterProcessorTest {

  /** Literal ACTIVE. */
  private static final String ACTIVE_2 = "ACTIVE";

  /** Literal condition. */
  private static final String CONDITION = "condition";

  /** Literal default. */
  private static final String DEFAULT = "default";

  /** Literal engine. */
  private static final String ENGINE = "engine";

  /** Literal filter. */
  private static final String FILTER_2 = "filter";

  /** Literal nodeId. */
  private static final String NODEID = "nodeId";

  /** Literal payload.status == 'ACTIVE'. */
  private static final String PAYLOAD_STATUS_ACTIVE = "payload.status == 'ACTIVE'";

  /** Literal status. */
  private static final String STATUS = "status";

  /** Literal test-node. */
  private static final String TEST_NODE = "test-node";

  /** Literal true. */
  private static final String TRUE = "true";

  /** Processor. */
  private FilterProcessor processor;

  /** Reporter. */
  private PluginMetricsReporter reporter;

  @BeforeEach
  void setUp() {
    reporter = mock(PluginMetricsReporter.class);
    final StaticListableBeanFactory beanFactory = new StaticListableBeanFactory();
    beanFactory.addBean("reporter", reporter);
    processor = new FilterProcessor(beanFactory.getBeanProvider(PluginMetricsReporter.class));
  }

  @Test
  void testSpelMatch() {
    final Map<String, Object> config = Map.of(CONDITION, PAYLOAD_STATUS_ACTIVE);
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of(STATUS, ACTIVE_2));

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(ctx -> ctx.put(NODEID, TEST_NODE)))
        .expectNextMatches(
            m -> {
              assertEquals(msg.payload(), m.payload(), "Test spel match");
              assertEquals(DEFAULT, m.sourcePort(), "Test spel match (2)");
              assertTrue(m.messageHistory().isEmpty(), "Test spel match (3)");
              return true;
            })
        .verifyComplete();

    verify(reporter).increment(TEST_NODE, FILTER_2, "MATCH");
  }

  @Test
  void testSpelVariablesMatch() {
    final Map<String, Object> config =
        Map.of(CONDITION, "#payload.status == 'ACTIVE' && #metadata.source == 'REST'");
    final Message<?> msg =
        DefaultMessage.create(UUID.randomUUID(), Map.of(STATUS, ACTIVE_2))
            .withMetadata(Map.of("source", "REST"));

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(ctx -> ctx.put(NODEID, TEST_NODE)))
        .expectNextMatches(m -> DEFAULT.equals(m.sourcePort()))
        .verifyComplete();

    verify(reporter).increment(TEST_NODE, FILTER_2, "MATCH");
  }

  @Test
  void testSpelNoMatch() {
    final Map<String, Object> config = Map.of(CONDITION, PAYLOAD_STATUS_ACTIVE);
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of(STATUS, "INACTIVE"));

    StepVerifier.create(processor.process(Flux.just(msg), config)).verifyComplete();
  }

  @Test
  void testSimpleEngineMatch() {
    final Map<String, Object> config = Map.of(CONDITION, PAYLOAD_STATUS_ACTIVE, ENGINE, "SIMPLE");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of(STATUS, ACTIVE_2));

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(ctx -> ctx.put(NODEID, TEST_NODE)))
        .expectNextMatches(
            m -> {
              assertEquals(msg.payload(), m.payload(), "Test simple engine match");
              assertEquals(DEFAULT, m.sourcePort(), "Test simple engine match (2)");
              assertTrue(m.messageHistory().isEmpty(), "Test simple engine match (3)");
              return true;
            })
        .verifyComplete();
  }

  @Test
  void testDiscardPort() {
    final Map<String, Object> config =
        Map.of(CONDITION, PAYLOAD_STATUS_ACTIVE, "discardPort", "dead-letter");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of(STATUS, "INACTIVE"));

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(ctx -> ctx.put(NODEID, TEST_NODE)))
        .expectNextMatches(
            m -> {
              assertEquals("dead-letter", m.sourcePort(), "Test discard port");
              assertTrue(m.messageHistory().isEmpty(), "Test discard port (2)");
              return true;
            })
        .verifyComplete();

    verify(reporter).increment(TEST_NODE, FILTER_2, "DISCARD");
  }

  @Test
  void testStrictModeError() {
    final Map<String, Object> config =
        Map.of(CONDITION, "payload.invalidField > 100", "strictMode", true);
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of(STATUS, ACTIVE_2));

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(ctx -> ctx.put(NODEID, TEST_NODE)))
        .expectErrorMatches(
            e -> e instanceof com.infenia.yukta.plugin.exception.FilterEvaluationException)
        .verify();

    verify(reporter).increment(TEST_NODE, FILTER_2, "ERROR");
  }

  @Test
  void testNonStrictModeError() {
    final Map<String, Object> config =
        Map.of(CONDITION, "payload.invalidField > 100", "strictMode", false);
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of(STATUS, ACTIVE_2));

    StepVerifier.create(processor.process(Flux.just(msg), config)).verifyComplete();
  }

  @Test
  void testType() {
    assertEquals("FILTER", processor.getType(), "Test type");
  }

  @Test
  void testUiDesign() {
    assertTrue(processor.describe().uiDesign().isPresent(), "Test ui design");
    assertEquals(140, processor.describe().uiDesign().get().width(), "Test ui design (2)");
    assertEquals(80, processor.describe().uiDesign().get().height(), "Test ui design (3)");
    assertTrue(processor.describe().uiDesign().get().html().contains("svg"), "Test ui design (4)");
  }

  @Test
  void testOutputPorts() {
    assertEquals(List.of(DEFAULT), processor.getOutputPorts(Map.of()), "Test output ports");
    assertEquals(
        List.of(DEFAULT, "discard"),
        processor.getOutputPorts(Map.of("discardPort", "discard")),
        "Test output ports (2)");
  }

  @Test
  void testValidateConfig() {
    StepVerifier.create(processor.validateConfig(Map.of(CONDITION, TRUE))).verifyComplete();

    StepVerifier.create(processor.validateConfig(Map.of()))
        .expectError(IllegalArgumentException.class)
        .verify();

    StepVerifier.create(processor.validateConfig(Map.of(CONDITION, TRUE, ENGINE, "INVALID")))
        .expectError(IllegalArgumentException.class)
        .verify();

    StepVerifier.create(processor.validateConfig(Map.of(CONDITION, TRUE, ENGINE, "REGO")))
        .expectErrorMatches(e -> e.getMessage().contains("reserved"))
        .verify();
  }

  @Test
  void testInitialize() {
    // SpEL
    StepVerifier.create(processor.initialize(Map.of(CONDITION, TRUE, ENGINE, "SpEL")))
        .verifyComplete();
    // SIMPLE
    StepVerifier.create(
            processor.initialize(Map.of(CONDITION, PAYLOAD_STATUS_ACTIVE, ENGINE, "SIMPLE")))
        .verifyComplete();
    // Error
    StepVerifier.create(processor.initialize(Map.of()))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testMetadata() {
    assertTrue(processor.describe().description().contains("predicate"), "Test metadata");
    assertTrue(processor.describe().usagePattern().contains(CONDITION), "Test metadata (2)");
  }

  @Test
  void testOptimizationHints() {
    assertTrue(
        processor.suppressOptimizationHint(Map.of("allowAfterHeavy", true)),
        "Test optimization hints");
    assertEquals(
        false, processor.suppressOptimizationHint(Map.of()), "Test optimization hints (2)");
  }

  @Test
  void testValidateInContext() {
    final com.infenia.yukta.plugin.core.WorkflowContext context =
        new com.infenia.yukta.plugin.core.WorkflowContext(
            "node1",
            List.of(),
            List.of(new com.infenia.yukta.plugin.core.WorkflowContext.WorkflowEdge("s", "t", "p")));

    // Should log warning but complete
    org.junit.jupiter.api.Assertions.assertNull(
        processor.validateInContext(context, Map.of()).block(), "Completes empty");
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
    org.junit.jupiter.api.Assertions.assertNull(
        processor.onControlSignal(null).block(), "Completes empty");
  }

  @Test
  void testEvaluateUnsupportedEngine() {
    final Map<String, Object> config = Map.of(CONDITION, TRUE, ENGINE, "UNKNOWN");
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of());

    // Switch to manual initialization if needed, but here we just test process with bad engine
    StepVerifier.create(
            processor.process(Flux.just(msg), config).contextWrite(ctx -> ctx.put(NODEID, "test")))
        .expectErrorMatches(
            e ->
                e instanceof com.infenia.yukta.plugin.exception.FilterEvaluationException
                    && e.getCause().getMessage().contains("Unsupported engine"))
        .verify();
  }

  private Flux<Message<?>> run(final Map<String, Object> config, final Object payload) {
    return processor
        .process(Flux.just(DefaultMessage.create(UUID.randomUUID(), payload)), config)
        .contextWrite(ctx -> ctx.put("nodeId", "n"));
  }

  @Test
  void testMismatchWithBlankOrNoDiscardPortDropsMessage() {
    StepVerifier.create(run(Map.of("condition", "false", "discardPort", " "), "x"))
        .verifyComplete();
    StepVerifier.create(run(Map.of("condition", "false"), "x")).verifyComplete();
  }

  @Test
  void testNullResultIsTreatedAsNoMatch() {
    StepVerifier.create(run(Map.of("condition", "null"), "x")).verifyComplete();
  }

  @Test
  void testInitializeBlankConditionAndUnknownEngine() {
    StepVerifier.create(processor.initialize(Map.of("condition", " "))).expectError().verify();
    StepVerifier.create(processor.initialize(Map.of("condition", "x", "engine", "OTHER")))
        .verifyComplete();
    StepVerifier.create(processor.initialize(Map.of("condition", "a exists", "engine", "SIMPLE")))
        .verifyComplete();
  }

  @Test
  void testValidateConfigBranches() {
    StepVerifier.create(processor.validateConfig(Map.of("condition", " "))).expectError().verify();
    StepVerifier.create(processor.validateConfig(Map.of("condition", "x", "engine", "SIMPLE")))
        .verifyComplete();
    StepVerifier.create(processor.validateConfig(Map.of("condition", "x", "engine", "REGO")))
        .expectError()
        .verify();
    StepVerifier.create(processor.validateConfig(Map.of("condition", "x", "engine", "NOPE")))
        .expectError()
        .verify();
  }

  @Test
  void testOutputPortsBlankDiscardPort() {
    assertEquals(List.of("default"), processor.getOutputPorts(Map.of("discardPort", " ")));
    assertEquals(List.of("default"), processor.getOutputPorts(Map.of()));
  }

  @org.junit.jupiter.api.Test
  void declaresConfigSchema() {
    final var schema = processor.getConfigSchema();
    org.junit.jupiter.api.Assertions.assertTrue(
        schema.stream().anyMatch(f -> f.required() && "condition".equals(f.name())));
  }
}
