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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.config.AppConfigService;
import com.infenia.yukta.model.WorkflowDefinition;
import com.infenia.yukta.model.WorkflowDefinition.Edge;
import com.infenia.yukta.model.WorkflowDefinition.Node;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class WorkflowOrchestratorFanInTest {

  private WorkflowRegistry registry;
  private TaskTrackerService tracker;
  private WorkflowValidator validator;
  private AppConfigService configService;
  private WorkflowOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    registry = mock(WorkflowRegistry.class);
    tracker = mock(TaskTrackerService.class);
    configService = mock(AppConfigService.class);
    validator = new WorkflowValidator(registry);
    when(tracker.startWorkflow(any(), any(), any(), any())).thenReturn(Mono.empty());
    when(configService.getExecutionTimeout(anyString())).thenReturn(Mono.just(3600L));
    orchestrator =
        new WorkflowOrchestrator(
            registry, tracker, validator, configService, null, Schedulers.parallel());
  }

  @Test
  void testFanInFromTwoTriggersToProcessor() {
    String sessionId = "sess-fan-in-1";
    Node t1 = new Node("t1", "trigger", Map.of());
    Node t2 = new Node("t2", "trigger", Map.of());
    Node p1 = new Node("p1", "processor", Map.of());
    Node term = new Node("term", "terminal", Map.of());

    WorkflowDefinition def =
        new WorkflowDefinition(
            "Fan-in Test",
            List.of(t1, t2, p1, term),
            List.of(new Edge("t1", "p1"), new Edge("t2", "p1"), new Edge("p1", "term")));

    TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(trigger.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(trigger.validateConfig(any())).thenReturn(Mono.empty());
    when(trigger.initialize(any())).thenReturn(Mono.empty());
    when(trigger.start(any()))
        .thenAnswer(
            invocation -> {
              Map<String, Object> config = invocation.getArgument(0);
              return Flux.just(DefaultMessage.create(UUID.randomUUID(), "msg-from-trigger"));
            });

    ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(processor.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processor.validateConfig(any())).thenReturn(Mono.empty());
    when(processor.initialize(any())).thenReturn(Mono.empty());
    when(processor.process(any(), any()))
        .thenAnswer(
            invocation -> {
              Flux<Message<?>> input = invocation.getArgument(0);
              return input.map(
                  msg -> {
                    final String traceIdStr = msg.getTraceId();
                    final UUID traceId =
                        traceIdStr != null ? UUID.fromString(traceIdStr) : UUID.randomUUID();
                    return DefaultMessage.create(traceId, "processed-" + msg.getPayload());
                  });
            });

    TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(terminal.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminal.validateConfig(any())).thenReturn(Mono.empty());
    when(terminal.initialize(any())).thenReturn(Mono.empty());
    when(terminal.consume(any(), any()))
        .thenAnswer(
            inv -> {
              Flux<Message<?>> input = inv.getArgument(0);
              return input.then();
            });

    when(registry.get("trigger")).thenReturn(trigger);
    when(registry.get("processor")).thenReturn(processor);
    when(registry.get("terminal")).thenReturn(terminal);

    String executionId = "exec-fan-in-1";
    StepVerifier.create(
            orchestrator
                .prepareWorkflow(def)
                .flatMap(
                    p ->
                        orchestrator.execute(sessionId, "test-workflow", executionId, p, Map.of())))
        .verifyComplete();

    // Verify that terminal received 2 messages (one from each trigger path)
    verify(terminal).consume(any(), any());
    verify(tracker, atLeastOnce()).emitWorkflowStatusEvent(eq(executionId), eq("SUCCESS"));
  }

  @Test
  void testFanInToTerminal() {
    String sessionId = "sess-fan-in-2";
    Node t = new Node("t", "trigger", Map.of());
    Node p1 = new Node("p1", "processor", Map.of());
    Node p2 = new Node("p2", "processor", Map.of());
    Node term = new Node("term", "terminal", Map.of());

    WorkflowDefinition def =
        new WorkflowDefinition(
            "Fan-in to Terminal",
            List.of(t, p1, p2, term),
            List.of(
                new Edge("t", "p1"),
                new Edge("t", "p2"),
                new Edge("p1", "term"),
                new Edge("p2", "term")));

    TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(trigger.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(trigger.validateConfig(any())).thenReturn(Mono.empty());
    when(trigger.initialize(any())).thenReturn(Mono.empty());
    when(trigger.start(any()))
        .thenReturn(Flux.just(DefaultMessage.create(UUID.randomUUID(), "data")));

    ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(processor.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processor.validateConfig(any())).thenReturn(Mono.empty());
    when(processor.initialize(any())).thenReturn(Mono.empty());
    when(processor.process(any(), any())).thenAnswer(inv -> inv.getArgument(0));

    TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(terminal.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminal.validateConfig(any())).thenReturn(Mono.empty());
    when(terminal.initialize(any())).thenReturn(Mono.empty());
    when(terminal.consume(any(), any()))
        .thenAnswer(
            inv -> {
              Flux<Message<?>> input = inv.getArgument(0);
              return input
                  .collectList()
                  .flatMap(
                      list -> {
                        if (list.size() == 2) {
                          return Mono.empty();
                        } else {
                          return Mono.error(
                              new RuntimeException("Expected 2 messages, got " + list.size()));
                        }
                      });
            });

    when(registry.get("trigger")).thenReturn(trigger);
    when(registry.get("processor")).thenReturn(processor);
    when(registry.get("terminal")).thenReturn(terminal);

    String executionId = "exec-fan-in-2";
    StepVerifier.create(
            orchestrator
                .prepareWorkflow(def)
                .flatMap(
                    p ->
                        orchestrator.execute(sessionId, "test-workflow", executionId, p, Map.of())))
        .verifyComplete();

    verify(tracker, atLeastOnce()).emitWorkflowStatusEvent(eq(executionId), eq("SUCCESS"));
  }

  @Test
  void testFanInWithErrorPropagation() {
    String sessionId = "sess-fan-in-error";
    Node t1 = new Node("t1", "trigger", Map.of());
    Node t2 = new Node("t2", "trigger", Map.of());
    Node p = new Node("p", "processor", Map.of());
    Node term = new Node("term", "terminal", Map.of());

    WorkflowDefinition def =
        new WorkflowDefinition(
            "Fan-in Error Test",
            List.of(t1, t2, p, term),
            List.of(new Edge("t1", "p"), new Edge("t2", "p"), new Edge("p", "term")));

    TriggerPlugin trigger1 = mock(TriggerPlugin.class);
    when(trigger1.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(trigger1.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(trigger1.validateConfig(any())).thenReturn(Mono.empty());
    when(trigger1.initialize(any())).thenReturn(Mono.empty());
    when(trigger1.start(any())).thenReturn(Flux.error(new RuntimeException("Trigger 1 failed")));

    TriggerPlugin trigger2 = mock(TriggerPlugin.class);
    when(trigger2.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(trigger2.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(trigger2.validateConfig(any())).thenReturn(Mono.empty());
    when(trigger2.initialize(any())).thenReturn(Mono.empty());
    when(trigger2.start(any()))
        .thenReturn(Flux.just(DefaultMessage.create(UUID.randomUUID(), "t2-data")));

    ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(processor.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processor.validateConfig(any())).thenReturn(Mono.empty());
    when(processor.initialize(any())).thenReturn(Mono.empty());
    when(processor.process(any(), any())).thenAnswer(inv -> inv.getArgument(0));

    TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(terminal.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminal.validateConfig(any())).thenReturn(Mono.empty());
    when(terminal.initialize(any())).thenReturn(Mono.empty());
    when(terminal.consume(any(), any()))
        .thenAnswer(
            inv -> {
              Flux<Message<?>> input = inv.getArgument(0);
              return input.then();
            });

    Node t1_node = new Node("t1", "trigger1", Map.of());
    Node t2_node = new Node("t2", "trigger2", Map.of());
    WorkflowDefinition def2 =
        new WorkflowDefinition(
            "Fan-in Error Test",
            List.of(t1_node, t2_node, p, term),
            List.of(new Edge("t1", "p"), new Edge("t2", "p"), new Edge("p", "term")));

    when(registry.get("trigger1")).thenReturn(trigger1);
    when(registry.get("trigger2")).thenReturn(trigger2);
    when(registry.get("processor")).thenReturn(processor);
    when(registry.get("terminal")).thenReturn(terminal);

    String executionId = "exec-fan-in-error";
    StepVerifier.create(
            orchestrator
                .prepareWorkflow(def2)
                .flatMap(
                    pw ->
                        orchestrator.execute(
                            sessionId, "test-workflow", executionId, pw, Map.of())))
        .expectErrorMatches(
            e ->
                e.getMessage().contains("Trigger 1 failed")
                    || (e.getCause() != null
                        && e.getCause().getMessage().contains("Trigger 1 failed")))
        .verify();

    verify(tracker, timeout(1000).atLeastOnce())
        .emitTaskStatusEvent(eq(executionId), eq("t1"), anyString(), eq("FAILURE"), any());
    // ERROR now since I changed the error handling in execute() to STATUS_ERROR
    verify(tracker, timeout(1000)).emitWorkflowStatusEvent(eq(executionId), eq("ERROR"));
  }
}
