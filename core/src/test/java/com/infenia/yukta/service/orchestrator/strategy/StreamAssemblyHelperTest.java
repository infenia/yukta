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
package com.infenia.yukta.service.orchestrator.strategy;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.valve.ReactiveControlValve;
import com.infenia.yukta.service.orchestrator.assembly.AssemblyContext;
import com.infenia.yukta.service.orchestrator.assembly.ExecutionContextBuilder;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.time.Duration;
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
@NoArgsConstructor
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.CompareObjectsWithEquals"
})
class StreamAssemblyHelperTest {

  @Mock private TaskTrackerService tracker;

  private static final String NODE_ID = "node-1";
  private static final String EXECUTION_ID = "exec-001";
  private static final String SESSION_ID = "session-001";
  private static final String WORKFLOW_ID = "wf-001";

  @BeforeEach
  void setUp() {
    doNothing()
        .when(tracker)
        .emitTaskStatusEvent(
            anyString(), anyString(), anyString(), anyString(), org.mockito.ArgumentMatchers.any());
  }

  @Test
  void buildStreamWithContext_passesThroughMessages() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "hello");
    final WorkflowNode node = new WorkflowNode(NODE_ID, "trigger", Map.of());
    final AssemblyContext context = buildContext();

    final Flux<Message<?>> result =
        StreamAssemblyHelper.buildStreamWithContext(
            node, Flux.just(msg), Duration.ofSeconds(5), tracker, context);

    StepVerifier.create(result).expectNextCount(1).verifyComplete();
  }

  @Test
  void buildStreamWithContext_emptyStream_completesEmpty() {
    final WorkflowNode node = new WorkflowNode(NODE_ID, "trigger", Map.of());
    final AssemblyContext context = buildContext();

    final Flux<Message<?>> result =
        StreamAssemblyHelper.buildStreamWithContext(
            node, Flux.empty(), Duration.ofSeconds(5), tracker, context);

    StepVerifier.create(result).verifyComplete();
  }

  @Test
  void buildStreamWithContext_appliesExecutionContextToStream() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");
    final WorkflowNode node = new WorkflowNode(NODE_ID, "trigger", Map.of());
    final AssemblyContext context = buildContext();

    final Flux<Message<?>> result =
        StreamAssemblyHelper.buildStreamWithContext(
            node, Flux.just(msg), Duration.ofSeconds(5), tracker, context);

    StepVerifier.create(result)
        .expectAccessibleContext()
        .hasKey(ExecutionContextBuilder.CTX_SESSION_ID)
        .then()
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void buildStreamWithContext_appliesWorkflowIdContext() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");
    final WorkflowNode node = new WorkflowNode(NODE_ID, "trigger", Map.of());
    final AssemblyContext context = buildContext();

    final Flux<Message<?>> result =
        StreamAssemblyHelper.buildStreamWithContext(
            node, Flux.just(msg), Duration.ofSeconds(5), tracker, context);

    StepVerifier.create(result)
        .expectAccessibleContext()
        .hasKey(ExecutionContextBuilder.CTX_WORKFLOW_ID)
        .then()
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void buildStreamWithContext_appliesExecutionIdContext() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");
    final WorkflowNode node = new WorkflowNode(NODE_ID, "trigger", Map.of());
    final AssemblyContext context = buildContext();

    final Flux<Message<?>> result =
        StreamAssemblyHelper.buildStreamWithContext(
            node, Flux.just(msg), Duration.ofSeconds(5), tracker, context);

    StepVerifier.create(result)
        .expectAccessibleContext()
        .hasKey(ExecutionContextBuilder.CTX_EXECUTION_ID)
        .then()
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void buildStreamWithContext_appliesNodeIdContext() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");
    final WorkflowNode node = new WorkflowNode(NODE_ID, "trigger", Map.of());
    final AssemblyContext context = buildContext();

    final Flux<Message<?>> result =
        StreamAssemblyHelper.buildStreamWithContext(
            node, Flux.just(msg), Duration.ofSeconds(5), tracker, context);

    StepVerifier.create(result)
        .expectAccessibleContext()
        .hasKey(ExecutionContextBuilder.CTX_NODE_ID)
        .then()
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void buildStreamWithContext_multipleMessages_allPassThrough() {
    final Message<?> msg1 = DefaultMessage.create(UUID.randomUUID(), "a");
    final Message<?> msg2 = DefaultMessage.create(UUID.randomUUID(), "b");
    final Message<?> msg3 = DefaultMessage.create(UUID.randomUUID(), "c");
    final WorkflowNode node = new WorkflowNode(NODE_ID, "trigger", Map.of());
    final AssemblyContext context = buildContext();

    final Flux<Message<?>> result =
        StreamAssemblyHelper.buildStreamWithContext(
            node, Flux.just(msg1, msg2, msg3), Duration.ofSeconds(5), tracker, context);

    StepVerifier.create(result).expectNextCount(3).verifyComplete();
  }

  @Test
  void buildStreamWithContext_applyPostProcessingControlsCalled() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");
    final WorkflowNode node = new WorkflowNode(NODE_ID, "trigger", Map.of());

    // Use a context with a node immediate stop sink to verify post-processing is applied
    final Sinks.One<Void> immediateStopSink = Sinks.one();
    final AssemblyContext context = buildContextWithImmediateStop(immediateStopSink);

    // Immediately signal stop — stream should complete before emitting
    immediateStopSink.tryEmitEmpty();

    final Flux<Message<?>> result =
        StreamAssemblyHelper.buildStreamWithContext(
            node, Flux.just(msg), Duration.ofSeconds(5), tracker, context);

    // With immediate stop triggered, items are taken until completion signal
    StepVerifier.create(result).verifyComplete();
  }

  @Test
  void buildStreamWithContext_upstreamError_propagatesErrorAndLogsIt() {
    final RuntimeException cause = new RuntimeException("upstream failure");
    final WorkflowNode node = new WorkflowNode(NODE_ID, "trigger", Map.of());
    final AssemblyContext context = buildContext();

    final Flux<Message<?>> result =
        StreamAssemblyHelper.buildStreamWithContext(
            node, Flux.error(cause), Duration.ofSeconds(5), tracker, context);

    StepVerifier.create(result).expectErrorMatches(e -> e == cause).verify();
  }

  @Test
  void buildStreamWithContext_payloadContextApplied() {
    final Message<?> msg = DefaultMessage.create(UUID.randomUUID(), "data");
    final WorkflowNode node = new WorkflowNode(NODE_ID, "trigger", Map.of());
    final Map<String, Object> payload = Map.of("key", "value");
    final AssemblyContext context = buildContextWithPayload(payload);

    final Flux<Message<?>> result =
        StreamAssemblyHelper.buildStreamWithContext(
            node, Flux.just(msg), Duration.ofSeconds(5), tracker, context);

    StepVerifier.create(result)
        .expectAccessibleContext()
        .hasKey(ExecutionContextBuilder.CTX_PAYLOAD)
        .then()
        .expectNextCount(1)
        .verifyComplete();
  }

  // ── helpers ───────────────────────────────────────────────────────────────

  @SuppressWarnings("unchecked")
  private AssemblyContext buildContext() {
    return buildContextWithPayload(Map.of());
  }

  @SuppressWarnings("unchecked")
  private AssemblyContext buildContextWithPayload(final Map<String, Object> payload) {
    final ExecutionControl control =
        new ExecutionControl(
            SESSION_ID,
            WORKFLOW_ID,
            EXECUTION_ID,
            null,
            payload,
            Sinks.one(),
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());

    return new AssemblyContext(
        EXECUTION_ID,
        SESSION_ID,
        WORKFLOW_ID,
        payload,
        control,
        new Flux[1],
        new ArrayList<>(),
        new ArrayList<>(),
        new ArrayList<>());
  }

  @SuppressWarnings("unchecked")
  private AssemblyContext buildContextWithImmediateStop(final Sinks.One<Void> immediateStopSink) {
    final ExecutionControl control =
        new ExecutionControl(
            SESSION_ID,
            WORKFLOW_ID,
            EXECUTION_ID,
            null,
            Map.of(),
            Sinks.one(),
            Sinks.one(),
            new ReactiveControlValve(),
            Map.of(NODE_ID, immediateStopSink),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of());

    return new AssemblyContext(
        EXECUTION_ID,
        SESSION_ID,
        WORKFLOW_ID,
        Map.of(),
        control,
        new Flux[1],
        new ArrayList<>(),
        new ArrayList<>(),
        new ArrayList<>());
  }
}
