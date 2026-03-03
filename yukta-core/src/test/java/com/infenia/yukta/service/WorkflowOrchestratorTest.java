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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.config.AppConfigService;
import com.infenia.yukta.model.WorkflowDefinition;
import com.infenia.yukta.model.WorkflowDefinition.Edge;
import com.infenia.yukta.model.WorkflowDefinition.Node;
import com.infenia.yukta.plugin.core.PluginCategory;
import com.infenia.yukta.plugin.core.WorkflowPlugin;
import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.plugin.store.MessageStore;
import com.infenia.yukta.plugin.type.ProcessorPlugin;
import com.infenia.yukta.plugin.type.TerminalPlugin;
import com.infenia.yukta.plugin.type.TriggerPlugin;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class WorkflowOrchestratorTest {

  private WorkflowRegistry registry;
  private TaskTrackerService tracker;
  private WorkflowValidator validator;
  private AppConfigService configService;
  private com.infenia.yukta.plugin.gateway.ControlBusGateway controlBusGateway;
  private WorkflowOrchestrator orchestrator;

  @BeforeEach
  void setUp() {
    registry = mock(WorkflowRegistry.class);
    tracker = mock(TaskTrackerService.class);
    configService = mock(AppConfigService.class);
    controlBusGateway = mock(com.infenia.yukta.service.DefaultControlBusGateway.class);
    when(controlBusGateway.emit(any())).thenReturn(Mono.empty());
    com.infenia.yukta.service.ControlBusService controlBusService =
        mock(com.infenia.yukta.service.ControlBusService.class);
    when(((com.infenia.yukta.service.DefaultControlBusGateway) controlBusGateway)
            .getControlBusService())
        .thenReturn(controlBusService);
    validator = new WorkflowValidator(registry);
    when(tracker.startWorkflow(anyString(), anyString(), anyString(), any()))
        .thenReturn(Mono.empty());
    when(configService.getExecutionTimeout(anyString())).thenReturn(Mono.just(3600L));
    when(registry.get(anyString()))
        .thenAnswer(
            inv -> {
              WorkflowPlugin p = mock(WorkflowPlugin.class);
              when(p.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
              return p;
            });
    orchestrator =
        new WorkflowOrchestrator(
            registry,
            tracker,
            validator,
            configService,
            null,
            controlBusGateway,
            java.time.Duration.ofSeconds(10),
            Schedulers.parallel());
  }

  @Test
  void testValidateStructuralIntegritySuccess() {
    WorkflowDefinition.Node triggerNode = new WorkflowDefinition.Node("n1", "trigger", Map.of());
    WorkflowDefinition.Node terminalNode = new WorkflowDefinition.Node("n2", "terminal", Map.of());
    WorkflowDefinition def =
        new WorkflowDefinition(
            "desc",
            List.of(triggerNode, terminalNode),
            List.of(new WorkflowDefinition.Edge("n1", "n2")));

    TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(trigger.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(trigger.validateConfig(any())).thenReturn(Mono.empty());
    when(trigger.initialize(any())).thenReturn(Mono.empty());
    when(trigger.prepare(any())).thenReturn(Mono.empty());

    TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(terminal.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminal.validateConfig(any())).thenReturn(Mono.empty());
    when(terminal.initialize(any())).thenReturn(Mono.empty());
    when(terminal.prepare(any())).thenReturn(Mono.empty());

    when(registry.get("trigger")).thenReturn(trigger);
    when(registry.get("terminal")).thenReturn(terminal);

    StepVerifier.create(orchestrator.prepareWorkflow(def)).expectNextCount(1).verifyComplete();
  }

  @Test
  void testValidateStructuralIntegrityMissingTriggerAtEntry() {
    WorkflowDefinition.Node processorNode =
        new WorkflowDefinition.Node("n1", "processor", Map.of());
    WorkflowDefinition def = new WorkflowDefinition("desc", List.of(processorNode), List.of());

    ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(processor.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(registry.get("processor")).thenReturn(processor);

    StepVerifier.create(orchestrator.prepareWorkflow(def))
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("entry point but not a TRIGGER"))
        .verify();
  }

  @Test
  void testValidateStructuralIntegrityProcessorMissingEdges() {
    WorkflowDefinition.Node triggerNode = new WorkflowDefinition.Node("n1", "trigger", Map.of());
    WorkflowDefinition.Node processorNode =
        new WorkflowDefinition.Node("n2", "processor", Map.of());
    WorkflowDefinition def =
        new WorkflowDefinition(
            "desc",
            List.of(triggerNode, processorNode),
            List.of(new WorkflowDefinition.Edge("n1", "n2"))); // n2 has no outgoing edge

    TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(trigger.getCategory()).thenReturn(PluginCategory.TRIGGER);
    ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(processor.getCategory()).thenReturn(PluginCategory.PROCESSOR);

    when(registry.get("trigger")).thenReturn(trigger);
    when(registry.get("processor")).thenReturn(processor);
    when(trigger.validateConfig(any())).thenReturn(Mono.empty());
    when(trigger.initialize(any())).thenReturn(Mono.empty());
    when(trigger.prepare(any())).thenReturn(Mono.empty());
    when(processor.validateConfig(any())).thenReturn(Mono.empty());
    when(processor.initialize(any())).thenReturn(Mono.empty());
    when(processor.prepare(any())).thenReturn(Mono.empty());

    StepVerifier.create(orchestrator.prepareWorkflow(def))
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("must have both incoming and outgoing edges"))
        .verify();
  }

  @Test
  void testValidateStructuralIntegrityEndpointNotTerminal() {
    WorkflowDefinition.Node triggerNode = new WorkflowDefinition.Node("n1", "trigger", Map.of());
    WorkflowDefinition def = new WorkflowDefinition("desc", List.of(triggerNode), List.of());

    TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(trigger.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(trigger.validateConfig(any())).thenReturn(Mono.empty());
    when(trigger.initialize(any())).thenReturn(Mono.empty());
    when(trigger.prepare(any())).thenReturn(Mono.empty());

    when(registry.get("trigger")).thenReturn(trigger);

    StepVerifier.create(orchestrator.prepareWorkflow(def))
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("endpoint but not a TERMINAL"))
        .verify();
  }

  @Test
  void testValidateStructuralIntegrityCycleDetection() {
    WorkflowDefinition.Node t = new WorkflowDefinition.Node("t", "trigger", Map.of());
    WorkflowDefinition.Node p1 = new WorkflowDefinition.Node("p1", "processor", Map.of());
    WorkflowDefinition.Node p2 = new WorkflowDefinition.Node("p2", "processor", Map.of());
    WorkflowDefinition def =
        new WorkflowDefinition(
            "desc",
            List.of(t, p1, p2),
            List.of(
                new WorkflowDefinition.Edge("t", "p1"),
                new WorkflowDefinition.Edge("p1", "p2"),
                new WorkflowDefinition.Edge("p2", "p1") // Cycle
                ));

    TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(trigger.getCategory()).thenReturn(PluginCategory.TRIGGER);
    ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(processor.getCategory()).thenReturn(PluginCategory.PROCESSOR);

    when(registry.get("trigger")).thenReturn(trigger);
    when(registry.get("processor")).thenReturn(processor);
    when(trigger.validateConfig(any())).thenReturn(Mono.empty());
    when(trigger.initialize(any())).thenReturn(Mono.empty());
    when(trigger.prepare(any())).thenReturn(Mono.empty());
    when(processor.validateConfig(any())).thenReturn(Mono.empty());
    when(processor.initialize(any())).thenReturn(Mono.empty());
    when(processor.prepare(any())).thenReturn(Mono.empty());

    StepVerifier.create(orchestrator.prepareWorkflow(def))
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException && e.getMessage().contains("contains cycles"))
        .verify();
  }

  @Test
  void testValidateTriggerCannotHaveIncomingEdges() {
    WorkflowDefinition.Node t1 = new WorkflowDefinition.Node("t1", "trigger", Map.of());
    WorkflowDefinition.Node t2 = new WorkflowDefinition.Node("t2", "trigger", Map.of());
    WorkflowDefinition def =
        new WorkflowDefinition(
            "desc", List.of(t1, t2), List.of(new WorkflowDefinition.Edge("t1", "t2")));

    TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(trigger.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(registry.get("trigger")).thenReturn(trigger);

    StepVerifier.create(orchestrator.prepareWorkflow(def))
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("cannot have incoming edges"))
        .verify();
  }

  @Test
  void testValidateTerminalCannotHaveOutgoingEdges() {
    WorkflowDefinition.Node t = new WorkflowDefinition.Node("t", "trigger", Map.of());
    WorkflowDefinition.Node term1 = new WorkflowDefinition.Node("term1", "terminal", Map.of());
    WorkflowDefinition.Node term2 = new WorkflowDefinition.Node("term2", "terminal", Map.of());
    WorkflowDefinition def =
        new WorkflowDefinition(
            "desc",
            List.of(t, term1, term2),
            List.of(
                new WorkflowDefinition.Edge("t", "term1"),
                new WorkflowDefinition.Edge("term1", "term2")));

    TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(trigger.getCategory()).thenReturn(PluginCategory.TRIGGER);
    TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(terminal.getCategory()).thenReturn(PluginCategory.TERMINAL);

    when(registry.get("trigger")).thenReturn(trigger);
    when(registry.get("terminal")).thenReturn(terminal);

    StepVerifier.create(orchestrator.prepareWorkflow(def))
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("cannot have outgoing edges"))
        .verify();
  }

  @Test
  void testExecuteWorkflow() {
    String sessionId = "sess-1";
    UUID traceId = UUID.randomUUID();
    Message<String> msg = DefaultMessage.create(traceId, "payload");

    WorkflowDefinition.Node triggerNode = new WorkflowDefinition.Node("n1", "trigger", Map.of());
    WorkflowDefinition.Node terminalNode = new WorkflowDefinition.Node("n2", "terminal", Map.of());
    WorkflowDefinition def =
        new WorkflowDefinition(
            "desc",
            List.of(triggerNode, terminalNode),
            List.of(new WorkflowDefinition.Edge("n1", "n2")));

    TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(trigger.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(trigger.validateConfig(any())).thenReturn(Mono.empty());
    when(trigger.initialize(any())).thenReturn(Mono.empty());
    when(trigger.prepare(any())).thenReturn(Mono.empty());
    when(trigger.start(any())).thenReturn(Flux.just(msg));

    TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(terminal.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminal.validateConfig(any())).thenReturn(Mono.empty());
    when(terminal.initialize(any())).thenReturn(Mono.empty());
    when(terminal.prepare(any())).thenReturn(Mono.empty());
    when(terminal.consume(any(), any()))
        .thenAnswer(inv -> ((Flux<Message<?>>) inv.getArgument(0)).then());

    when(registry.get("trigger")).thenReturn(trigger);
    when(registry.get("terminal")).thenReturn(terminal);

    String executionId = "exec-1";
    StepVerifier.create(
            orchestrator
                .prepareWorkflow(def)
                .flatMap(
                    pw ->
                        orchestrator.execute(
                            sessionId, "test-workflow", executionId, pw, Map.of())))
        .verifyComplete();

    verify(trigger).start(any());
    verify(terminal).consume(any(), any());
    verify(tracker).startWorkflow(eq(executionId), eq(sessionId), anyString(), any());
    verify(tracker, atLeastOnce())
        .emitTaskStatusEvent(eq(executionId), anyString(), anyString(), anyString(), any());
    verify(tracker).emitWorkflowStatusEvent(eq(executionId), eq("SUCCESS"));
  }

  @Test
  void testMessageHistoryTracking() {
    String sessionId = "history-sess";
    UUID traceId = UUID.randomUUID();
    Message<String> msg = DefaultMessage.create(traceId, "payload");

    Node triggerNode = new Node("t1", "trigger", Map.of());
    Node procNode = new Node("p1", "processor", Map.of());
    Node terminalNode = new Node("term1", "terminal", Map.of());
    WorkflowDefinition def =
        new WorkflowDefinition(
            "History Test",
            List.of(triggerNode, procNode, terminalNode),
            List.of(new Edge("t1", "p1"), new Edge("p1", "term1")));

    TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(trigger.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(trigger.validateConfig(any())).thenReturn(Mono.empty());
    when(trigger.initialize(any())).thenReturn(Mono.empty());
    when(trigger.prepare(any())).thenReturn(Mono.empty());
    when(trigger.start(any())).thenReturn(Flux.just(msg));

    ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(processor.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processor.validateConfig(any())).thenReturn(Mono.empty());
    when(processor.initialize(any())).thenReturn(Mono.empty());
    when(processor.prepare(any())).thenReturn(Mono.empty());
    when(processor.process(any(), any())).thenAnswer(inv -> inv.getArgument(0));

    AtomicReference<Message<?>> capturedMessage = new AtomicReference<>();
    TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(terminal.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminal.validateConfig(any())).thenReturn(Mono.empty());
    when(terminal.initialize(any())).thenReturn(Mono.empty());
    when(terminal.prepare(any())).thenReturn(Mono.empty());
    when(terminal.consume(any(), any()))
        .thenAnswer(
            inv -> {
              Flux<Message<?>> input = inv.getArgument(0);
              return input.doOnNext(capturedMessage::set).then();
            });

    when(registry.get("trigger")).thenReturn(trigger);
    when(registry.get("processor")).thenReturn(processor);
    when(registry.get("terminal")).thenReturn(terminal);

    orchestrator
        .prepareWorkflow(def)
        .flatMap(pw -> orchestrator.execute(sessionId, "wf", "exec-h", pw, Map.of()))
        .as(StepVerifier::create)
        .verifyComplete();

    Message<?> finalMsg = capturedMessage.get();
    org.junit.jupiter.api.Assertions.assertNotNull(finalMsg);
    // History should contain t1 and p1
    List<String> history = finalMsg.getMessageHistory();
    org.junit.jupiter.api.Assertions.assertTrue(history.contains("t1"));
    org.junit.jupiter.api.Assertions.assertTrue(history.contains("p1"));
  }

  @Test
  void testWireTapSupport() {
    final String sessionId = "tap-sess";
    final MessageStore mockStore = mock(MessageStore.class);
    when(mockStore.store(any())).thenReturn(Mono.empty());

    final WorkflowOrchestrator tappedOrchestrator =
        new WorkflowOrchestrator(
            registry,
            tracker,
            validator,
            configService,
            mockStore,
            controlBusGateway,
            java.time.Duration.ofSeconds(10),
            Schedulers.immediate());

    final Node triggerNode = new Node("t1", "trigger", Map.of());
    final Node terminalNode = new Node("term1", "terminal", Map.of());
    final WorkflowDefinition def =
        new WorkflowDefinition(
            "Tap Test", List.of(triggerNode, terminalNode), List.of(new Edge("t1", "term1")));

    final TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(trigger.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(trigger.validateConfig(any())).thenReturn(Mono.empty());
    when(trigger.initialize(any())).thenReturn(Mono.empty());
    when(trigger.prepare(any())).thenReturn(Mono.empty());
    when(trigger.start(any())).thenReturn(Flux.just(DefaultMessage.create(UUID.randomUUID(), "d")));

    final TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminal.validateConfig(any())).thenReturn(Mono.empty());
    when(terminal.initialize(any())).thenReturn(Mono.empty());
    when(terminal.prepare(any())).thenReturn(Mono.empty());
    when(terminal.consume(any(), any())).thenReturn(Mono.empty());

    when(registry.get("trigger")).thenReturn(trigger);
    when(registry.get("terminal")).thenReturn(terminal);

    tappedOrchestrator
        .prepareWorkflow(def)
        .flatMap(pw -> tappedOrchestrator.execute(sessionId, "wf", "exec-tap", pw, Map.of()))
        .as(StepVerifier::create)
        .verifyComplete();

    // Verify wire tap stored at least the trigger emission
    verify(mockStore, atLeastOnce()).store(any());
  }

  @Test
  void testExecuteWorkflowWithBranching() {
    String sessionId = "sess-1";
    Message<String> msg = DefaultMessage.create(UUID.randomUUID(), "payload");

    WorkflowDefinition.Node triggerNode = new WorkflowDefinition.Node("t", "trigger", Map.of());
    WorkflowDefinition.Node term1 = new WorkflowDefinition.Node("term1", "terminal", Map.of());
    WorkflowDefinition.Node term2 = new WorkflowDefinition.Node("term2", "terminal", Map.of());

    WorkflowDefinition def =
        new WorkflowDefinition(
            "desc",
            List.of(triggerNode, term1, term2),
            List.of(
                new WorkflowDefinition.Edge("t", "term1"),
                new WorkflowDefinition.Edge("t", "term2")));

    TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(trigger.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(trigger.validateConfig(any())).thenReturn(Mono.empty());
    when(trigger.initialize(any())).thenReturn(Mono.empty());
    when(trigger.prepare(any())).thenReturn(Mono.empty());
    when(trigger.start(any())).thenReturn(Flux.just(msg));

    TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(terminal.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminal.validateConfig(any())).thenReturn(Mono.empty());
    when(terminal.initialize(any())).thenReturn(Mono.empty());
    when(terminal.prepare(any())).thenReturn(Mono.empty());
    when(terminal.consume(any(), any()))
        .thenAnswer(inv -> ((Flux<Message<?>>) inv.getArgument(0)).then());

    when(registry.get("trigger")).thenReturn(trigger);
    when(registry.get("terminal")).thenReturn(terminal);

    String executionId = "exec-2";
    StepVerifier.create(
            orchestrator
                .prepareWorkflow(def)
                .flatMap(
                    pw ->
                        orchestrator.execute(
                            sessionId, "test-workflow", executionId, pw, Map.of())))
        .verifyComplete();

    verify(terminal, atLeastOnce()).consume(any(), any());
  }

  @Test
  void testExecuteWorkflowWithProcessor() {
    String sessionId = "sess-1";
    Message<String> msg = DefaultMessage.create(UUID.randomUUID(), "payload");

    WorkflowDefinition.Node triggerNode = new WorkflowDefinition.Node("t", "trigger", Map.of());
    WorkflowDefinition.Node procNode = new WorkflowDefinition.Node("p", "processor", Map.of());
    WorkflowDefinition.Node termNode = new WorkflowDefinition.Node("term", "terminal", Map.of());

    WorkflowDefinition def =
        new WorkflowDefinition(
            "desc",
            List.of(triggerNode, procNode, termNode),
            List.of(
                new WorkflowDefinition.Edge("t", "p"), new WorkflowDefinition.Edge("p", "term")));

    TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(trigger.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(trigger.validateConfig(any())).thenReturn(Mono.empty());
    when(trigger.initialize(any())).thenReturn(Mono.empty());
    when(trigger.prepare(any())).thenReturn(Mono.empty());
    when(trigger.start(any())).thenReturn(Flux.just(msg));

    ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(processor.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processor.validateConfig(any())).thenReturn(Mono.empty());
    when(processor.initialize(any())).thenReturn(Mono.empty());
    when(processor.prepare(any())).thenReturn(Mono.empty());
    when(processor.process(any(), any())).thenReturn(Flux.just(msg));

    TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(terminal.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminal.validateConfig(any())).thenReturn(Mono.empty());
    when(terminal.initialize(any())).thenReturn(Mono.empty());
    when(terminal.prepare(any())).thenReturn(Mono.empty());
    when(terminal.consume(any(), any()))
        .thenAnswer(inv -> ((Flux<Message<?>>) inv.getArgument(0)).then());

    when(registry.get("trigger")).thenReturn(trigger);
    when(registry.get("processor")).thenReturn(processor);
    when(registry.get("terminal")).thenReturn(terminal);

    String executionId = "exec-3";
    StepVerifier.create(
            orchestrator
                .prepareWorkflow(def)
                .flatMap(
                    pw ->
                        orchestrator.execute(
                            sessionId, "test-workflow", executionId, pw, Map.of())))
        .verifyComplete();

    verify(trigger).start(any());
    verify(processor).process(any(), any());
    verify(terminal).consume(any(), any());
  }

  @Test
  void testPrepareWorkflowPluginNotFound() {
    WorkflowDefinition.Node node = new WorkflowDefinition.Node("n1", "unknown", Map.of());
    WorkflowDefinition def = new WorkflowDefinition("desc", List.of(node), List.of());

    when(registry.get("unknown")).thenReturn(null);

    StepVerifier.create(orchestrator.prepareWorkflow(def))
        .expectErrorMatches(
            e ->
                e instanceof IllegalArgumentException
                    && e.getMessage().contains("Plugin not found"))
        .verify();
  }

  @Test
  void testChainWithProcessorAndMultipleChildren() {
    String sessionId = "sess-1";
    Message<String> msg = DefaultMessage.create(UUID.randomUUID(), "payload");

    WorkflowDefinition.Node triggerNode = new WorkflowDefinition.Node("t", "trigger", Map.of());
    WorkflowDefinition.Node procNode = new WorkflowDefinition.Node("p", "processor", Map.of());
    WorkflowDefinition.Node term1 = new WorkflowDefinition.Node("term1", "terminal", Map.of());
    WorkflowDefinition.Node term2 = new WorkflowDefinition.Node("term2", "terminal", Map.of());

    WorkflowDefinition def =
        new WorkflowDefinition(
            "desc",
            List.of(triggerNode, procNode, term1, term2),
            List.of(
                new WorkflowDefinition.Edge("t", "p"),
                new WorkflowDefinition.Edge("p", "term1"),
                new WorkflowDefinition.Edge("p", "term2")));

    TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(trigger.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(trigger.validateConfig(any())).thenReturn(Mono.empty());
    when(trigger.initialize(any())).thenReturn(Mono.empty());
    when(trigger.prepare(any())).thenReturn(Mono.empty());
    when(trigger.start(any())).thenReturn(Flux.just(msg));

    ProcessorPlugin processor = mock(ProcessorPlugin.class);
    when(processor.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(processor.getCategory()).thenReturn(PluginCategory.PROCESSOR);
    when(processor.validateConfig(any())).thenReturn(Mono.empty());
    when(processor.initialize(any())).thenReturn(Mono.empty());
    when(processor.prepare(any())).thenReturn(Mono.empty());
    when(processor.process(any(), any())).thenReturn(Flux.just(msg));

    TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(terminal.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminal.validateConfig(any())).thenReturn(Mono.empty());
    when(terminal.initialize(any())).thenReturn(Mono.empty());
    when(terminal.prepare(any())).thenReturn(Mono.empty());
    when(terminal.consume(any(), any()))
        .thenAnswer(inv -> ((Flux<Message<?>>) inv.getArgument(0)).then());

    when(registry.get("trigger")).thenReturn(trigger);
    when(registry.get("processor")).thenReturn(processor);
    when(registry.get("terminal")).thenReturn(terminal);

    String executionId = "exec-4";
    StepVerifier.create(
            orchestrator
                .prepareWorkflow(def)
                .flatMap(
                    pw ->
                        orchestrator.execute(
                            sessionId, "test-workflow", executionId, pw, Map.of())))
        .verifyComplete();

    verify(processor).process(any(), any());
    verify(terminal, atLeastOnce()).consume(any(), any());
  }
}
