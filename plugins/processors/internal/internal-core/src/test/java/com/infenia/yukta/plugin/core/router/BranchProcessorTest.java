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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

class BranchProcessorTest {

  private BranchProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new BranchProcessor();
  }

  @Test
  void testSelectKeyMode() {
    final Map<String, Object> config =
        Map.of(
            "mode", "SELECT_KEY",
            "selector", "payload.userType",
            "cases",
                Map.of(
                    "PREMIUM", "premium_port",
                    "GUEST", "guest_port"));

    final Message msg1 = DefaultMessage.create(UUID.randomUUID(), Map.of("userType", "PREMIUM"));
    final Message msg2 = DefaultMessage.create(UUID.randomUUID(), Map.of("userType", "GUEST"));

    StepVerifier.create(
            processor
                .process(Flux.just(msg1), config)
                .contextWrite(Context.of("nodeId", "test-node")))
        .expectNextMatches(
            m ->
                "premium_port".equals(m.getSourcePort())
                    && m.getMessageHistory().contains("test-node"))
        .verifyComplete();

    StepVerifier.create(processor.process(Flux.just(msg2), config))
        .expectNextMatches(m -> "guest_port".equals(m.getSourcePort()))
        .verifyComplete();
  }

  @Test
  void testSelectKeyTypeCoercion() {
    final Map<String, Object> config =
        Map.of(
            "mode", "SELECT_KEY",
            "selector", "payload.code",
            "cases",
                Map.of(
                    "1", "port_1",
                    "2", "port_2"));

    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of("code", 1));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "port_1".equals(m.getSourcePort()))
        .verifyComplete();
  }

  @Test
  void testExpressionModeSingleMatch() {
    final Map<String, String> cases = new LinkedHashMap<>();
    cases.put("payload.score > 90", "high_score");
    cases.put("payload.score <= 90", "low_score");

    final Map<String, Object> config = Map.of("mode", "EXPRESSION", "cases", cases);

    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of("score", 95));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "high_score".equals(m.getSourcePort()))
        .verifyComplete();
  }

  @Test
  void testExpressionModeMultipleMatches() {
    final Map<String, String> cases = new LinkedHashMap<>();
    cases.put("payload.score > 50", "pass");
    cases.put("payload.score > 90", "excellent");

    final Map<String, Object> config =
        Map.of("mode", "EXPRESSION", "allowMultipleMatches", true, "cases", cases);

    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of("score", 95));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "pass".equals(m.getSourcePort()))
        .expectNextMatches(m -> "excellent".equals(m.getSourcePort()))
        .verifyComplete();
  }

  @Test
  void testDefaultPort() {
    final Map<String, Object> config =
        Map.of(
            "mode", "SELECT_KEY",
            "selector", "payload.userType",
            "cases", Map.of("ADMIN", "admin_port"),
            "defaultPort", "default_port");

    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of("userType", "UNKNOWN"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "default_port".equals(m.getSourcePort()))
        .verifyComplete();
  }

  @Test
  void testStrictModeError() {
    final Map<String, Object> config =
        Map.of(
            "mode",
            "SELECT_KEY",
            "selector",
            "payload.userType",
            "cases",
            Map.of("ADMIN", "admin_port"),
            "strictMode",
            true);

    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of("userType", "UNKNOWN"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectError(NoMatchingBranchException.class)
        .verify();
  }

  @Test
  void testNonStrictModeEmpty() {
    final Map<String, Object> config =
        Map.of(
            "mode",
            "SELECT_KEY",
            "selector",
            "payload.userType",
            "cases",
            Map.of("ADMIN", "admin_port"),
            "strictMode",
            false);

    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of("userType", "UNKNOWN"));

    StepVerifier.create(processor.process(Flux.just(msg), config)).verifyComplete();
  }

  @Test
  void testValidateConfig() {
    final Map<String, Object> validConfig =
        Map.of(
            "mode", "SELECT_KEY",
            "selector", "payload.id",
            "cases", Map.of("1", "port1"));
    StepVerifier.create(processor.validateConfig(validConfig)).verifyComplete();

    final Map<String, Object> invalidMode = Map.of("mode", "INVALID", "cases", Map.of());
    StepVerifier.create(processor.validateConfig(invalidMode)).expectError().verify();

    final Map<String, Object> missingSelector = Map.of("mode", "SELECT_KEY", "cases", Map.of());
    StepVerifier.create(processor.validateConfig(missingSelector)).expectError().verify();

    final Map<String, Object> missingCases = Map.of("mode", "EXPRESSION");
    StepVerifier.create(processor.validateConfig(missingCases)).expectError().verify();
  }

  @Test
  void testErrorPortOnEvaluationFailure() {
    final Map<String, Object> config =
        Map.of(
            "mode", "SELECT_KEY",
            "selector", "payload.unknownField.subField", // Will fail evaluation
            "cases", Map.of("VAL", "port"),
            "errorPort", "error_port");

    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of("key", "val"));

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(Context.of("nodeId", "test-node")))
        .expectNextMatches(
            m ->
                "error_port".equals(m.getSourcePort())
                    && m.getFailureReason().contains("evaluation failed")
                    && m.getMessageHistory().contains("test-node"))
        .verifyComplete();
  }

  @Test
  void testGetOutputPorts() {
    final Map<String, Object> config =
        Map.of(
            "mode", "SELECT_KEY",
            "cases", Map.of("A", "portA", "B", "portB"),
            "defaultPort", "defPort",
            "errorPort", "errPort");

    final List<String> ports = processor.getOutputPorts(config);
    assertEquals(4, ports.size());
    assertTrue(ports.contains("portA"));
    assertTrue(ports.contains("portB"));
    assertTrue(ports.contains("defPort"));
    assertTrue(ports.contains("errPort"));
  }

  @Test
  void testGetUiDesign() {
    final Optional<UiDesign> design = processor.getUiDesign();
    assertTrue(design.isPresent());
    assertEquals(140, design.get().width());
    assertEquals(80, design.get().height());
    assertTrue(design.get().html().contains("Branch"));
  }

  @Test
  void testMetadata() {
    assertTrue(processor.getDescription().contains("Routes"));
    assertTrue(processor.getUsagePattern().contains("mode"));
  }

  @Test
  void testDefaultLifecycle() {
    processor.initialize(Map.of()).block();
    processor.prepare(Map.of()).block();
    processor.shutdown(Map.of()).block();
    processor.onControlSignal(null).block();
  }

  @Test
  void testPrepare() {
    // SELECT_KEY
    processor.prepare(Map.of("mode", "SELECT_KEY", "selector", "payload.x")).block();
    // EXPRESSION
    processor
        .prepare(Map.of("mode", "EXPRESSION", "cases", Map.of("payload.x == 1", "port")))
        .block();
    // No-op
    processor.prepare(Map.of("mode", "OTHER")).block();
  }

  @Test
  void testValidateInContext() {
    final Map<String, Object> config = Map.of("cases", Map.of("1", "port1"), "defaultPort", "def");

    // Success
    final com.infenia.yukta.plugin.core.WorkflowContext successContext =
        new com.infenia.yukta.plugin.core.WorkflowContext(
            "node1",
            List.of(
                new com.infenia.yukta.plugin.core.WorkflowContext.WorkflowEdge(
                    "node1", "t1", "port1"),
                new com.infenia.yukta.plugin.core.WorkflowContext.WorkflowEdge(
                    "node1", "t2", "def")),
            List.of());
    StepVerifier.create(processor.validateInContext(successContext, config)).verifyComplete();

    // Error - missing edge for port
    final com.infenia.yukta.plugin.core.WorkflowContext errorContext =
        new com.infenia.yukta.plugin.core.WorkflowContext(
            "node1",
            List.of(
                new com.infenia.yukta.plugin.core.WorkflowContext.WorkflowEdge(
                    "node1", "t1", "port1")),
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
    final Map<String, Object> config =
        Map.of("mode", "SELECT_KEY", "selector", "payload.val.fail()");
    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of("val", 1));

    // Strict
    StepVerifier.create(processor.process(Flux.just(msg), config)).expectError().verify();

    // Non-strict
    final Map<String, Object> nonStrict = new java.util.HashMap<>(config);
    nonStrict.put("strictMode", false);
    StepVerifier.create(processor.process(Flux.just(msg), nonStrict)).verifyComplete();
  }

  @Test
  void testExpressionModeNoMatch() {
    final Map<String, String> cases = Map.of("payload.score > 100", "impossible");
    final Map<String, Object> config = Map.of("mode", "EXPRESSION", "cases", cases);
    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of("score", 50));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectError(NoMatchingBranchException.class)
        .verify();
  }

  @Test
  void testSelectKeyNoMatchResult() {
    final Map<String, Object> config =
        Map.of(
            "mode",
            "SELECT_KEY",
            "selector",
            "payload.missing",
            "cases",
            Map.of("VAL", "port"),
            "strictMode",
            false);
    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of());

    StepVerifier.create(processor.process(Flux.just(msg), config)).verifyComplete();
  }

  @Test
  void testGetOutputPortsEmpty() {
    assertEquals(List.of("default"), processor.getOutputPorts(Map.of()));
  }

  @Test
  void testPrepareExpressionNullCases() {
    StepVerifier.create(processor.prepare(Map.of("mode", "EXPRESSION"))).verifyComplete();
  }

  @Test
  void testGetType() {
    assertEquals("BRANCH", processor.getType());
  }

  @Test
  void testHandleExceptionNoStrictMode() {
    final Map<String, Object> config =
        Map.of(
            "mode", "SELECT_KEY",
            "selector", "payload.val.fail()",
            "strictMode", false);
    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of("val", 1));

    StepVerifier.create(processor.process(Flux.just(msg), config)).verifyComplete();
  }

  @Test
  void testHandleExceptionWrapped() {
    final Map<String, Object> config = Map.of("mode", "SELECT_KEY", "selector", "1/0");
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
            "mode", "SELECT_KEY",
            "selector",
                "payload['missing']", // Use bracket notation for Map to avoid reflection issues
            "cases", Map.of("A", "portA"),
            "defaultPort", "def");
    final Message<Map<String, Object>> msg = DefaultMessage.create(UUID.randomUUID(), Map.of());

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "def".equals(m.getSourcePort()))
        .verifyComplete();
  }

  @Test
  void testSelectKeyPortNotFound() {
    final Map<String, Object> config =
        Map.of(
            "mode", "SELECT_KEY",
            "selector", "payload.val",
            "cases", Map.of("A", "portA"),
            "defaultPort", "def");
    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of("val", "B"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "def".equals(m.getSourcePort()))
        .verifyComplete();
  }

  @Test
  void testGetOutputPortsWithBlanks() {
    final Map<String, Object> config =
        Map.of(
            "mode", "SELECT_KEY",
            "cases", Map.of("A", "portA"),
            "defaultPort", " ",
            "errorPort", "");

    final List<String> ports = processor.getOutputPorts(config);
    assertEquals(List.of("portA"), ports);
  }

  @Test
  void testValidateConfigMissingCases() {
    final Map<String, Object> config = Map.of("mode", "SELECT_KEY", "selector", "s");
    StepVerifier.create(processor.validateConfig(config))
        .expectErrorMatches(
            e -> e instanceof IllegalArgumentException && e.getMessage().contains("cases map"))
        .verify();
  }

  @Test
  void testValidateConfigSelectorNull() {
    final Map<String, Object> config = Map.of("mode", "SELECT_KEY", "cases", Map.of("A", "portA"));
    StepVerifier.create(processor.validateConfig(config))
        .expectErrorMatches(
            e -> e instanceof IllegalArgumentException && e.getMessage().contains("selector"))
        .verify();
  }

  @Test
  void testEvaluateBranchesEmptyCases() {
    final Map<String, Object> config =
        Map.of("mode", "SELECT_KEY", "selector", "'A'", "cases", Map.of());
    final Message<Map<String, Object>> msg = DefaultMessage.create(UUID.randomUUID(), Map.of());

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectError(NoMatchingBranchException.class)
        .verify();
  }

  @Test
  void testHandleExceptionNullErrorPort() {
    final Map<String, Object> config = Map.of("mode", "SELECT_KEY", "selector", "1/0");
    final Message<Map<String, Object>> msg = DefaultMessage.create(UUID.randomUUID(), Map.of());

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectError(RuntimeException.class)
        .verify();
  }
}
