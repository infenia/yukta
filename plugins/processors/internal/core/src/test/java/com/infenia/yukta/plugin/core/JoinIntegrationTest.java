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
package com.infenia.yukta.plugin.core;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.config.AppConfigService;
import com.infenia.yukta.model.WorkflowDefinition;
import com.infenia.yukta.model.WorkflowDefinition.Edge;
import com.infenia.yukta.model.WorkflowDefinition.Node;
import com.infenia.yukta.plugin.DefaultMessage;
import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.PluginCategory;
import com.infenia.yukta.plugin.TerminalPlugin;
import com.infenia.yukta.plugin.TriggerPlugin;
import com.infenia.yukta.service.TaskTrackerService;
import com.infenia.yukta.service.WorkflowOrchestrator;
import com.infenia.yukta.service.WorkflowRegistry;
import com.infenia.yukta.service.WorkflowValidator;
import com.infenia.yukta.service.join.InMemoryJoinStore;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

class JoinIntegrationTest {

  private WorkflowRegistry registry;
  private TaskTrackerService tracker;
  private WorkflowValidator validator;
  private AppConfigService configService;
  private WorkflowOrchestrator orchestrator;
  private JoinProcessor joinProcessor;
  private InMemoryJoinStore joinStore;

  @BeforeEach
  void setUp() {
    registry = mock(WorkflowRegistry.class);
    tracker = mock(TaskTrackerService.class);
    configService = mock(AppConfigService.class);
    validator = new WorkflowValidator(registry);
    when(tracker.startWorkflow(any(), any(), any(), any())).thenReturn(Mono.empty());
    when(tracker.updateTaskStatus(any(), any(), any(), any())).thenReturn(Mono.empty());
    when(tracker.finishWorkflow(any(), any())).thenReturn(Mono.empty());
    when(tracker.appendLog(any(), any())).thenReturn(Mono.empty());
    when(configService.getExecutionTimeout(any())).thenReturn(Mono.just(3600L));
    orchestrator =
        new WorkflowOrchestrator(
            registry, tracker, validator, configService, null, Schedulers.immediate());

    joinStore = new InMemoryJoinStore();
    joinStore.init();
    joinProcessor = new JoinProcessor();
    ReflectionTestUtils.setField(joinProcessor, "joinStore", joinStore);
  }

  @Test
  void testWorkflowWithJoin() {
    String sessionId = "sess-join-int";
    Node t1 = new Node("t1", "trigger", Map.of("id", "t1"));
    Node t2 = new Node("t2", "trigger", Map.of("id", "t2"));
    Node join =
        new Node(
            "join",
            "JOIN",
            Map.of(
                "mode", "ALL",
                "expectedAncestors", List.of("t1", "t2"),
                "mergeStrategy", "ARRAY"));
    Node term = new Node("term", "terminal", Map.of());

    WorkflowDefinition def =
        new WorkflowDefinition(
            "Join Integration Test",
            List.of(t1, t2, join, term),
            List.of(new Edge("t1", "join"), new Edge("t2", "join"), new Edge("join", "term")));

    UUID commonTraceId = UUID.randomUUID();

    TriggerPlugin trigger = mock(TriggerPlugin.class);
    when(trigger.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(trigger.getCategory()).thenReturn(PluginCategory.TRIGGER);
    when(trigger.getType()).thenReturn("trigger");
    when(trigger.validateConfig(any())).thenReturn(Mono.empty());
    when(trigger.initialize(any())).thenReturn(Mono.empty());
    when(trigger.start(any()))
        .thenAnswer(
            inv -> {
              Map<String, Object> config = inv.getArgument(0);
              return Flux.just(
                  DefaultMessage.create(commonTraceId, "data-from-" + config.get("id")));
            });

    TerminalPlugin terminal = mock(TerminalPlugin.class);
    when(terminal.getDefaultTimeout()).thenReturn(java.time.Duration.ofSeconds(30));
    when(terminal.getCategory()).thenReturn(PluginCategory.TERMINAL);
    when(terminal.getType()).thenReturn("terminal");
    when(terminal.validateConfig(any())).thenReturn(Mono.empty());
    when(terminal.initialize(any())).thenReturn(Mono.empty());

    AtomicReference<List<?>> resultPayload = new AtomicReference<>();
    when(terminal.consume(any(), any()))
        .thenAnswer(
            inv -> {
              Flux<Message> input = inv.getArgument(0);
              return input.doOnNext(m -> resultPayload.set((List<?>) m.getPayload())).then();
            });

    when(registry.get("trigger")).thenReturn(trigger);
    when(registry.get("JOIN")).thenReturn(joinProcessor);
    when(registry.get("terminal")).thenReturn(terminal);

    String executionId = "exec-join-int";
    StepVerifier.create(
            orchestrator
                .prepareWorkflow(def)
                .flatMap(
                    p ->
                        orchestrator.execute(sessionId, "test-workflow", executionId, p, Map.of())))
        .verifyComplete();

    verify(terminal).consume(any(), any());
    List<?> results = resultPayload.get();
    assert (results != null);
    assert (results.size() == 2);
    assert (results.contains("data-from-t1"));
    assert (results.contains("data-from-t2"));
  }
}
