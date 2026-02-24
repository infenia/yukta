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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.jagratha.model.WorkflowDefinition;
import com.infenia.jagratha.model.WorkflowDefinition.Edge;
import com.infenia.jagratha.model.WorkflowDefinition.Node;
import com.infenia.jagratha.plugin.Message;
import com.infenia.jagratha.plugin.PluginCategory;
import com.infenia.jagratha.plugin.TerminalPlugin;
import com.infenia.jagratha.plugin.TriggerPlugin;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class WorkflowOrchestratorImprovementsTest {

  private WorkflowRegistry registry;
  private TaskTrackerService tracker;
  private WorkflowValidator validator;
  private WorkflowOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    registry = mock(WorkflowRegistry.class);
    tracker = mock(TaskTrackerService.class);
    validator = new WorkflowValidator(registry);
    when(tracker.startWorkflow(any(), any(), any())).thenReturn(Mono.empty());
    when(tracker.updateTaskStatus(any(), any(), any(), any(), any())).thenReturn(Mono.empty());
    when(tracker.finishWorkflow(any(), any(), any())).thenReturn(Mono.empty());
    when(tracker.appendLog(any(), any(), any())).thenReturn(Mono.empty());
    orchestrator = new WorkflowOrchestrator(registry, tracker, validator);
  }

  @Test
  void testPrepareWorkflowRollback() {
    Node n1 = new Node("n1", "trigger", Map.of());
    Node n2 = new Node("n2", "terminal", Map.of());
    WorkflowDefinition def =
        new WorkflowDefinition("desc", List.of(n1, n2), List.of(new Edge("n1", "n2")));

    TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(trigger.validateConfig(any())).thenReturn(Mono.empty());
    when(trigger.initialize(any())).thenReturn(Mono.empty());
    when(trigger.shutdown(any())).thenReturn(Mono.empty());

    TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminal.validateConfig(any())).thenReturn(Mono.empty());
    // Fail initialization for terminal
    when(terminal.initialize(any())).thenReturn(Mono.error(new RuntimeException("Init failed")));
    when(terminal.shutdown(any())).thenReturn(Mono.empty());

    when(registry.get("trigger")).thenReturn(trigger);
    when(registry.get("terminal")).thenReturn(terminal);

    StepVerifier.create(orchestrator.prepareWorkflow(def))
        .expectError(RuntimeException.class)
        .verify();

    verify(trigger).shutdown(any());
    verify(terminal).shutdown(any());
  }

  @Test
  void testExecuteWithFlatMapDelayError() {
    String sessionId = "sess-1";
    Node n1 = new Node("t", "type-t", Map.of());
    Node n2 = new Node("term1", "type-term1", Map.of());
    Node n3 = new Node("term2", "type-term2", Map.of());
    WorkflowDefinition defUnique =
        new WorkflowDefinition(
            "desc", List.of(n1, n2, n3), List.of(new Edge("t", "term1"), new Edge("t", "term2")));

    TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(trigger.validateConfig(any())).thenReturn(Mono.empty());
    when(trigger.initialize(any())).thenReturn(Mono.empty());
    when(trigger.start(any(), any()))
        .thenReturn(Flux.just(Message.create(UUID.randomUUID(), "data")));

    TerminalPlugin term1Plugin = mock(TerminalPlugin.class);
    when(term1Plugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(term1Plugin.validateConfig(any())).thenReturn(Mono.empty());
    when(term1Plugin.initialize(any())).thenReturn(Mono.empty());
    // term1 fails AFTER subscribing
    when(term1Plugin.consume(any(), any()))
        .thenAnswer(
            inv ->
                ((Flux<Message>) inv.getArgument(0))
                    .then(Mono.error(new RuntimeException("Term1 failed"))));

    TerminalPlugin term2Plugin = mock(TerminalPlugin.class);
    when(term2Plugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(term2Plugin.validateConfig(any())).thenReturn(Mono.empty());
    when(term2Plugin.initialize(any())).thenReturn(Mono.empty());
    // term2 succeeds
    when(term2Plugin.consume(any(), any()))
        .thenAnswer(inv -> ((Flux<Message>) inv.getArgument(0)).then());

    when(registry.get("type-t")).thenReturn(trigger);
    when(registry.get("type-term1")).thenReturn(term1Plugin);
    when(registry.get("type-term2")).thenReturn(term2Plugin);

    StepVerifier.create(
            orchestrator
                .prepareWorkflow(defUnique)
                .flatMap(p -> orchestrator.execute(sessionId, "test-workflow", p, Map.of())))
        .expectErrorMatches(
            e ->
                e.getMessage().contains("Term1 failed")
                    || (e.getCause() != null && e.getCause().getMessage().contains("Term1 failed")))
        .verify();

    verify(term1Plugin).consume(any(), any());
    verify(term2Plugin).consume(any(), any()); // Verify that term2 was still called
  }

  @Test
  void testExecuteAutoConnectTimeout() {
    String sessionId = "sess-1";
    Node t = new Node("t", "type-t", Map.of());
    Node term1 = new Node("term1", "type-term1", Map.of());
    Node term2 = new Node("term2", "type-term2", Map.of());
    WorkflowDefinition def =
        new WorkflowDefinition(
            "desc",
            List.of(t, term1, term2),
            List.of(new Edge("t", "term1"), new Edge("t", "term2")));

    TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(trigger.validateConfig(any())).thenReturn(Mono.empty());
    when(trigger.initialize(any())).thenReturn(Mono.empty());
    when(trigger.start(any(), any())).thenReturn(Flux.never());

    TerminalPlugin term1Plugin = mock(TerminalPlugin.class);
    when(term1Plugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(term1Plugin.validateConfig(any())).thenReturn(Mono.empty());
    when(term1Plugin.initialize(any())).thenReturn(Mono.empty());
    // This one subscribes and waits
    when(term1Plugin.consume(any(), any()))
        .thenAnswer(
            inv -> {
              Flux<Message> s = inv.getArgument(0);
              return s.then();
            });

    TerminalPlugin term2Plugin = mock(TerminalPlugin.class);
    when(term2Plugin.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(term2Plugin.validateConfig(any())).thenReturn(Mono.empty());
    when(term2Plugin.initialize(any())).thenReturn(Mono.empty());
    // This one DOES NOT subscribe to the stream
    when(term2Plugin.consume(any(), any())).thenReturn(Mono.empty());

    when(registry.get("type-t")).thenReturn(trigger);
    when(registry.get("type-term1")).thenReturn(term1Plugin);
    when(registry.get("type-term2")).thenReturn(term2Plugin);

    // replay(1).refCount(2, 30s) will hang if only 1 subscribes
    StepVerifier.withVirtualTime(
            () ->
                orchestrator
                    .prepareWorkflow(def)
                    .flatMap(p -> orchestrator.execute(sessionId, "test-workflow", p, Map.of())))
        .expectSubscription()
        .thenAwait(Duration.ofSeconds(35))
        .expectError()
        .verify();
  }

  @Test
  void testContextPropagation() {
    String sessionId = "sess-test-context";
    Node t = new Node("t", "trigger", Map.of());
    Node term = new Node("term", "terminal", Map.of());
    WorkflowDefinition def =
        new WorkflowDefinition("desc", List.of(t, term), List.of(new Edge("t", "term")));

    TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(trigger.validateConfig(any())).thenReturn(Mono.empty());
    when(trigger.initialize(any())).thenReturn(Mono.empty());
    when(trigger.start(any(), any()))
        .thenReturn(Flux.just(Message.create(UUID.randomUUID(), "data")));

    TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminal.validateConfig(any())).thenReturn(Mono.empty());
    when(terminal.initialize(any())).thenReturn(Mono.empty());
    when(terminal.consume(any(), any()))
        .thenAnswer(inv -> ((Flux<Message>) inv.getArgument(0)).then());

    when(registry.get("trigger")).thenReturn(trigger);
    when(registry.get("terminal")).thenReturn(terminal);

    StepVerifier.create(
            orchestrator
                .prepareWorkflow(def)
                .flatMap(p -> orchestrator.execute(sessionId, "test-workflow", p, Map.of())))
        .verifyComplete();

    verify(tracker).startWorkflow(eq(sessionId), eq("test-workflow"), any());
    verify(tracker, atLeastOnce())
        .updateTaskStatus(eq(sessionId), eq("test-workflow"), any(), any(), any());
    verify(tracker).finishWorkflow(eq(sessionId), eq("test-workflow"), any());
  }
}
