// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.orchestrator.validator;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Unit tests for {@link WorkflowValidator}. */
@MockitoSettings
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals", "PMD.CyclomaticComplexity"})
class WorkflowValidatorTest {

  /** Trigger plugin type identifier. */
  private static final String TRIGGER_ID = "T";

  /** Processor plugin type identifier. */
  private static final String PROCESSOR_ID = "P";

  /** Terminal plugin type identifier. */
  private static final String TERMINAL_ID = "TERM";

  /** Test workflow name. */
  private static final String WORKFLOW_NAME = "test-workflow";

  /** Default edge label. */
  private static final String DEFAULT_EDGE_LABEL = "default";

  /** Guard plugin type identifier. */
  private static final String GUARD_ID = "GUARD";

  /** Hybrid plugin type identifier. */
  private static final String HYBRID_ID = "HYBRID";

  /** Mapper plugin type identifier. */
  private static final String MAPPER_ID = "MAPPER";

  /** Filter plugin type identifier. */
  private static final String FILTER_ID = "FILTER";

  /** Unknown plugin type identifier. */
  private static final String UNKNOWN_ID = "UNKNOWN";

  /** Unknown type plugin identifier. */
  private static final String UNKNOWN_TYPE_ID = "UNKNOWN_TYPE";

  /** Custom plugin type identifier. */
  private static final String CUSTOM_ID = "CUSTOM";

  /** Terminal alternative type identifier. */
  private static final String TERMINAL_ALT_ID = "TERMINAL";

  /** Mocked plugin registry. */
  @Mock private PluginRegistry registry;

  /** Mocked trigger plugin. */
  @Mock private TriggerPlugin triggerPlugin;

  /** Mocked processor plugin. */
  @Mock private ProcessorPlugin processorPlugin;

  /** Mocked terminal plugin. */
  @Mock private TerminalPlugin terminalPlugin;

  /** Validator under test. */
  private WorkflowValidator validator;

  @BeforeEach
  void setUp() {
    validator = new WorkflowValidator(registry);
    for (final var plugin : List.of(triggerPlugin, processorPlugin, terminalPlugin)) {
      lenient().when(plugin.getOutputPorts(any())).thenReturn(List.of(DEFAULT_EDGE_LABEL));
    }
  }

  private void assertRejected(final WorkflowDefinition def, final String message) {
    assertThatThrownBy(() -> validator.validate(def).block())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining(message);
  }

  private static WorkflowDefinition.Node node(final String nodeId, final String type) {
    return new WorkflowDefinition.Node(nodeId, type, Map.of());
  }

  @Test
  void definitionHoldingLiteralSecretIsRefused() {
    final WorkflowDefinition literal =
        new WorkflowDefinition(
            "w",
            "d",
            List.of(new WorkflowDefinition.Node("n1", "X", Map.of("password", "hunter2"))),
            List.of());

    assertRejected(literal, "n1.password");
  }

  @Test
  void definitionHoldingTheRedactionMaskIsRefused() {
    final WorkflowDefinition masked =
        new WorkflowDefinition(
            "w",
            "d",
            List.of(
                new WorkflowDefinition.Node(
                    "n1", "X", Map.of("headers", Map.of("Authorization", "******")))),
            List.of());

    StepVerifier.create(validator.validate(masked))
        .expectErrorSatisfies(
            e ->
                org.assertj.core.api.Assertions.assertThat(e)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("n1.headers.Authorization")
                    .hasMessageContaining("decrypted:"))
        .verify();
  }

  @Test
  void testDuplicateNodeIdRejected() {
    assertRejected(
        new WorkflowDefinition(
            WORKFLOW_NAME, "d", List.of(node("a", "T"), node("a", "P")), List.of()),
        "Duplicate node id 'a'");
  }

  @Test
  void testDuplicateEdgeRejected() {
    final var edge = new WorkflowDefinition.Edge("a", "b", null);
    assertRejected(
        new WorkflowDefinition(
            WORKFLOW_NAME, "d", List.of(node("a", "T"), node("b", "P")), List.of(edge, edge)),
        "more than once");
  }

  @Test
  void testUnknownSourcePortRejected() {
    when(registry.get("T")).thenReturn(triggerPlugin);
    assertRejected(
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(node("a", "T"), node("b", "P")),
            List.of(new WorkflowDefinition.Edge("a", "b", "nope"))),
        "output port 'nope'");
  }

  @Test
  void testErrorPortRejectedWithoutRoutePolicy() {
    when(registry.get("T")).thenReturn(triggerPlugin);
    assertRejected(
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(node("a", "T"), node("b", "P")),
            List.of(new WorkflowDefinition.Edge("a", "b", "error"))),
        "output port 'error'");
  }

  @Test
  void testPortOfUnregisteredPluginLeftToRegistryCheck() {
    final var def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(node("a", "T"), node("b", "P")),
            List.of(new WorkflowDefinition.Edge("a", "b", "x")));
    StepVerifier.create(validator.validate(def))
        .expectErrorMatches(e -> !e.getMessage().contains("output port"))
        .verify();
  }

  @Test
  void testRetryBackoffLongerThanTheNodeTimeoutRejected() {
    when(registry.get("P")).thenReturn(processorPlugin);
    final var node =
        new WorkflowDefinition.Node(
            "p",
            "P",
            Map.of(
                "timeoutSeconds",
                10,
                "engine",
                Map.of("retry", Map.of("maxAttempts", 3, "maxBackoffMs", 10_000))));
    assertRejected(
        new WorkflowDefinition(WORKFLOW_NAME, "d", List.of(node), List.of()), "maxBackoffMs");
  }

  @Test
  void testElementTimeoutNotBelowTheNodeTimeoutRejected() {
    when(registry.get("P")).thenReturn(processorPlugin);
    final var node =
        new WorkflowDefinition.Node(
            "p", "P", Map.of("timeoutSeconds", 10, "engine", Map.of("elementTimeoutMs", 10_000)));
    assertRejected(
        new WorkflowDefinition(WORKFLOW_NAME, "d", List.of(node), List.of()), "elementTimeoutMs");
  }

  @Test
  void testElementTimeoutNotBelowTheDeadlineRejected() {
    final var node =
        new WorkflowDefinition.Node(
            "p",
            "P",
            Map.of(
                "timeoutSeconds",
                60,
                "engine",
                Map.of("elementTimeoutMs", 5_000, "deadlineMs", 5_000)));
    assertRejected(
        new WorkflowDefinition(WORKFLOW_NAME, "d", List.of(node), List.of()), "engine.deadlineMs");
  }

  @Test
  void testRetryBackoffUsesThePluginDefaultTimeout() {
    when(registry.get("P")).thenReturn(processorPlugin);
    when(processorPlugin.getDefaultTimeout(any())).thenReturn(Duration.ofSeconds(1));
    final var node =
        new WorkflowDefinition.Node(
            "p",
            "P",
            Map.of("engine", Map.of("retry", Map.of("maxAttempts", 3, "maxBackoffMs", 5_000))));
    assertRejected(
        new WorkflowDefinition(WORKFLOW_NAME, "d", List.of(node), List.of()), "node timeout (1 s)");
  }

  @Test
  void testRetryBackoffWithUnknownPluginIsLeftToThePluginCheck() {
    final var node =
        new WorkflowDefinition.Node(
            "p",
            "P",
            Map.of("engine", Map.of("retry", Map.of("maxAttempts", 3, "maxBackoffMs", 5_000))));
    StepVerifier.create(
            validator.validate(
                new WorkflowDefinition(WORKFLOW_NAME, "d", List.of(node), List.of())))
        .expectErrorMatches(e -> !e.getMessage().contains("maxBackoffMs"))
        .verify();
  }

  @Test
  void testTooManyNodesRejected() {
    final var nodes = new ArrayList<WorkflowDefinition.Node>();
    for (int i = 0; i < 501; i++) {
      nodes.add(node("n" + i, "P"));
    }
    assertRejected(new WorkflowDefinition(WORKFLOW_NAME, "d", nodes, List.of()), "too large");
  }

  @Test
  void testErrorPortAllowedWhenRouted() {
    when(registry.get("P")).thenReturn(processorPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    final var routed =
        new WorkflowDefinition.Node("p", "P", Map.of("engine", Map.of("onError", "ROUTE")));
    final var def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(routed, node("d", "D")),
            List.of(new WorkflowDefinition.Edge("p", "d", "error")));
    StepVerifier.create(validator.validate(def))
        .expectErrorMatches(e -> !e.getMessage().contains("output port"))
        .verify();
  }

  @Test
  void testValidateSuccess() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "ok",
            List.of(
                new WorkflowDefinition.Node("n1", TRIGGER_ID, Map.of()),
                new WorkflowDefinition.Node("n2", PROCESSOR_ID, Map.of()),
                new WorkflowDefinition.Node("n3", TERMINAL_ID, Map.of())),
            List.of(
                new WorkflowDefinition.Edge("n1", "n2", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("n2", "n3", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testValidateErrors() {
    // Sub-case 1: Processor without outgoing (via Guard) — fails at validateProcessors
    // Only needs getCategory; validateConfig/validateInContext are NOT reached
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get(GUARD_ID)).thenReturn(processorPlugin);
    StepVerifier.create(
            validator.validate(
                new WorkflowDefinition(
                    WORKFLOW_NAME,
                    "d",
                    List.of(
                        new WorkflowDefinition.Node("t", "T", Map.of()),
                        new WorkflowDefinition.Node("g", "GUARD", Map.of())),
                    List.of(new WorkflowDefinition.Edge("t", "g", DEFAULT_EDGE_LABEL)))))
        .expectError()
        .verify();

    // Sub-case 2: Endpoint but not terminal (Hybrid is PROCESSOR + TriggerPlugin)
    // Fails at validateEntryPoints (no incoming → entry point but not canBeTrigger)
    // Only needs getCategory

    @SuppressWarnings("PMD.CommentRequired")
    /*  Interface for testing plugins that implement multiple categories.   */
    interface Hybrid extends TriggerPlugin, ProcessorPlugin {
      @Override
      default PluginCategory getCategory() {
        return PluginCategory.PROCESSOR;
      }
    }

    final Hybrid hybrid = org.mockito.Mockito.mock(Hybrid.class);

    when(hybrid.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get(HYBRID_ID)).thenReturn(hybrid);
    StepVerifier.create(
            validator.validate(
                new WorkflowDefinition(
                    WORKFLOW_NAME,
                    "d",
                    List.of(new WorkflowDefinition.Node("h1", "HYBRID", Map.of())),
                    List.of())))
        .expectError()
        .verify();

    // Sub-case 3: Terminal with outgoing — fails at validateEndpoints, only getCategory needed
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);
    StepVerifier.create(
            validator.validate(
                new WorkflowDefinition(
                    WORKFLOW_NAME,
                    "d",
                    List.of(
                        new WorkflowDefinition.Node("t", "T", Map.of()),
                        new WorkflowDefinition.Node("term1", "TERM", Map.of()),
                        new WorkflowDefinition.Node("p1", "P", Map.of())),
                    List.of(
                        new WorkflowDefinition.Edge("t", "term1", DEFAULT_EDGE_LABEL),
                        new WorkflowDefinition.Edge("term1", "p1", DEFAULT_EDGE_LABEL)))))
        .expectError()
        .verify();

    // Sub-case 4: Orphan — fails at validateEntryPoints ("o" has no incoming, not a trigger)
    StepVerifier.create(
            validator.validate(
                new WorkflowDefinition(
                    WORKFLOW_NAME,
                    "d",
                    List.of(
                        new WorkflowDefinition.Node("t", "T", Map.of()),
                        new WorkflowDefinition.Node("term", "TERM", Map.of()),
                        new WorkflowDefinition.Node("o", "TERM", Map.of())),
                    List.of(new WorkflowDefinition.Edge("t", "term", DEFAULT_EDGE_LABEL)))))
        .expectError()
        .verify();
  }

  @Test
  void testMapperAndFilter() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);

    // MAPPER and FILTER share the same ProcessorPlugin mock — same type, same stubs
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(MAPPER_ID)).thenReturn(processorPlugin);
    when(registry.get(FILTER_ID)).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node(
                    "m1", "MAPPER", Map.of("mode", "SCRIPT", "mapping", "payload")),
                new WorkflowDefinition.Node(
                    "m2", "MAPPER", Map.of("mode", "SCRIPT", "mapping", "if(1)for(;;);")),
                new WorkflowDefinition.Node("m4", "MAPPER", Map.of("mode", "PROJECTION")),
                new WorkflowDefinition.Node("f1", "FILTER", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "m1", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("m1", "m2", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("m2", "m4", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("m4", "f1", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("f1", "term", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testProcessorNullPluginContinues() {
    // Fails at validatePluginsRegistered — "UNKNOWN" returns null
    // flatMap may not reach "TERM" before error propagates, so don't stub it
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(registry.get(UNKNOWN_ID)).thenReturn(null);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "UNKNOWN", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p", "term", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testProcessorWithBothIncomingAndOutgoing() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p", "term", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testEndpointNullPluginContinues() {
    // Fails at validatePluginsRegistered — "UNKNOWN_TYPE" returns null
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(registry.get(UNKNOWN_TYPE_ID)).thenReturn(null);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("unknown", "UNKNOWN_TYPE", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "unknown", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testEndpointIsTerminal() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "term", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testNodeContextsCallsPluginValidation() {
    // validateNodeContexts calls validateInContext on ALL nodes (flatMap processes all).
    // validatePluginConfigs (step 8) is NEVER reached because step 7 errors.
    // So: validateInContext is needed; validateConfig is NOT needed for T and TERM.
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateInContext(any(), any()))
        .thenReturn(Mono.error(new IllegalArgumentException("Context validation failed")));
    when(registry.get(CUSTOM_ID)).thenReturn(processorPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("c", "CUSTOM", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "c", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("c", "term", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testCycleDetection() {
    // Cycle p1→p2→p1 — fails at validateNoCycles, before validateNodeContexts/validatePluginConfigs
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p1", "p2", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p2", "p1", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testMultipleOrphans() {
    // p2, term2 are orphans — fails at validateEntryPoints ("p2" is entry but not trigger)
    // flatMap order: t, p1, p2, term1, term2 — error fires when p2 is found as entry non-trigger
    // TERM plugin's getCategory may not be reached, so don't stub it
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("term1", "TERM", Map.of()),
                new WorkflowDefinition.Node("term2", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p1", "term1", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testMultipleTriggers() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t1", "T", Map.of()),
                new WorkflowDefinition.Node("t2", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t1", "p", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("t2", "p", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p", "term", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testProcessorAsEntryPoint() {
    // "p" has no incoming — fails at validateEntryPoints immediately when p is processed
    // "term" may not be reached in flatMap, so don't stub its getCategory
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("p", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(new WorkflowDefinition.Edge("p", "term", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testProcessorWithoutOutgoing() {
    // Fails at validateProcessors — "p" is a processor without outgoing edges
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "p", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testProcessorWithoutIncoming() {
    // "p" has no incoming — fails at validateEntryPoints (entry point but not a trigger)
    // validateEntryPoints uses flatMap so only stubs for nodes actually processed are needed
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "term", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p", "term", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testTerminalNotAsEndpoint() {
    // Fails at validateEndpoints — "term" is terminal but has outgoing edge
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "term", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("term", "p", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testNonTerminalAsEndpoint() {
    // Fails at validateEndpoints — "p" is an endpoint but not terminal
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "p", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testCategoryTerminalWithoutTerminalPlugin_isNotTerminal() {
    // The assembler builds terminals from the type, so the category alone must not pass.
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "p", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testComplexValidWorkflow() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("p3", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p1", "p2", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p2", "p3", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p3", "term", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testDiamondWorkflow() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("p3", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p1", "p2", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p1", "p3", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p2", "term", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p3", "term", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testSelfCycle() {
    // p→p self-cycle — fails at validateNoCycles before validateNodeContexts/validatePluginConfigs
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p", "p", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testTriggerWithoutOutgoing() {
    // Fails at validateEndpoints — trigger is also an endpoint but not terminal
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(new WorkflowDefinition.Node("t", "T", Map.of())),
            List.of());

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testMissingPluginInProcessors() {
    // Fails at validatePluginsRegistered — "UNKNOWN" returns null
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(registry.get(UNKNOWN_ID)).thenReturn(null);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("unknown", "UNKNOWN", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "unknown", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("unknown", "term", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testMissingPluginInEndpoints() {
    // Fails at validatePluginsRegistered — "UNKNOWN" returns null
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(registry.get(UNKNOWN_ID)).thenReturn(null);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("unknown", "UNKNOWN", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "unknown", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testEntryPointIsTerminal() {
    // Fails at validateEntryPoints — terminal is an entry point but not a trigger
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of());

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testComplexCyclePath() {
    // p1→p2→p3→p1 cycle — fails at validateNoCycles before validate*Config/Context
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("p3", "P", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p1", "p2", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p2", "p3", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p3", "p1", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testTriggerWithIncomingEdge() {
    // t2 has incoming edge — fails at validateEntryPoints ("t2 cannot have incoming edges")
    // P and TERM getCategory may not be reached in flatMap before error
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t1", "T", Map.of()),
                new WorkflowDefinition.Node("t2", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t1", "t2", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("t2", "p", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p", "term", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testAllProcessorsValidStructure() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("p3", "P", Map.of()),
                new WorkflowDefinition.Node("p4", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p1", "p2", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p2", "p3", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p3", "p4", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p4", "term", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testTerminalAsEndpointWithOutgoing() {
    // terminal has outgoing — fails at validateEndpoints
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "term", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("term", "p", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testProcessorAsEndpoint() {
    // "p" is endpoint but not terminal — fails at validateEndpoints
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "p", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testMultipleNodes() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t1", "T", Map.of()),
                new WorkflowDefinition.Node("t2", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("term1", "TERM", Map.of()),
                new WorkflowDefinition.Node("term2", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t1", "p1", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("t2", "p2", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p1", "term1", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p2", "term2", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testUnreachableProcessorFromDifferentTrigger() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t1", "T", Map.of()),
                new WorkflowDefinition.Node("t2", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("term1", "TERM", Map.of()),
                new WorkflowDefinition.Node("term2", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t1", "p1", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("t2", "p2", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p1", "term1", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p2", "term2", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testMixedReachableAndUnreachableNodes() {
    // "orphan" has no incoming — fails at validateEntryPoints
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t1", "T", Map.of()),
                new WorkflowDefinition.Node("t2", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("term1", "TERM", Map.of()),
                new WorkflowDefinition.Node("term2", "TERM", Map.of()),
                new WorkflowDefinition.Node("orphan", "P", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t1", "p1", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("t2", "p2", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p1", "term1", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p2", "term2", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testEndpointNodeWithTerminal() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "term", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testNonEndpointNodeWithoutTerminal() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p", "term", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testOrphanNodeReachable() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p", "term", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testOrphanNodeReachableFromDifferentTrigger() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t1", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("term1", "TERM", Map.of()),
                new WorkflowDefinition.Node("t2", "T", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("term2", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t1", "p1", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p1", "term1", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("t2", "p2", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p2", "term2", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testValidateOrphanNodeLogic() {
    // "p2" has no incoming — fails at validateEntryPoints (entry point but not a TRIGGER)
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p1", "term", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("entry point")
                    && error.getMessage().contains("not a TRIGGER"))
        .verify();
  }

  @Test
  void testOrphanNodeUnreachableTheoryProof() {
    // t_hidden has an incoming edge from a terminal — fails at validateEntryPoints
    // ("Trigger node t_hidden cannot have incoming edges")
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t_main", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("term1", "TERM", Map.of()),
                new WorkflowDefinition.Node("t_hidden", "T", Map.of()),
                new WorkflowDefinition.Node("term_unreachable", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t_main", "p1", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p1", "term1", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("term1", "t_hidden", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("t_hidden", "term_unreachable", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("Trigger node")
                    && error.getMessage().contains("cannot have incoming edges"))
        .verify();
  }

  @Test
  void testTerminalNodeWithOutgoingEdge() {
    // term1 has outgoing edge — fails at validateEndpoints
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get(TERMINAL_ALT_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("term1", "TERMINAL", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("term2", "TERMINAL", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p1", "term1", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("term1", "p2", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p2", "term2", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testMissingTriggerNode() {
    // Workflow has only processor and terminal, no trigger
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "missing-trigger",
            List.of(
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(new WorkflowDefinition.Edge("p1", "term", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("must contain at least one TRIGGER node"))
        .verify();
  }

  @Test
  void testMissingTerminalNode() {
    // Workflow has only trigger and processor, no terminal
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "missing-terminal",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "p1", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("must contain at least one TERMINAL node"))
        .verify();
  }

  @Test
  void testOnlyProcessorNodes() {
    // Workflow has only processor nodes, no trigger or terminal
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "only-processors",
            List.of(
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of())),
            List.of(new WorkflowDefinition.Edge("p1", "p2", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("must contain at least one TRIGGER node"))
        .verify();
  }

  @Test
  void testTriggerAsEndpointNotTerminal() {
    // t1 has no outgoing edges and passes validateEntryPoints (it is a trigger/entry point),
    // validateTerminalNodeExists (term exists), and validateProcessors (not a processor).
    // Fails at validateEndpoints line 239: isEndpoint=true && isTerminal=false.
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node("t1", "T", Map.of()),
                new WorkflowDefinition.Node("t2", "T", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(new WorkflowDefinition.Edge("t2", "term", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("t1")
                    && error.getMessage().contains("is an endpoint but not a TERMINAL"))
        .verify();
  }

  @Test
  void testEdgeFromUnknownNodeIsRejected() {
    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(new WorkflowDefinition.Node("n1", TRIGGER_ID, Map.of())),
            List.of(new WorkflowDefinition.Edge("ghost", "n1", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectErrorMatches(e -> e.getMessage().contains("ghost -> n1"))
        .verify();
  }

  @Test
  void testEdgeToUnknownNodeIsRejectedWithMessage() {
    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(new WorkflowDefinition.Node("n1", TRIGGER_ID, Map.of())),
            List.of(new WorkflowDefinition.Edge("n1", "ghost", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("n1 -> ghost")
                    && e.getMessage().contains("unknown node"))
        .verify();
  }

  private WorkflowDefinition singleNodeWithEngine(final Map<String, Object> engine) {
    return new WorkflowDefinition(
        WORKFLOW_NAME,
        "d",
        List.of(new WorkflowDefinition.Node("n1", PROCESSOR_ID, Map.of("engine", engine))),
        List.of());
  }

  @Test
  void testMalformedEnginePolicyIsRejectedNamingTheNode() {
    StepVerifier.create(validator.validate(singleNodeWithEngine(Map.of("onError", "EXPLODE"))))
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().startsWith("Node 'n1': ")
                    && e.getMessage().contains("engine.onError"))
        .verify();
  }

  @Test
  void testRetryOnNonProcessorNodeIsRejected() {
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(
                new WorkflowDefinition.Node(
                    "n1", TRIGGER_ID, Map.of("engine", Map.of("retry", Map.of("maxAttempts", 2))))),
            List.of());

    StepVerifier.create(validator.validate(def))
        .expectErrorMatches(e -> e.getMessage().contains("only supported on processor nodes"))
        .verify();
  }

  @Test
  void testRetryOnAnUnknownPluginTypeLeavesTheReportToThePluginCheck() {
    when(registry.get(PROCESSOR_ID)).thenReturn(null);

    StepVerifier.create(validator.validate(singleNodeWithEngine(Map.of("onError", "CONTINUE"))))
        .expectErrorMatches(e -> !e.getMessage().contains("only supported on processor"))
        .verify();
  }

  @Test
  void testPolicyOnProcessorPassesThePolicyCheck() {
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);

    StepVerifier.create(validator.validate(singleNodeWithEngine(Map.of("onError", "CONTINUE"))))
        .expectErrorMatches(
            e -> e instanceof IllegalArgumentException && !e.getMessage().contains("engine"))
        .verify();
  }

  private WorkflowDefinition singleProcessor(final Map<String, Object> config) {
    return new WorkflowDefinition(
        WORKFLOW_NAME, "d", List.of(new WorkflowDefinition.Node("p", "P", config)), List.of());
  }

  private void assertNotRejectedFor(final WorkflowDefinition def, final String text) {
    StepVerifier.create(validator.validate(def))
        .expectErrorMatches(e -> !e.getMessage().contains(text))
        .verify();
  }

  private void assertNoLimitViolation(final WorkflowDefinition def) {
    StepVerifier.create(validator.validate(def))
        .expectErrorMatches(
            e ->
                !e.getMessage().contains("node timeout")
                    && !e.getMessage().contains("must be below"))
        .verify();
  }

  @Test
  void testTooManyEdgesRejected() {
    final var edges = new ArrayList<WorkflowDefinition.Edge>();
    for (int i = 0; i < 2001; i++) {
      edges.add(new WorkflowDefinition.Edge("a", "b", "p" + i));
    }
    assertRejected(
        new WorkflowDefinition(WORKFLOW_NAME, "d", List.of(node("a", "T"), node("b", "P")), edges),
        "too large");
  }

  @Test
  void testOutputPortLookupFailureIsLeftToOtherChecks() {
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(triggerPlugin.getOutputPorts(any())).thenThrow(new IllegalStateException("bad config"));
    assertRejectedNotForPort(
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(node("a", "T"), node("b", "P")),
            List.of(new WorkflowDefinition.Edge("a", "b", "x"))));
  }

  private void assertRejectedNotForPort(final WorkflowDefinition def) {
    assertNotRejectedFor(def, "output port");
  }

  @Test
  void testNonPositiveConfiguredTimeoutFallsBackToThePluginDefault() {
    when(registry.get("P")).thenReturn(processorPlugin);
    when(processorPlugin.getDefaultTimeout(any())).thenReturn(Duration.ofSeconds(1));
    assertRejected(
        singleProcessor(
            Map.of(
                "timeoutSeconds",
                0,
                "engine",
                Map.of("retry", Map.of("maxAttempts", 3, "maxBackoffMs", 5_000)))),
        "node timeout (1 s)");
  }

  @Test
  void testPoliciesBelowTheLimitsAreAccepted() {
    when(registry.get("P")).thenReturn(processorPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    // retry backoff and element timeout both below timeout and deadline
    assertNoLimitViolation(
        singleProcessor(
            Map.of(
                "timeoutSeconds",
                60,
                "engine",
                Map.of(
                    "retry", Map.of("maxAttempts", 3, "maxBackoffMs", 1_000),
                    "elementTimeoutMs", 2_000,
                    "deadlineMs", 30_000))));
  }

  @Test
  void testRetryWithoutElementTimeoutAndElementTimeoutWithoutRetry() {
    when(registry.get("P")).thenReturn(processorPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    assertNoLimitViolation(
        singleProcessor(
            Map.of(
                "timeoutSeconds",
                60,
                "engine",
                Map.of("retry", Map.of("maxAttempts", 3, "maxBackoffMs", 1_000)))));
    assertNoLimitViolation(
        singleProcessor(Map.of("timeoutSeconds", 60, "engine", Map.of("elementTimeoutMs", 1_000))));
    // deadline without an element timeout
    assertNoLimitViolation(
        singleProcessor(Map.of("timeoutSeconds", 60, "engine", Map.of("deadlineMs", 1_000))));
  }

  @Test
  void testNoKnownNodeTimeoutSkipsTheBackoffCheck() {
    when(registry.get("P")).thenReturn(processorPlugin);
    assertNotRejectedFor(
        singleProcessor(
            Map.of("engine", Map.of("retry", Map.of("maxAttempts", 3, "maxBackoffMs", 5_000)))),
        "maxBackoffMs");
  }

  @Test
  void testPluginConfigValidationFailureIsReported() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    lenient().when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TRIGGER_ID)).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(processorPlugin.validateConfig(any()))
        .thenReturn(Mono.error(new IllegalArgumentException("bad plugin config")));
    when(registry.get(PROCESSOR_ID)).thenReturn(processorPlugin);
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    lenient().when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(TERMINAL_ID)).thenReturn(terminalPlugin);

    final WorkflowDefinition def =
        new WorkflowDefinition(
            WORKFLOW_NAME,
            "d",
            List.of(node("t", TRIGGER_ID), node("p", PROCESSOR_ID), node("term", TERMINAL_ID)),
            List.of(
                new WorkflowDefinition.Edge("t", "p", DEFAULT_EDGE_LABEL),
                new WorkflowDefinition.Edge("p", "term", DEFAULT_EDGE_LABEL)));

    StepVerifier.create(validator.validate(def))
        .expectErrorMatches(e -> e.getMessage().contains("bad plugin config"))
        .verify();
  }
}
