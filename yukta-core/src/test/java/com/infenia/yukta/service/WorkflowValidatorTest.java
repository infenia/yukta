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

import com.infenia.yukta.model.WorkflowDefinition;
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

    // Guard without outgoing (Line 176)
    WorkflowPlugin guardPlugin = mock(ProcessorPlugin.class);
    when(guardPlugin.getCategory())
        .thenReturn(PluginCategory.TERMINAL); // Mock as Terminal to bypass Processor validation
    when(guardPlugin.getType()).thenReturn("GUARD");
    when(guardPlugin.validateConfig(any())).thenReturn(Mono.empty());
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

    // Guard custom error port (Line 188, 189)
    WorkflowDefinition defG =
        new WorkflowDefinition(
            "d",
            List.of(
                new WorkflowDefinition.Node("t", "T", Map.of()),
                new WorkflowDefinition.Node("g", "GUARD", Map.of("errorPort", "custom")),
                new WorkflowDefinition.Node("term", "TERM", Map.of())),
            List.of(
                new WorkflowDefinition.Edge("t", "g"),
                new WorkflowDefinition.Edge("g", "term", "custom")));
    StepVerifier.create(validator.validate(defG)).expectError().verify();

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
}
