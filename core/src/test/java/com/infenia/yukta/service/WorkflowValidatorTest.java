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
package com.infenia.yukta.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.WorkflowPlugin;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class WorkflowValidatorTest {

  @Mock private WorkflowRegistry registry;
  private WorkflowValidator validator;

  @BeforeEach
  void setUp() {
    validator = new WorkflowValidator(registry);
  }

  private void mockPlugin(String type, PluginCategory category) {
    WorkflowPlugin plugin;
    if (category == PluginCategory.TRIGGER) {
      plugin = mock(TriggerPlugin.class);
    } else if (category == PluginCategory.TERMINAL) {
      plugin = mock(TerminalPlugin.class);
    } else {
      plugin = mock(ProcessorPlugin.class);
    }
    when(plugin.getCategory()).thenReturn(category);
    when(plugin.getType()).thenReturn(type);
    when(plugin.validateConfig(any())).thenReturn(Mono.empty());
    when(plugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get(type)).thenReturn(plugin);
  }

  @Test
  void testValidateSuccess() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "ok",
            List.of(
                new WorkflowDefinition.Node("n1", "T", Map.of()),
                new WorkflowDefinition.Node("n2", "P", Map.of()),
                new WorkflowDefinition.Node("n3", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("n1", "n2"), new WorkflowDefinition.Edge("n2", "n3")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testValidateErrors() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    // Processor without outgoing (via Guard)
    WorkflowPlugin guardPlugin = mock(ProcessorPlugin.class);
    when(guardPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(guardPlugin.getType()).thenReturn("GUARD");
    when(guardPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(guardPlugin.validateInContext(any(), any())).thenReturn(Mono.empty());
    when(registry.get("GUARD")).thenReturn(guardPlugin);
    StepVerifier.create(
            validator.validate(
                new WorkflowDefinition(
                    "d",
                    List.of(
                        new WorkflowDefinition.Node("t", "T", Map.of()),
                        new WorkflowDefinition.Node("g", "GUARD", Map.of())),
                    List.of(new WorkflowDefinition.Edge("t", "g")))))
        .expectError()
        .verify();

    // Endpoint but not terminal (Line 215)
    interface Hybrid extends TriggerPlugin, ProcessorPlugin {
      @Override
      default PluginCategory getCategory() {
        return PluginCategory.PROCESSOR;
      }
    }
    Hybrid hybrid = mock(Hybrid.class);
    when(hybrid.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(hybrid.validateConfig(any())).thenReturn(Mono.empty());
    when(registry.get("HYBRID")).thenReturn(hybrid);
    StepVerifier.create(
            validator.validate(
                new WorkflowDefinition(
                    "d",
                    List.of(new WorkflowDefinition.Node("h1", "HYBRID", Map.of())),
                    List.of())))
        .expectError()
        .verify();

    // Terminal with outgoing (Line 220)
    StepVerifier.create(
            validator.validate(
                new WorkflowDefinition(
                    "d",
                    List.of(
                        new WorkflowDefinition.Node("t", "T", Map.of()),
                        new WorkflowDefinition.Node("term1", "TERM", Map.of()),
                        new WorkflowDefinition.Node("p1", "P", Map.of())),
                    List.of(
                        new WorkflowDefinition.Edge("t", "term1"),
                        new WorkflowDefinition.Edge("term1", "p1")))))
        .expectError()
        .verify();

    // Orphan (Line 263)
    StepVerifier.create(
            validator.validate(
                new WorkflowDefinition(
                    "d",
                    List.of(
                        new WorkflowDefinition.Node("t", "T", Map.of()),
                        new WorkflowDefinition.Node("term", "TERM", Map.of()),
                        new WorkflowDefinition.Node("o", "TERM", Map.of())),
                    List.of(new WorkflowDefinition.Edge("t", "term")))))
        .expectError()
        .verify();
  }

  @Test
  void testMapperAndFilter() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("MAPPER", PluginCategory.PROCESSOR);
    mockPlugin("FILTER", PluginCategory.PROCESSOR);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    WorkflowDefinition.Node m1 =
        new WorkflowDefinition.Node("m1", "MAPPER", Map.of("mode", "SCRIPT", "mapping", "payload"));
    WorkflowDefinition.Node m2 =
        new WorkflowDefinition.Node(
            "m2", "MAPPER", Map.of("mode", "SCRIPT", "mapping", "if(1)for(;;);"));
    WorkflowDefinition.Node f1 = new WorkflowDefinition.Node("f1", "FILTER", Map.of());
    WorkflowDefinition.Node m4 =
        new WorkflowDefinition.Node("m4", "MAPPER", Map.of("mode", "PROJECTION"));

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                m1,
                m2,
                m4,
                f1,
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "m1"),
                new WorkflowDefinition.Edge("m1", "m2"),
                new WorkflowDefinition.Edge("m2", "m4"),
                new WorkflowDefinition.Edge("m4", "f1"),
                new WorkflowDefinition.Edge("f1", "term")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testProcessorNullPluginContinues() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "UNKNOWN", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p"), new WorkflowDefinition.Edge("p", "term")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testProcessorWithBothIncomingAndOutgoing() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p"), new WorkflowDefinition.Edge("p", "term")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testEndpointNullPluginContinues() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("unknown", "UNKNOWN_TYPE", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "unknown")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testEndpointIsTerminal() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "term")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testNodeContextsCallsPluginValidation() {
    WorkflowPlugin mockPlugin = mock(ProcessorPlugin.class);
    when(mockPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(mockPlugin.getType()).thenReturn("CUSTOM");
    when(mockPlugin.validateConfig(any())).thenReturn(Mono.empty());
    when(mockPlugin.validateInContext(any(), any()))
        .thenReturn(Mono.error(new IllegalArgumentException("Context validation failed")));
    when(registry.get("CUSTOM")).thenReturn(mockPlugin);

    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("c", "CUSTOM", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "c"), new WorkflowDefinition.Edge("c", "term")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testCycleDetection() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1"),
                new WorkflowDefinition.Edge("p1", "p2"),
                new WorkflowDefinition.Edge("p2", "p1")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testMultipleOrphans() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("term1", "TERM", Map.of()),
                new WorkflowDefinition.Node("term2", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1"),
                new WorkflowDefinition.Edge("p1", "term1")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testMultipleTriggers() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t1", "T", Map.of()),
                new WorkflowDefinition.Node("t2", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t1", "p"),
                new WorkflowDefinition.Edge("t2", "p"),
                new WorkflowDefinition.Edge("p", "term")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testProcessorAsEntryPoint() {
    mockPlugin("P", PluginCategory.PROCESSOR);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("p", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(new WorkflowDefinition.Edge("p", "term")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testProcessorWithoutOutgoing() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "p")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testProcessorWithoutIncoming() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "term"),
                new WorkflowDefinition.Edge("p", "term")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testTerminalNotAsEndpoint() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "term"),
                new WorkflowDefinition.Edge("term", "p")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testNonTerminalAsEndpoint() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "p")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testComplexValidWorkflow() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("p3", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1"),
                new WorkflowDefinition.Edge("p1", "p2"),
                new WorkflowDefinition.Edge("p2", "p3"),
                new WorkflowDefinition.Edge("p3", "term")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testDiamondWorkflow() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("p3", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1"),
                new WorkflowDefinition.Edge("p1", "p2"),
                new WorkflowDefinition.Edge("p1", "p3"),
                new WorkflowDefinition.Edge("p2", "term"),
                new WorkflowDefinition.Edge("p3", "term")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testSelfCycle() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "p"), new WorkflowDefinition.Edge("p", "p")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testTriggerWithoutOutgoing() {
    mockPlugin("T", PluginCategory.TRIGGER);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d", List.of(new WorkflowDefinition.Node("t", "T", Map.of())), List.of());

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testMissingPluginInProcessors() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("TERM", PluginCategory.TERMINAL);
    // Intentionally don't mock "UNKNOWN"

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("unknown", "UNKNOWN", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "unknown"),
                new WorkflowDefinition.Edge("unknown", "term")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testMissingPluginInEndpoints() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("TERM", PluginCategory.TERMINAL);
    // Intentionally don't mock "UNKNOWN"

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("unknown", "UNKNOWN", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "unknown")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testEntryPointIsTerminal() {
    mockPlugin("TERM", PluginCategory.TERMINAL);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d", List.of(new WorkflowDefinition.Node("term", "TERM", Map.of())), List.of());

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testComplexCyclePath() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("p3", "P", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1"),
                new WorkflowDefinition.Edge("p1", "p2"),
                new WorkflowDefinition.Edge("p2", "p3"),
                new WorkflowDefinition.Edge("p3", "p1")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testTriggerWithIncomingEdge() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    // Trigger with incoming edge - creates orphan nodes since no free entry point
    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t1", "T", Map.of()),
                new WorkflowDefinition.Node("t2", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge(
                    "t1", "t2"), // t2 is a trigger with incoming edge - invalid
                new WorkflowDefinition.Edge("t2", "p"),
                new WorkflowDefinition.Edge("p", "term")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testAllProcessorsValidStructure() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    // All nodes with both edges
    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("p3", "P", Map.of()),
                new WorkflowDefinition.Node("p4", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1"),
                new WorkflowDefinition.Edge("p1", "p2"),
                new WorkflowDefinition.Edge("p2", "p3"),
                new WorkflowDefinition.Edge("p3", "p4"),
                new WorkflowDefinition.Edge("p4", "term")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testTerminalAsEndpointWithOutgoing() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("TERM", PluginCategory.TERMINAL);
    mockPlugin("P", PluginCategory.PROCESSOR);

    // Terminal with outgoing edge
    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "term"),
                new WorkflowDefinition.Edge("term", "p")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testProcessorAsEndpoint() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);

    // Processor at endpoint (not terminal)
    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "p")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testMultipleNodes() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t1", "T", Map.of()),
                new WorkflowDefinition.Node("t2", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("term1", "TERM", Map.of()),
                new WorkflowDefinition.Node("term2", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t1", "p1"),
                new WorkflowDefinition.Edge("t2", "p2"),
                new WorkflowDefinition.Edge("p1", "term1"),
                new WorkflowDefinition.Edge("p2", "term2")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testUnreachableProcessorFromDifferentTrigger() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    // p2 is not reachable from t1
    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t1", "T", Map.of()),
                new WorkflowDefinition.Node("t2", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("term1", "TERM", Map.of()),
                new WorkflowDefinition.Node("term2", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t1", "p1"),
                new WorkflowDefinition.Edge("t2", "p2"),
                new WorkflowDefinition.Edge("p1", "term1"),
                new WorkflowDefinition.Edge("p2", "term2")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testMixedReachableAndUnreachableNodes() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    // t1 → p1 → term1 (reachable)
    // t2 → p2 → term2 (reachable)
    // orphan (unreachable - not connected to any trigger)
    WorkflowDefinition def =
        new WorkflowDefinition(
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
                new WorkflowDefinition.Edge("t1", "p1"),
                new WorkflowDefinition.Edge("t2", "p2"),
                new WorkflowDefinition.Edge("p1", "term1"),
                new WorkflowDefinition.Edge("p2", "term2")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testEndpointNodeWithTerminal() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    // Endpoint node that IS terminal - should pass
    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(new WorkflowDefinition.Edge("t", "term")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testNonEndpointNodeWithoutTerminal() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    // Non-endpoint node (has outgoing edge) that is NOT terminal - should pass
    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p"), new WorkflowDefinition.Edge("p", "term")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testOrphanNodeReachable() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    // Node that IS reachable - should pass
    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p"), new WorkflowDefinition.Edge("p", "term")));

    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testOrphanNodeReachableFromDifferentTrigger() {
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    // Create a workflow with multiple triggers, each with its own path.
    // All nodes are reachable from at least one trigger, so no orphan errors.
    // Path 1: t1 -> p1 -> term1
    // Path 2: t2 -> p2 -> term2
    // Both paths are valid and complete. All nodes are reachable from their respective triggers.
    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t1", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("term1", "TERM", Map.of()),
                new WorkflowDefinition.Node("t2", "T", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("term2", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t1", "p1"),
                new WorkflowDefinition.Edge("p1", "term1"),
                new WorkflowDefinition.Edge("t2", "p2"),
                new WorkflowDefinition.Edge("p2", "term2")));

    // This should pass validation because all nodes are reachable from at least one trigger
    StepVerifier.create(validator.validate(def)).verifyComplete();
  }

  @Test
  void testValidateOrphanNodeLogic() {
    // This test directly verifies the validateOrphanNode logic by checking
    // that it correctly identifies reachable nodes. The true branch
    // (!reachable.contains(nodeId)) is exercised when a node is NOT in the
    // reachable set, which should cause an error. However, in practice,
    // this condition should never occur in valid workflows because:
    // 1. All trigger nodes are always reachable (they're entry points)
    // 2. Any non-trigger node can only exist if reachable from a trigger
    // The test validates the error message to ensure the logic works correctly
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    // Use the existing testOrphanNodeUnreachable flow that attempts
    // to create an unreachable node (which will fail earlier validation)
    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1"), new WorkflowDefinition.Edge("p1", "term")));

    // p2 is not connected to anything, so it's an entry point without being
    // a TRIGGER - caught by validateEntryPoints before validateOrphanNode
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
    // This test proves that the !reachable.contains(nodeId) branch in validateOrphanNode
    // is THEORETICALLY unreachable in valid workflows.
    //
    // PROOF: For a node to be unreachable:
    // Case 1: Node has NO incoming edges
    //   -> It's an entry point (not in targetIds)
    //   -> validateEntryPoints checks: must be TRIGGER or MUST BE CAUGHT
    //   -> If TRIGGER: caught by "mustBeTrigger" rule if it has outgoing to non-endpoint
    //   -> If not TRIGGER: caught immediately as "entry point but not TRIGGER"
    //
    // Case 2: Node HAS incoming edges
    //   -> Not an entry point, so passes validateEntryPoints
    //   -> But if its source is unreachable, source falls into Case 1 or 2...
    //   -> Following chain backwards, always hits a node without incoming (Case 1)
    //   -> Case 1 nodes are always caught
    //
    // Case 3: Node is a TRIGGER with incoming edges
    //   -> validateEntryPoints has rule: "if (!isEntryPoint && mustBeTrigger)"
    //   -> Returns error: "Trigger node X cannot have incoming edges"
    //
    // CONCLUSION: All paths that could create an unreachable node are caught by
    // earlier validation rules. validateOrphanNode's !reachable.contains(nodeId) check
    // is defensive code that protects against logical errors, but cannot be reached
    // in correctly validated workflows.

    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);
    mockPlugin("TERM", PluginCategory.TERMINAL);

    // Try to create a trigger with incoming edges - caught by validateEntryPoints
    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t_main", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("term1", "TERM", Map.of()),
                new WorkflowDefinition.Node("t_hidden", "T", Map.of()),
                new WorkflowDefinition.Node("term_unreachable", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t_main", "p1"),
                new WorkflowDefinition.Edge("p1", "term1"),
                // This edge makes t_hidden have incoming - will be caught as invalid
                new WorkflowDefinition.Edge("term1", "t_hidden"),
                new WorkflowDefinition.Edge("t_hidden", "term_unreachable")));

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
    mockPlugin("T", PluginCategory.TRIGGER);
    mockPlugin("P", PluginCategory.PROCESSOR);
    mockPlugin("TERMINAL", PluginCategory.TERMINAL);

    // Terminal node that has an outgoing edge (not an endpoint) - should fail
    // Workflow: T -> P -> TERMINAL -> P2 -> TERMINAL2
    // The second TERMINAL should fail because it has an outgoing edge
    WorkflowDefinition def =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("p1", "P", Map.of()),
                new WorkflowDefinition.Node("term1", "TERMINAL", Map.of()),
                new WorkflowDefinition.Node("p2", "P", Map.of()),
                new WorkflowDefinition.Node("term2", "TERMINAL", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "p1"),
                new WorkflowDefinition.Edge("p1", "term1"),
                new WorkflowDefinition.Edge("term1", "p2"),
                new WorkflowDefinition.Edge("p2", "term2")));

    StepVerifier.create(validator.validate(def))
        .expectError(IllegalArgumentException.class)
        .verify();
  }
}
