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
package com.infenia.yukta.service.orchestrator.validator;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import com.infenia.yukta.service.plugin.PluginRegistry;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@MockitoSettings
class WorkflowValidatorTest {

  @Mock private PluginRegistry registry;
  @Mock private TriggerPlugin triggerPlugin;
  @Mock private ProcessorPlugin processorPlugin;
  @Mock private TerminalPlugin terminalPlugin;

  private WorkflowValidator validator;

  @BeforeEach
  void setUp() {
    validator = new WorkflowValidator(registry);
  }

  @Test
  void testValidateSuccess() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("T")).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("P")).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "ok",
            List.of(
                new WorkflowDefinition.Node("n1", "T", Map.of()),
                new WorkflowDefinition.Node("n2", "P", Map.of()),
                new WorkflowDefinition.Node("n3", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("n1", "n2", "default"),
                new WorkflowDefinition.Edge("n2", "n3", "default")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testValidateErrors() {
    // Sub-case 1: Processor without outgoing (via Guard) — fails at validateProcessors
    // Only needs getCategory; validateConfig/validateInContext are NOT reached
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get("GUARD")).thenReturn(processorPlugin);
    StepVerifier.create(
            validator.validate(
                new WorkflowDefinition(
                    "test-workflow",
                    "d",
                    List.of(
                        new WorkflowDefinition.Node("t", "T", Map.of()),
                        new WorkflowDefinition.Node("g", "GUARD", Map.of())),
                    List.of(new WorkflowDefinition.Edge("t", "g", "default")))))
        .expectError()
        .verify();

    // Sub-case 2: Endpoint but not terminal (Hybrid is PROCESSOR + TriggerPlugin)
    // Fails at validateEntryPoints (no incoming → entry point but not canBeTrigger)
    // Only needs getCategory
    interface Hybrid extends TriggerPlugin, ProcessorPlugin {
      @Override
      default PluginCategory getCategory() {
        return PluginCategory.PROCESSOR;
      }
    }
    Hybrid hybrid = org.mockito.Mockito.mock(Hybrid.class);
    when(hybrid.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get("HYBRID")).thenReturn(hybrid);
    StepVerifier.create(
            validator.validate(
                new WorkflowDefinition(
                    "test-workflow",
                    "d",
                    List.of(new WorkflowDefinition.Node("h1", "HYBRID", Map.of())),
                    List.of())))
        .expectError()
        .verify();

    // Sub-case 3: Terminal with outgoing — fails at validateEndpoints, only getCategory needed
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get("TERM")).thenReturn(terminalPlugin);
    StepVerifier.create(
            validator.validate(
                new WorkflowDefinition(
                    "test-workflow",
                    "d",
                    List.of(
                        new WorkflowDefinition.Node("t", "T", Map.of()),
                        new WorkflowDefinition.Node("term1", "TERM", Map.of()),
                        new WorkflowDefinition.Node("p1", "P", Map.of())),
                    List.of(
                        new WorkflowDefinition.Edge("t", "term1", "default"),
                        new WorkflowDefinition.Edge("term1", "p1", "default")))))
        .expectError()
        .verify();

    // Sub-case 4: Orphan — fails at validateEntryPoints ("o" has no incoming, not a trigger)
    StepVerifier.create(
            validator.validate(
                new WorkflowDefinition(
                    "test-workflow",
                    "d",
                    List.of(
                        new WorkflowDefinition.Node("t", "T", Map.of()),
                        new WorkflowDefinition.Node("term", "TERM", Map.of()),
                        new WorkflowDefinition.Node("o", "TERM", Map.of())),
                    List.of(new WorkflowDefinition.Edge("t", "term", "default")))))
        .expectError()
        .verify();
  }

  @Test
  void testMapperAndFilter() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("T")).thenReturn(triggerPlugin);

    // MAPPER and FILTER share the same ProcessorPlugin mock — same type, same stubs
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("MAPPER")).thenReturn(processorPlugin);
    when(registry.get("FILTER")).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
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
                new WorkflowDefinition.Edge("t", "m1", "default"),
                new WorkflowDefinition.Edge("m1", "m2", "default"),
                new WorkflowDefinition.Edge("m2", "m4", "default"),
                new WorkflowDefinition.Edge("m4", "f1", "default"),
                new WorkflowDefinition.Edge("f1", "term", "default")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testProcessorNullPluginContinues() {
    // Fails at validatePluginsRegistered — "UNKNOWN" returns null
    // flatMap may not reach "TERM" before error propagates, so don't stub it
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(registry.get("UNKNOWN")).thenReturn(null);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "UNKNOWN", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p", "default"),
                new WorkflowDefinition.Edge("p", "term", "default")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testProcessorWithBothIncomingAndOutgoing() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("T")).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("P")).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p", "default"),
                new WorkflowDefinition.Edge("p", "term", "default")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testEndpointNullPluginContinues() {
    // Fails at validatePluginsRegistered — "UNKNOWN_TYPE" returns null
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(registry.get("UNKNOWN_TYPE")).thenReturn(null);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("unknown", "UNKNOWN_TYPE", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "unknown", "default")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testEndpointIsTerminal() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("T")).thenReturn(triggerPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "term", "default")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testNodeContextsCallsPluginValidation() {
    // validateNodeContexts calls validateInContext on ALL nodes (flatMap processes all).
    // validatePluginConfigs (step 8) is NEVER reached because step 7 errors.
    // So: validateInContext is needed; validateConfig is NOT needed for T and TERM.
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("T")).thenReturn(triggerPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateInContext(any(), any()))
        .thenReturn(Mono.error(new IllegalArgumentException("Context validation failed")));
    when(registry.get("CUSTOM")).thenReturn(processorPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("c", "CUSTOM", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "c", "default"),
                new WorkflowDefinition.Edge("c", "term", "default")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testCycleDetection() {
    // Cycle p1→p2→p1 — fails at validateNoCycles, before validateNodeContexts/validatePluginConfigs
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get("P")).thenReturn(processorPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1", "default"),
                new WorkflowDefinition.Edge("p1", "p2", "default"),
                new WorkflowDefinition.Edge("p2", "p1", "default")));

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
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get("P")).thenReturn(processorPlugin);
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("term1", "TERM", Map.of()),
                new WorkflowDefinition.Node("term2", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1", "default"),
                new WorkflowDefinition.Edge("p1", "term1", "default")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testMultipleTriggers() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("T")).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("P")).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t1", "T", Map.of()),
                new WorkflowDefinition.Node("t2", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t1", "p", "default"),
                new WorkflowDefinition.Edge("t2", "p", "default"),
                new WorkflowDefinition.Edge("p", "term", "default")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testProcessorAsEntryPoint() {
    // "p" has no incoming — fails at validateEntryPoints immediately when p is processed
    // "term" may not be reached in flatMap, so don't stub its getCategory
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get("P")).thenReturn(processorPlugin);
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("p", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(new WorkflowDefinition.Edge("p", "term", "default")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testProcessorWithoutOutgoing() {
    // Fails at validateProcessors — "p" is a processor without outgoing edges
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get("P")).thenReturn(processorPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "p", "default")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testProcessorWithoutIncoming() {
    // "p" has no incoming — fails at validateEntryPoints (entry point but not a trigger)
    // validateEntryPoints uses flatMap so only stubs for nodes actually processed are needed
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get("P")).thenReturn(processorPlugin);
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "term", "default"),
                new WorkflowDefinition.Edge("p", "term", "default")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testTerminalNotAsEndpoint() {
    // Fails at validateEndpoints — "term" is terminal but has outgoing edge
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get("TERM")).thenReturn(terminalPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get("P")).thenReturn(processorPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "term", "default"),
                new WorkflowDefinition.Edge("term", "p", "default")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testNonTerminalAsEndpoint() {
    // Fails at validateEndpoints — "p" is an endpoint but not terminal
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get("P")).thenReturn(processorPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "p", "default")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testComplexValidWorkflow() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("T")).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("P")).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("p3", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1", "default"),
                new WorkflowDefinition.Edge("p1", "p2", "default"),
                new WorkflowDefinition.Edge("p2", "p3", "default"),
                new WorkflowDefinition.Edge("p3", "term", "default")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testDiamondWorkflow() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("T")).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("P")).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("p3", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1", "default"),
                new WorkflowDefinition.Edge("p1", "p2", "default"),
                new WorkflowDefinition.Edge("p1", "p3", "default"),
                new WorkflowDefinition.Edge("p2", "term", "default"),
                new WorkflowDefinition.Edge("p3", "term", "default")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testSelfCycle() {
    // p→p self-cycle — fails at validateNoCycles before validateNodeContexts/validatePluginConfigs
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get("P")).thenReturn(processorPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p", "default"),
                new WorkflowDefinition.Edge("p", "p", "default")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testTriggerWithoutOutgoing() {
    // Fails at validateEndpoints — trigger is also an endpoint but not terminal
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get("T")).thenReturn(triggerPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
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
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(registry.get("UNKNOWN")).thenReturn(null);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("unknown", "UNKNOWN", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "unknown", "default"),
                new WorkflowDefinition.Edge("unknown", "term", "default")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testMissingPluginInEndpoints() {
    // Fails at validatePluginsRegistered — "UNKNOWN" returns null
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(registry.get("UNKNOWN")).thenReturn(null);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("unknown", "UNKNOWN", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "unknown", "default")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testEntryPointIsTerminal() {
    // Fails at validateEntryPoints — terminal is an entry point but not a trigger
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
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
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get("P")).thenReturn(processorPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("p3", "P", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1", "default"),
                new WorkflowDefinition.Edge("p1", "p2", "default"),
                new WorkflowDefinition.Edge("p2", "p3", "default"),
                new WorkflowDefinition.Edge("p3", "p1", "default")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testTriggerWithIncomingEdge() {
    // t2 has incoming edge — fails at validateEntryPoints ("t2 cannot have incoming edges")
    // P and TERM getCategory may not be reached in flatMap before error
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(registry.get("P")).thenReturn(processorPlugin);
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t1", "T", Map.of()),
                new WorkflowDefinition.Node("t2", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t1", "t2", "default"),
                new WorkflowDefinition.Edge("t2", "p", "default"),
                new WorkflowDefinition.Edge("p", "term", "default")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testAllProcessorsValidStructure() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("T")).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("P")).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("p3", "P", Map.of()),
                new WorkflowDefinition.Node("p4", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1", "default"),
                new WorkflowDefinition.Edge("p1", "p2", "default"),
                new WorkflowDefinition.Edge("p2", "p3", "default"),
                new WorkflowDefinition.Edge("p3", "p4", "default"),
                new WorkflowDefinition.Edge("p4", "term", "default")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testTerminalAsEndpointWithOutgoing() {
    // terminal has outgoing — fails at validateEndpoints
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get("TERM")).thenReturn(terminalPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get("P")).thenReturn(processorPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "term", "default"),
                new WorkflowDefinition.Edge("term", "p", "default")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testProcessorAsEndpoint() {
    // "p" is endpoint but not terminal — fails at validateEndpoints
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get("P")).thenReturn(processorPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "p", "default")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testMultipleNodes() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("T")).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("P")).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t1", "T", Map.of()),
                new WorkflowDefinition.Node("t2", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("term1", "TERM", Map.of()),
                new WorkflowDefinition.Node("term2", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t1", "p1", "default"),
                new WorkflowDefinition.Edge("t2", "p2", "default"),
                new WorkflowDefinition.Edge("p1", "term1", "default"),
                new WorkflowDefinition.Edge("p2", "term2", "default")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testUnreachableProcessorFromDifferentTrigger() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("T")).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("P")).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t1", "T", Map.of()),
                new WorkflowDefinition.Node("t2", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("term1", "TERM", Map.of()),
                new WorkflowDefinition.Node("term2", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t1", "p1", "default"),
                new WorkflowDefinition.Edge("t2", "p2", "default"),
                new WorkflowDefinition.Edge("p1", "term1", "default"),
                new WorkflowDefinition.Edge("p2", "term2", "default")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testMixedReachableAndUnreachableNodes() {
    // "orphan" has no incoming — fails at validateEntryPoints
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get("P")).thenReturn(processorPlugin);
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
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
                new WorkflowDefinition.Edge("t1", "p1", "default"),
                new WorkflowDefinition.Edge("t2", "p2", "default"),
                new WorkflowDefinition.Edge("p1", "term1", "default"),
                new WorkflowDefinition.Edge("p2", "term2", "default")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testEndpointNodeWithTerminal() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("T")).thenReturn(triggerPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "term", "default")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testNonEndpointNodeWithoutTerminal() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("T")).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("P")).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p", "default"),
                new WorkflowDefinition.Edge("p", "term", "default")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testOrphanNodeReachable() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("T")).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("P")).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p", "default"),
                new WorkflowDefinition.Edge("p", "term", "default")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testOrphanNodeReachableFromDifferentTrigger() {
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("T")).thenReturn(triggerPlugin);

    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processorPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(processorPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("P")).thenReturn(processorPlugin);

    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t1", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("term1", "TERM", Map.of()),
                new WorkflowDefinition.Node("t2", "T", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("term2", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t1", "p1", "default"),
                new WorkflowDefinition.Edge("p1", "term1", "default"),
                new WorkflowDefinition.Edge("t2", "p2", "default"),
                new WorkflowDefinition.Edge("p2", "term2", "default")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testValidateOrphanNodeLogic() {
    // "p2" has no incoming — fails at validateEntryPoints (entry point but not a TRIGGER)
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get("P")).thenReturn(processorPlugin);
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1", "default"),
                new WorkflowDefinition.Edge("p1", "term", "default")));

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
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get("P")).thenReturn(processorPlugin);
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t_main", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("term1", "TERM", Map.of()),
                new WorkflowDefinition.Node("t_hidden", "T", Map.of()),
                new WorkflowDefinition.Node("term_unreachable", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t_main", "p1", "default"),
                new WorkflowDefinition.Edge("p1", "term1", "default"),
                new WorkflowDefinition.Edge("term1", "t_hidden", "default"),
                new WorkflowDefinition.Edge("t_hidden", "term_unreachable", "default")));

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
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get("P")).thenReturn(processorPlugin);
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get("TERMINAL")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("term1", "TERMINAL", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("term2", "TERMINAL", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1", "default"),
                new WorkflowDefinition.Edge("p1", "term1", "default"),
                new WorkflowDefinition.Edge("term1", "p2", "default"),
                new WorkflowDefinition.Edge("p2", "term2", "default")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testMissingTriggerNode() {
    // Workflow has only processor and terminal, no trigger
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get("P")).thenReturn(processorPlugin);
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "missing-trigger",
            List.of(
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(new WorkflowDefinition.Edge("p1", "term", "default")));

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
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(processorPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get("P")).thenReturn(processorPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "missing-terminal",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "p1", "default")));

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
    when(registry.get("P")).thenReturn(processorPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "only-processors",
            List.of(
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of())),
            List.of(new WorkflowDefinition.Edge("p1", "p2", "default")));

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
    when(registry.get("T")).thenReturn(triggerPlugin);
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(registry.get("TERM")).thenReturn(terminalPlugin);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "test-workflow",
            "d",
            List.of(
                new WorkflowDefinition.Node("t1", "T", Map.of()),
                new WorkflowDefinition.Node("t2", "T", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(new WorkflowDefinition.Edge("t2", "term", "default")));

    StepVerifier.create(validator.validate(def))
        .expectErrorMatches(
            error ->
                error instanceof IllegalArgumentException
                    && error.getMessage().contains("t1")
                    && error.getMessage().contains("is an endpoint but not a TERMINAL"))
        .verify();
  }
}
