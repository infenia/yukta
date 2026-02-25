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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.jagratha.config.AppConfigService;
import com.infenia.jagratha.model.WorkflowDefinition;
import com.infenia.jagratha.model.WorkflowDefinition.Edge;
import com.infenia.jagratha.model.WorkflowDefinition.Node;
import com.infenia.jagratha.plugin.Message;
import com.infenia.jagratha.plugin.PluginCategory;
import com.infenia.jagratha.plugin.ProcessorPlugin;
import com.infenia.jagratha.plugin.TerminalPlugin;
import com.infenia.jagratha.plugin.TriggerPlugin;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class GuardIntegrationTest {

  private WorkflowOrchestrator orchestrator;
  private WorkflowRegistry registry;
  private TaskTrackerService tracker;
  private WorkflowValidator validator;
  private AppConfigService configService;

  private TriggerPlugin triggerPlugin;
  private ProcessorPlugin guardPlugin;
  private TerminalPlugin terminalTrue;
  private TerminalPlugin terminalFalse;

  @BeforeEach
  void setUp() {
    registry = mock(WorkflowRegistry.class);
    tracker = mock(TaskTrackerService.class);
    configService = mock(AppConfigService.class);
    validator = new WorkflowValidator(registry);
    when(configService.getExecutionTimeout(anyString())).thenReturn(Mono.just(3600L));
    orchestrator = new WorkflowOrchestrator(registry, tracker, validator, configService);

    triggerPlugin = mock(TriggerPlugin.class);
    when(triggerPlugin.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    guardPlugin = mock(ProcessorPlugin.class);
    when(guardPlugin.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    terminalTrue = mock(TerminalPlugin.class);
    when(terminalTrue.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    terminalFalse = mock(TerminalPlugin.class);
    when(terminalFalse.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));

    when(registry.get("TRIGGER")).thenReturn(triggerPlugin);
    when(registry.get("GUARD")).thenReturn(guardPlugin);
    when(registry.get("TERMINAL")).thenReturn(terminalTrue);

    when(triggerPlugin.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(triggerPlugin.initialize(any())).thenReturn(Mono.empty());
    when(triggerPlugin.validateConfig(any())).thenReturn(Mono.empty());

    when(guardPlugin.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(guardPlugin.initialize(any())).thenReturn(Mono.empty());
    when(guardPlugin.validateConfig(any())).thenReturn(Mono.empty());

    when(terminalTrue.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalTrue.initialize(any())).thenReturn(Mono.empty());
    when(terminalTrue.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalTrue.consume(any(), any())).thenReturn(Mono.empty());

    when(terminalFalse.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminalFalse.initialize(any())).thenReturn(Mono.empty());
    when(terminalFalse.validateConfig(any())).thenReturn(Mono.empty());
    when(terminalFalse.consume(any(), any())).thenReturn(Mono.empty());

    when(tracker.startWorkflow(anyString(), anyString(), anyString(), any()))
        .thenReturn(Mono.empty());
    when(tracker.updateTaskStatus(anyString(), anyString(), anyString(), any()))
        .thenReturn(Mono.empty());
    when(tracker.finishWorkflow(anyString(), anyString())).thenReturn(Mono.empty());
    when(tracker.appendLog(anyString(), anyString())).thenReturn(Mono.empty());
  }

  @SuppressWarnings("unchecked")
  @Test
  void testGuardPruningTrueBranch() {
    final String sessionId = "session-true";
    final UUID traceId = UUID.randomUUID();
    final Message msg = Message.create(traceId, Map.of("amount", 1500));

    when(triggerPlugin.start(any(), any())).thenReturn(Flux.just(msg));
    when(guardPlugin.process(any(), any()))
        .thenAnswer(
            inv -> {
              Flux<Message> in = inv.getArgument(0);
              return in.map(
                  m -> {
                    Map<String, Object> payload = (Map<String, Object>) m.payload();
                    if ((int) payload.get("amount") > 1000) return m.withSourcePort("true");
                    else return m.withSourcePort("false");
                  });
            });

    final Node trigger = new Node("t1", "TRIGGER", Map.of());
    final Node guard = new Node("g1", "GUARD", Map.of("condition", "amount > 1000"));
    final Node termTrue = new Node("termTrue", "TERMINAL", Map.of());
    final Node termFalse = new Node("termFalse", "TERMINAL", Map.of());

    final WorkflowDefinition def =
        new WorkflowDefinition(
            "Guard Test",
            List.of(trigger, guard, termTrue, termFalse),
            List.of(
                new Edge("t1", "g1"),
                new Edge("g1", "termTrue", "true"),
                new Edge("g1", "termFalse", "false")));

    String executionId = "exec-guard-true";
    orchestrator
        .prepareWorkflow(def)
        .flatMap(
            prepared ->
                orchestrator.execute(
                    sessionId, "test-workflow", executionId, prepared, Map.of("start", true)))
        .as(StepVerifier::create)
        .verifyComplete();

    // Verify termTrue was executed
    verify(tracker, atLeastOnce())
        .updateTaskStatus(eq(executionId), eq("termTrue"), anyString(), eq("RUNNING"));
    // Verify termFalse was NOT executed
    verify(tracker, never())
        .updateTaskStatus(eq(executionId), eq("termFalse"), anyString(), eq("RUNNING"));
  }

  @SuppressWarnings("unchecked")
  @Test
  void testGuardPruningFalseBranch() {
    final String sessionId = "session-false";
    final UUID traceId = UUID.randomUUID();
    final Message msg = Message.create(traceId, Map.of("amount", 500));

    when(triggerPlugin.start(any(), any())).thenReturn(Flux.just(msg));
    when(guardPlugin.process(any(), any()))
        .thenAnswer(
            inv -> {
              Flux<Message> in = inv.getArgument(0);
              return in.map(
                  m -> {
                    Map<String, Object> payload = (Map<String, Object>) m.payload();
                    if ((int) payload.get("amount") > 1000) return m.withSourcePort("true");
                    else return m.withSourcePort("false");
                  });
            });

    final Node trigger = new Node("t1", "TRIGGER", Map.of());
    final Node guard = new Node("g1", "GUARD", Map.of("condition", "amount > 1000"));
    final Node termTrue = new Node("termTrue", "TERMINAL", Map.of());
    final Node termFalse = new Node("termFalse", "TERMINAL", Map.of());

    final WorkflowDefinition def =
        new WorkflowDefinition(
            "Guard Test",
            List.of(trigger, guard, termTrue, termFalse),
            List.of(
                new Edge("t1", "g1"),
                new Edge("g1", "termTrue", "true"),
                new Edge("g1", "termFalse", "false")));

    String executionId = "exec-guard-false";
    orchestrator
        .prepareWorkflow(def)
        .flatMap(
            prepared ->
                orchestrator.execute(
                    sessionId, "test-workflow", executionId, prepared, Map.of("start", true)))
        .as(StepVerifier::create)
        .verifyComplete();

    // Verify termFalse was executed
    verify(tracker, atLeastOnce())
        .updateTaskStatus(eq(executionId), eq("termFalse"), anyString(), eq("RUNNING"));
    // Verify termTrue was NOT executed
    verify(tracker, never())
        .updateTaskStatus(eq(executionId), eq("termTrue"), anyString(), eq("RUNNING"));
  }
}
