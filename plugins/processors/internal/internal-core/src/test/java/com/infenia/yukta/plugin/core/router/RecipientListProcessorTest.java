// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.core.router;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.plugin.core.UiDesign;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** Recipient list processor test. */
@SuppressWarnings("PMD.TooManyMethods")
@NoArgsConstructor
class RecipientListProcessorTest {

  /** Literal DYNAMIC. */
  private static final String DYNAMIC_2 = "DYNAMIC";

  /** Literal STATIC. */
  private static final String STATIC_2 = "STATIC";

  /** Literal data. */
  private static final String DATA = "data";

  /** Literal expression. */
  private static final String EXPRESSION = "expression";

  /** Literal mode. */
  private static final String MODE = "mode";

  /** Literal recipients. */
  private static final String RECIPIENTS = "recipients";

  /** Processor. */
  private RecipientListProcessor processor;

  @BeforeEach
  void setUp() {
    processor = new RecipientListProcessor();
  }

  @Test
  void testStaticMode() {
    final Map<String, Object> config =
        Map.of(MODE, STATIC_2, RECIPIENTS, List.of("port1", "port2"));

    final Message msg = DefaultMessage.create(UUID.randomUUID(), "payload");

    StepVerifier.create(
            processor
                .process(Flux.just(msg), config)
                .contextWrite(Context.of("nodeId", "test-node")))
        .expectNextMatches(m -> "port1".equals(m.sourcePort()) && m.messageHistory().isEmpty())
        .expectNextMatches(m -> "port2".equals(m.sourcePort()) && m.messageHistory().isEmpty())
        .verifyComplete();
  }

  @Test
  void testDynamicModeList() {
    final Map<String, Object> config = Map.of(MODE, DYNAMIC_2, EXPRESSION, "payload.destinations");

    final Message msg =
        DefaultMessage.create(UUID.randomUUID(), Map.of("destinations", List.of("p1", "p2")));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "p1".equals(m.sourcePort()))
        .expectNextMatches(m -> "p2".equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testDynamicModeSingleString() {
    final Map<String, Object> config = Map.of(MODE, DYNAMIC_2, EXPRESSION, "payload.target");

    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of("target", "p1"));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "p1".equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testExternalModePayload() {
    final Map<String, Object> config =
        Map.of(MODE, "EXTERNAL", "sourceField", "payload.user.groups");

    final Message msg =
        DefaultMessage.create(UUID.randomUUID(), Map.of("user", Map.of("groups", List.of("g1"))));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "g1".equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testExternalModeMetadata() {
    final Map<String, Object> config =
        Map.of(MODE, "EXTERNAL", "sourceField", "metadata.recipients");

    final Message msg =
        DefaultMessage.create(UUID.randomUUID(), DATA)
            .withMetadata(Map.of(RECIPIENTS, List.of("m1")));

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "m1".equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testZeroMatchesStatic() {
    final Map<String, Object> config = Map.of(MODE, STATIC_2, RECIPIENTS, List.of());

    final Message msg = DefaultMessage.create(UUID.randomUUID(), DATA);

    StepVerifier.create(processor.process(Flux.just(msg), config)).verifyComplete();
  }

  @Test
  void testZeroMatchesDynamic() {
    final Map<String, Object> config = Map.of(MODE, DYNAMIC_2, EXPRESSION, "payload.destinations");

    final Message msg = DefaultMessage.create(UUID.randomUUID(), Map.of("destinations", List.of()));

    StepVerifier.create(processor.process(Flux.just(msg), config)).verifyComplete();
  }

  @Test
  void testSequentialDispatch() {
    final Map<String, Object> config =
        Map.of(MODE, STATIC_2, RECIPIENTS, List.of("p1", "p2"), "parallel", false);

    final Message msg = DefaultMessage.create(UUID.randomUUID(), DATA);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(m -> "p1".equals(m.sourcePort()))
        .expectNextMatches(m -> "p2".equals(m.sourcePort()))
        .verifyComplete();
  }

  @Test
  void testErrorPortOnFailure() {
    final Map<String, Object> config =
        Map.of(
            MODE,
            DYNAMIC_2,
            EXPRESSION,
            "1 / 0", // Causes ArithmeticException
            "errorPort",
            "error_port");

    final Message msg = DefaultMessage.create(UUID.randomUUID(), DATA);

    StepVerifier.create(processor.process(Flux.just(msg), config))
        .expectNextMatches(
            m ->
                "error_port".equals(m.sourcePort())
                    && m.failureReason().contains("Recipient computation failed"))
        .verifyComplete();
  }

  @Test
  void testStrictModeError() {
    final Map<String, Object> config =
        Map.of(MODE, DYNAMIC_2, EXPRESSION, "1 / 0", "strictMode", true);

    final Message msg = DefaultMessage.create(UUID.randomUUID(), DATA);

    StepVerifier.create(processor.process(Flux.just(msg), config)).expectError().verify();
  }

  @Test
  void testGetOutputPorts() {
    // Static mode
    final Map<String, Object> staticConfig =
        Map.of(MODE, STATIC_2, RECIPIENTS, List.of("p1", "p2"), "errorPort", "err");
    final List<String> staticPorts = processor.getOutputPorts(staticConfig);
    assertEquals(3, staticPorts.size(), "Test get output ports");
    assertTrue(staticPorts.containsAll(List.of("p1", "p2", "err")), "Test get output ports (2)");

    // Dynamic mode
    final Map<String, Object> dynamicConfig = Map.of(MODE, DYNAMIC_2);
    assertEquals(
        List.of("*"), processor.getOutputPorts(dynamicConfig), "Test get output ports (3)");
  }

  @Test
  void testValidateConfig() {
    // Valid Static
    StepVerifier.create(processor.validateConfig(Map.of(MODE, STATIC_2, RECIPIENTS, List.of("p1"))))
        .verifyComplete();

    // Invalid Static
    StepVerifier.create(processor.validateConfig(Map.of(MODE, STATIC_2))).expectError().verify();

    // Valid Dynamic
    StepVerifier.create(processor.validateConfig(Map.of(MODE, DYNAMIC_2, EXPRESSION, "exp")))
        .verifyComplete();

    // Invalid Dynamic
    StepVerifier.create(processor.validateConfig(Map.of(MODE, DYNAMIC_2))).expectError().verify();
  }

  @Test
  void testUiDesign() {
    final Optional<UiDesign> design = processor.describe().uiDesign();
    assertTrue(design.isPresent(), "Test ui design");
    assertTrue(design.get().html().contains("Recipient List"), "Test ui design (2)");
  }

  private List<Object> run(final Map<String, Object> config, final Object payload) {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), payload);
    return processor
        .process(Flux.just(msg), config)
        .map(m -> (Object) m.sourcePort())
        .collectList()
        .block();
  }

  @Test
  void testGetType() {
    assertEquals("RECIPIENT_LIST", processor.getType());
  }

  @Test
  void testOutputPortsStaticVariants() {
    assertEquals(List.of("default"), processor.getOutputPorts(Map.of("mode", "STATIC")));
    assertEquals(
        List.of("default"), processor.getOutputPorts(Map.of("mode", "STATIC", "errorPort", "  ")));
  }

  @Test
  void testValidateConfigExternalAndInvalid() {
    StepVerifier.create(processor.validateConfig(Map.of("mode", "EXTERNAL", "sourceField", "x")))
        .verifyComplete();
    StepVerifier.create(processor.validateConfig(Map.of("mode", "EXTERNAL")))
        .expectError()
        .verify();
    StepVerifier.create(processor.validateConfig(Map.of("mode", "BOGUS"))).expectError().verify();
    StepVerifier.create(processor.validateConfig(Map.of("mode", "STATIC", "recipients", List.of())))
        .verifyComplete();
  }

  @Test
  void testPrepareAllModes() {
    StepVerifier.create(processor.prepare(Map.of("mode", "DYNAMIC", "expression", "payload.a")))
        .verifyComplete();
    StepVerifier.create(processor.prepare(Map.of("mode", "EXTERNAL", "sourceField", "payload.a")))
        .verifyComplete();
    StepVerifier.create(processor.prepare(Map.of("mode", "STATIC"))).verifyComplete();
  }

  @Test
  void testCollectionAndArrayResults() {
    final Map<String, Object> cfg = Map.of("mode", "DYNAMIC", "expression", "payload.v");
    assertEquals(List.of("a"), run(cfg, Map.of("v", Set.of("a"))));
    assertEquals(List.of("a", "b"), run(cfg, Map.of("v", new String[] {"a", "b"})));
    assertEquals(List.of("1"), run(cfg, Map.of("v", new int[] {1})));
    assertEquals(List.of("7"), run(cfg, Map.of("v", 7)));
    assertEquals(List.of(), run(Map.of("mode", "DYNAMIC", "expression", "null"), "x"));
  }

  @Test
  void testNonStrictSwallowsFailure() {
    final Map<String, Object> cfg =
        Map.of("mode", "DYNAMIC", "expression", "1 / 0", "strictMode", false, "errorPort", " ");
    assertEquals(List.of(), run(cfg, "x"));
  }

  @Test
  void testUnknownModeYieldsNoRecipients() {
    assertEquals(List.of(), run(Map.of("mode", "BOGUS"), "x"));
  }

  @Test
  void testStaticModeWithoutRecipientsYieldsNothing() {
    assertEquals(List.of(), run(Map.of("mode", "STATIC"), "x"));
  }

  private List<Object> run(final Map<String, Object> config, final Object payload) {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), payload);
    return processor
        .process(Flux.just(msg), config)
        .map(m -> (Object) m.sourcePort())
        .collectList()
        .block();
  }

  @Test
  void testGetType() {
    assertEquals("RECIPIENT_LIST", processor.getType());
  }

  @Test
  void testOutputPortsStaticVariants() {
    assertEquals(List.of("default"), processor.getOutputPorts(Map.of("mode", "STATIC")));
    assertEquals(
        List.of("default"), processor.getOutputPorts(Map.of("mode", "STATIC", "errorPort", "  ")));
  }

  @Test
  void testValidateConfigExternalAndInvalid() {
    StepVerifier.create(processor.validateConfig(Map.of("mode", "EXTERNAL", "sourceField", "x")))
        .verifyComplete();
    StepVerifier.create(processor.validateConfig(Map.of("mode", "EXTERNAL")))
        .expectError()
        .verify();
    StepVerifier.create(processor.validateConfig(Map.of("mode", "BOGUS"))).expectError().verify();
    StepVerifier.create(processor.validateConfig(Map.of("mode", "STATIC", "recipients", List.of())))
        .verifyComplete();
  }

  @Test
  void testPrepareAllModes() {
    StepVerifier.create(processor.prepare(Map.of("mode", "DYNAMIC", "expression", "payload.a")))
        .verifyComplete();
    StepVerifier.create(processor.prepare(Map.of("mode", "EXTERNAL", "sourceField", "payload.a")))
        .verifyComplete();
    StepVerifier.create(processor.prepare(Map.of("mode", "STATIC"))).verifyComplete();
  }

  @Test
  void testCollectionAndArrayResults() {
    final Map<String, Object> cfg = Map.of("mode", "DYNAMIC", "expression", "payload.v");
    assertEquals(List.of("a"), run(cfg, Map.of("v", Set.of("a"))));
    assertEquals(List.of("a", "b"), run(cfg, Map.of("v", new String[] {"a", "b"})));
    assertEquals(List.of("1"), run(cfg, Map.of("v", new int[] {1})));
    assertEquals(List.of("7"), run(cfg, Map.of("v", 7)));
    assertEquals(List.of(), run(Map.of("mode", "DYNAMIC", "expression", "null"), "x"));
  }

  @Test
  void testNonStrictSwallowsFailure() {
    final Map<String, Object> cfg =
        Map.of("mode", "DYNAMIC", "expression", "1 / 0", "strictMode", false, "errorPort", " ");
    assertEquals(List.of(), run(cfg, "x"));
  }

  @Test
  void testUnknownModeYieldsNoRecipients() {
    assertEquals(List.of(), run(Map.of("mode", "BOGUS"), "x"));
  }

  @Test
  void testStaticModeWithoutRecipientsYieldsNothing() {
    assertEquals(List.of(), run(Map.of("mode", "STATIC"), "x"));
  }
}
