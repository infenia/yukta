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
package com.infenia.jagratha.service;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.jagratha.model.WorkflowDefinition;
import com.infenia.jagratha.plugin.PluginCategory;
import com.infenia.jagratha.plugin.WorkflowPlugin;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class WorkflowValidatorTest {

  @Mock private WorkflowRegistry registry;
  private WorkflowValidator validator;

  @BeforeEach
  void setUp() {
    validator = new WorkflowValidator(registry);
  }

  @Test
  void testValidateMapperSimpleScriptWarning() {
    WorkflowDefinition.Node trigger = new WorkflowDefinition.Node("t1", "TRIGGER", Map.of());
    WorkflowDefinition.Node mapper =
        new WorkflowDefinition.Node(
            "m1",
            "MAPPER",
            Map.of(
                "mode", "SCRIPT",
                "mapping", "payload.x"));
    WorkflowDefinition.Node terminal = new WorkflowDefinition.Node("term1", "TERMINAL", Map.of());

    WorkflowDefinition.Edge e1 = new WorkflowDefinition.Edge("t1", "m1", null);
    WorkflowDefinition.Edge e2 = new WorkflowDefinition.Edge("m1", "term1", null);

    WorkflowDefinition def =
        new WorkflowDefinition("desc", List.of(trigger, mapper, terminal), List.of(e1, e2));

    WorkflowPlugin triggerPlugin = mock(WorkflowPlugin.class);
    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());

    WorkflowPlugin mapperPlugin = mock(WorkflowPlugin.class);
    when(mapperPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(mapperPlugin.validateConfig(any())).thenReturn(Mono.empty());

    WorkflowPlugin terminalPlugin = mock(WorkflowPlugin.class);
    when(terminalPlugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalPlugin.validateConfig(any())).thenReturn(Mono.empty());

    when(registry.get("TRIGGER")).thenReturn(triggerPlugin);
    when(registry.get("MAPPER")).thenReturn(mapperPlugin);
    when(registry.get("TERMINAL")).thenReturn(terminalPlugin);

    // We expect it to complete successfully, but log a warning
    StepVerifier.create(validator.validate(def)).verifyComplete();
  }
}
