// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.UiDesign;
import com.infenia.yukta.plugin.exception.NoMatchingBranchException;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** Branch processor test. */
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.LooseCoupling",
  "PMD.TooManyMethods",
  "PMD.UnitTestAssertionsShouldIncludeMessage",
  "PMD.UnnecessaryFullyQualifiedName",
  "PMD.UseConcurrentHashMap"
})
@NoArgsConstructor
class BranchProcessorTest {

  /** Literal EXPRESSION. */
  private static final String EXPRESSION_2 = "EXPRESSION";

  /** Literal SELECT_KEY. */
  private static final String SELECT_KEY_2 = "SELECT_KEY";

  /** Literal cases. */
  private static final String CASES = "cases";

  /** Literal def. */
  private static final String DEF = "def";

  /** Literal defaultPort. */
  private static final String DEFAULTPORT = "defaultPort";

  /** Literal mode. */
  private static final String MODE = "mode";

  /** Literal node1. */
  private static final String NODE1 = "node1";

  /** Literal payload.userType. */
  private static final String PAYLOAD_USERTYPE = "payload.userType";

  /** Literal port1. */
  private static final String PORT1 = "port1";

  /** Literal portA. */
  private static final String PORTA = "portA";

  /** Literal selector. */
  private static final String SELECTOR = "selector";

  /** Literal strictMode. */
  private static final String STRICTMODE = "strictMode";

  /** Literal userType. */
  private static final String USERTYPE = "userType";

  /** Literal val. */
  private static final String VAL_2 = "val";

  /** Processor. */
  private BranchProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new BranchProcessor();
  }

  @Test
  void testSelectKeyMode() {
    final Map<String, Object> config =
        Map.of(
            MODE, SELECT_KEY_2,
            SELECTOR, PAYLOAD_USERTYPE,
            CASES,
                Map.of(
                    "PREMIUM", "premium_port",
                    "GUEST", "guest_port"));

    final Message msg1 = DefaultMessage.create(UUID.randomUUID(), Map.of(USERTYPE, "PREMIUM"));
    final Message msg2 = DefaultMessage.create(UUID.randomUUID(), Map.of(USERTYPE, "GUEST"));

    StepVerifier.create(
            processor
                .process(Flux.just(msg1), config)
                .contextWrite(Context.of("nodeId", "test-node")))
        .expectNextMatches(
            m -> "premium_port".equals(m.sourcePort()) && m.messageHistory().isEmpty())
        .verifyComplete();

    StepVerifier.create(processor.process(Flux.just(msg2), config))
        .expectNextMatches(m -> "guest_port".equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testSelectKeyTypeCoercion() {
    final Map<String, Object> config =
        Map.of(
            MODE,
            SELECT_KEY_2,
            SELECTOR,
            "payload.code",
            CASES,
            Map.of(
                "1", "port_1",
                "2", "port_2"));

    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of("code", 1));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "port_1".equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testExpressionModeSingleMatch() {
    final Map<String, String> cases = new LinkedHashMap<>();
    cases.put("payload.score > 90", "high_score");
    cases.put("payload.score <= 90", "low_score");

    final Map<String, Object> config = Map.of(MODE, EXPRESSION_2, CASES, cases);

    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of("score", 95));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "high_score".equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testExpressionModeMultipleMatches() {
    final Map<String, String> cases = new LinkedHashMap<>();
    cases.put("payload.score > 50", "pass");
    cases.put("payload.score > 90", "excellent");

    final Map<String, Object> config =
        Map.of(MODE, EXPRESSION_2, "allowMultipleMatches", true, CASES, cases);

    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of("score", 95));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "pass".equals(m.sourcePort()))
        .expectNextMatches(m -> "excellent".equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testDefaultPort() {
    final Map<String, Object> config =
        Map.of(
            MODE,
            SELECT_KEY_2,
            SELECTOR,
            PAYLOAD_USERTYPE,
            CASES,
            Map.of("ADMIN", "admin_port"),
            DEFAULTPORT,
            "default_port");

    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of(USERTYPE, "UNKNOWN"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "default_port".equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testStrictModeError() {
    final Map<String, Object> config =
        Map.of(
            MODE,
            SELECT_KEY_2,
            SELECTOR,
            PAYLOAD_USERTYPE,
            CASES,
            Map.of("ADMIN", "admin_port"),
            STRICTMODE,
            true);

    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of(USERTYPE, "UNKNOWN"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectError(NoMatchingBranchException.class)
        .verify();
  }

  @Test
  void testNonStrictModeEmpty() {
    final Map<String, Object> config =
        Map.of(
            MODE,
            SELECT_KEY_2,
            SELECTOR,
            PAYLOAD_USERTYPE,
            CASES,
            Map.of("ADMIN", "admin_port"),
            STRICTMODE,
            false);

    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of(USERTYPE, "UNKNOWN"));

    StepVerifier.create(processor.process(Flux.just(msg), config)).verifyComplete();
  }

  @Test
  void testValidateConfig() {
    final Map<String, Object> validConfig =
        Map.of(MODE, SELECT_KEY_2, SELECTOR, "payload.id", CASES, Map.of("1", PORT1));
    StepVerifier.create(processor.validateConfig(validConfig)).verifyComplete();

    final Map<String, Object> invalidMode = Map.of(MODE, "INVALID", CASES, Map.of());
    StepVerifier.create(processor.validateConfig(invalidMode)).expectError().verify();

    final Map<String, Object> missingSelector = Map.of(MODE, SELECT_KEY_2, CASES, Map.of());
    StepVerifier.create(processor.validateConfig(missingSelector)).expectError().verify();

    final Map<String, Object> missingCases = Map.of(MODE, EXPRESSION_2);
    StepVerifier.create(processor.validateConfig(missingCases)).expectError().verify();
  }

  @Test
  void testErrorPortOnEvaluationFailure() {
    final Map<String, Object> config =
        Map.of(
            MODE,
            SELECT_KEY_2,
            SELECTOR,
            "payload.unknownField.subField", // Will fail evaluation
            CASES,
            Map.of("VAL", "port"),
            "errorPort",
            "error_port");

    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of("key", VAL_2));

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(Context.of("nodeId", "test-node")))
        .expectNextMatches(
            m ->
                "error_port".equals(m.sourcePort())
                    && m.failureReason().contains("evaluation failed")
                    && m.messageHistory().isEmpty())
        .verifyComplete();
  }

  @Test
  void testGetOutputPorts() {
    final Map<String, Object> config =
        Map.of(
            MODE,
            SELECT_KEY_2,
            CASES,
            Map.of("A", PORTA, "B", "portB"),
            DEFAULTPORT,
            "defPort",
            "errorPort",
            "errPort");

    final List<String> ports = processor.getOutputPorts(config);
    assertEquals(4, ports.size(), "Test get output ports");
    assertTrue(ports.contains(PORTA), "Test get output ports (2)");
    assertTrue(ports.contains("portB"), "Test get output ports (3)");
    assertTrue(ports.contains("defPort"), "Test get output ports (4)");
    assertTrue(ports.contains("errPort"), "Test get output ports (5)");
  }

  @Test
  void testGetUiDesign() {
    final Optional<UiDesign> design = processor.describe().uiDesign();
    assertTrue(design.isPresent(), "Test get ui design");
    assertEquals(140, design.get().width(), "Test get ui design (2)");
    assertEquals(80, design.get().height(), "Test get ui design (3)");
    assertTrue(design.get().label().contains("Branch"), "Test get ui design (4)");
  }

  @Test
  void testMetadata() {
    assertTrue(processor.describe().description().contains("Routes"), "Test metadata");
    assertTrue(processor.describe().usagePattern().contains(MODE), "Test metadata (2)");
  }

  @Test
  void testDefaultLifecycle() {
    processor.initialize(Map.of()).block();
    processor.prepare(Map.of()).block();
    processor.shutdown(Map.of()).block();
    org.junit.jupiter.api.Assertions.assertNull(
        processor.onControlSignal(null).block(), "Completes empty");
  }

  @Test
  void testPrepare() {
    // SELECT_KEY
    processor.prepare(Map.of(MODE, SELECT_KEY_2, SELECTOR, "payload.x")).block();
    // EXPRESSION
    processor.prepare(Map.of(MODE, EXPRESSION_2, CASES, Map.of("payload.x == 1", "port"))).block();
    // No-op
    org.junit.jupiter.api.Assertions.assertNull(
        processor.prepare(Map.of(MODE, "OTHER")).block(), "Completes empty");
  }

  @Test
  void testValidateInContext() {
    final Map<String, Object> config = Map.of(CASES, Map.of("1", PORT1), DEFAULTPORT, DEF);

    // Success
    final com.infenia.yukta.plugin.core.WorkflowContext successContext =
        new com.infenia.yukta.plugin.core.WorkflowContext(
            NODE1,
            List.of(
                new com.infenia.yukta.plugin.core.WorkflowContext.WorkflowEdge(NODE1, "t1", PORT1),
                new com.infenia.yukta.plugin.core.WorkflowContext.WorkflowEdge(NODE1, "t2", DEF)),
            List.of());
    StepVerifier.create(processor.validateInContext(successContext, config)).verifyComplete();

    // Error - missing edge for port
    final com.infenia.yukta.plugin.core.WorkflowContext errorContext =
        new com.infenia.yukta.plugin.core.WorkflowContext(
            NODE1,
            List.of(
                new com.infenia.yukta.plugin.core.WorkflowContext.WorkflowEdge(NODE1, "t1", PORT1)),
            List.of());
    StepVerifier.create(processor.validateInContext(errorContext, config))
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("no outgoing edge"))
        .verify();

    // Cases null handled by getOrDefault
    StepVerifier.create(processor.validateInContext(errorContext, Map.of())).verifyComplete();
  }

  @Test
  void testProcessEvaluationFailureNoPort() {
    final Map<String, Object> config = Map.of(MODE, SELECT_KEY_2, SELECTOR, "payload.val.fail()");
    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of(VAL_2, 1));

    // Strict
    StepVerifier.create(processor.process(Flux.just(msg), config)).expectError().verify();

    // Non-strict
    final Map<String, Object> nonStrict = new java.util.HashMap<>(config);
    nonStrict.put(STRICTMODE, false);
    StepVerifier.create(processor.process(Flux.just(msg), nonStrict)).verifyComplete();
  }

  @Test
  void testExpressionModeNoMatch() {
    final Map<String, String> cases = Map.of("payload.score > 100", "impossible");
    final Map<String, Object> config = Map.of(MODE, EXPRESSION_2, CASES, cases);
    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of("score", 50));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectError(NoMatchingBranchException.class)
        .verify();
  }

  @Test
  void testSelectKeyNoMatchResult() {
    final Map<String, Object> config =
        Map.of(
            MODE,
            SELECT_KEY_2,
            SELECTOR,
            "payload.missing",
            CASES,
            Map.of("VAL", "port"),
            STRICTMODE,
            false);
    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of());

    StepVerifier.create(processor.process(Flux.just(msg), config)).verifyComplete();
  }

  @Test
  void testGetOutputPortsEmpty() {
    assertEquals(
        List.of("default"), processor.getOutputPorts(Map.of()), "Test get output ports empty");
  }

  @Test
  void testPrepareExpressionNullCases() {
    StepVerifier.create(processor.prepare(Map.of(MODE, EXPRESSION_2))).verifyComplete();
  }

  @Test
  void testGetType() {
    assertEquals("BRANCH", processor.getType(), "Test get type");
  }

  @Test
  void testHandleExceptionNoStrictMode() {
    final Map<String, Object> config =
        Map.of(MODE, SELECT_KEY_2, SELECTOR, "payload.val.fail()", STRICTMODE, false);
    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of(VAL_2, 1));

    StepVerifier.create(processor.process(Flux.just(msg), config)).verifyComplete();
  }

  @Test
  void testHandleExceptionWrapped() {
    final Map<String, Object> config = Map.of(MODE, SELECT_KEY_2, SELECTOR, "1/0");
    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of());

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectErrorMatches(
            e ->
                e instanceof RuntimeException
                    && e.getMessage().contains("Branch evaluation failed"))
        .verify();
  }

  @Test
  void testSelectKeyResultNull() {
    final Map<String, Object> config =
        Map.of(
            MODE,
            SELECT_KEY_2,
            SELECTOR,
            "payload['missing']", // Use bracket notation for Map to avoid reflection issues
            CASES,
            Map.of("A", PORTA),
            DEFAULTPORT,
            DEF);
    final Message<Map<String, Object>> msg = DefaultMessage.create(UUID.randomUUID(), Map.of());

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> DEF.equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testSelectKeyPortNotFound() {
    final Map<String, Object> config =
        Map.of(
            MODE,
            SELECT_KEY_2,
            SELECTOR,
            "payload.val",
            CASES,
            Map.of("A", PORTA),
            DEFAULTPORT,
            DEF);
    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of(VAL_2, "B"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> DEF.equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testGetOutputPortsWithBlanks() {
    final Map<String, Object> config =
        Map.of(MODE, SELECT_KEY_2, CASES, Map.of("A", PORTA), DEFAULTPORT, " ", "errorPort", "");

    final List<String> ports = processor.getOutputPorts(config);
    assertEquals(List.of(PORTA), ports, "Test get output ports with blanks");
  }

  @Test
  void testValidateConfigMissingCases() {
    final Map<String, Object> config = Map.of(MODE, SELECT_KEY_2, SELECTOR, "s");
    StepVerifier.create(processor.validateConfig(config))
        .expectErrorMatches(
            e -> e instanceof IllegalArgumentException && e.getMessage().contains("cases map"))
        .verify();
  }

  @Test
  void testValidateConfigSelectorNull() {
    final Map<String, Object> config = Map.of(MODE, SELECT_KEY_2, CASES, Map.of("A", PORTA));
    StepVerifier.create(processor.validateConfig(config))
        .expectErrorMatches(
            e -> e instanceof IllegalArgumentException && e.getMessage().contains(SELECTOR))
        .verify();
  }

  @Test
  void testEvaluateBranchesEmptyCases() {
    final Map<String, Object> config = Map.of(MODE, SELECT_KEY_2, SELECTOR, "'A'", CASES, Map.of());
    final Message<Map<String, Object>> msg = DefaultMessage.create(UUID.randomUUID(), Map.of());

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectError(NoMatchingBranchException.class)
        .verify();
  }

  @Test
  void testHandleExceptionNullErrorPort() {
    final Map<String, Object> config = Map.of(MODE, SELECT_KEY_2, SELECTOR, "1/0");
    final Message<Map<String, Object>> msg = DefaultMessage.create(UUID.randomUUID(), Map.of());

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectError(RuntimeException.class)
        .verify();
  }

  @Test
  void testSelectKeyNullResultMatchesNothing() {
    final Map<String, Object> config =
        Map.of("mode", "SELECT_KEY", "selector", "payload.none", "cases", Map.of("A", "pa"));
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of("x", 1));
    StepVerifier.create(processor.process(Flux.just(msg), config).onErrorResume(e -> Flux.empty()))
        .verifyComplete();
  }

  @Test
  void testSelectKeyLiteralNullMatchesNothing() {
    final Map<String, Object> config =
        Map.of("mode", "SELECT_KEY", "selector", "null", "cases", Map.of("A", "pa"));
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "x");
    StepVerifier.create(processor.process(Flux.just(msg), config).onErrorResume(e -> Flux.empty()))
        .verifyComplete();
  }

  @Test
  void testValidateConfigCasesNullOrEmpty() {
    StepVerifier.create(processor.validateConfig(Map.of("mode", "EXPRESSION", "cases", Map.of())))
        .expectError()
        .verify();
    StepVerifier.create(processor.validateConfig(Map.of("mode", "EXPRESSION")))
        .expectError()
        .verify();
    StepVerifier.create(processor.validateConfig(Map.of("mode", "SELECT_KEY", "selector", "s")))
        .expectError()
        .verify();
  }

  @Test
  void testValidateInContextWithExplicitNullCases() {
    final java.util.HashMap<String, Object> config = new java.util.HashMap<>();
    config.put("cases", null);
    final com.infenia.yukta.plugin.core.WorkflowContext ctx =
        new com.infenia.yukta.plugin.core.WorkflowContext("n", List.of(), List.of());
    StepVerifier.create(processor.validateInContext(ctx, config)).verifyComplete();
  }

  @Test
  void testExpressionModeNoMatchNoCasesEvaluateToTrue() {
    final Map<String, String> cases = new LinkedHashMap<>();
    cases.put("payload.v == 1", "p1");
    cases.put("payload.v == 2", "p2");
    final Map<String, Object> config = Map.of("mode", "EXPRESSION", "cases", cases);
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), Map.of("v", 2));
    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "p2".equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testUnknownModeMatchesNothing() {
    final Map<String, Object> config =
        Map.of("mode", "BOGUS", "cases", Map.of("A", "pa"), "strictMode", false);
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "x");
    StepVerifier.create(processor.process(Flux.just(msg), config)).verifyComplete();
  }

  @org.junit.jupiter.api.Test
  void declaresConfigSchema() {
    final var schema = processor.getConfigSchema();
    org.junit.jupiter.api.Assertions.assertTrue(
        schema.stream().anyMatch(f -> f.required() && "cases".equals(f.name())));
  }
}
