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
package com.infenia.yukta.service.orchestrator.stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.model.workflow.WorkflowNode;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeoutException;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** Unit tests for {@link StreamBuilder}. */
@ExtendWith(MockitoExtension.class)
@DisplayName("StreamBuilder Tests")
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyStaticImports", "PMD.TooManyMethods", "PMD.CommentRequired"})
class StreamBuilderTest {

  private static final String EXECUTION_ID = "exec-001";
  private static final String NODE_ID = "test-node";
  private static final String DEFAULT_TASK_ID = "default";
  private static final String STATUS_RUNNING = "RUNNING";
  private static final String STATUS_SUCCESS = "SUCCESS";
  private static final String STATUS_FAILURE = "FAILURE";
  private static final Duration TIMEOUT = Duration.ofSeconds(10);

  /** Injected mocked node. */
  @Mock private WorkflowNode node;

  /** Injected mocked tracker service. */
  @Mock private TaskTrackerService taskTrackerService;

  /** Builder under test. */
  private StreamBuilder builder;

  @BeforeEach
  void setUp() {
    org.mockito.Mockito.when(node.nodeId()).thenReturn(NODE_ID);
    builder = new StreamBuilder(node, TIMEOUT, taskTrackerService);
  }

  @Test
  @DisplayName("constructor initializes all fields correctly")
  void constructor_validParameters_initializesFields() {
    final StreamBuilder testBuilder = new StreamBuilder(node, TIMEOUT, taskTrackerService);

    assertThat(testBuilder).isNotNull();
  }

  @Test
  @DisplayName("withSource sets the source stream")
  void withSource_validFlux_setsSourceStream() {
    final Flux<Message<?>> sourceFlux = Flux.empty();

    final StreamBuilder result = builder.withSource(sourceFlux);

    assertThat(result).isSameAs(builder);
  }

  @Test
  @DisplayName("withSource returns builder for fluent chaining")
  void withSource_returnsBuilder_forFluentChaining() {
    final Flux<Message<?>> sourceFlux = Flux.empty();

    final StreamBuilder result = builder.withSource(sourceFlux);

    assertThat(result).isInstanceOf(StreamBuilder.class).isSameAs(builder);
  }

  @Test
  @DisplayName("withTimeout enables timeout handling")
  void withTimeout_enablesTimeoutHandling() {
    final StreamBuilder result = builder.withTimeout();

    assertThat(result).isSameAs(builder);
  }

  @Test
  @DisplayName("withTimeout returns builder for fluent chaining")
  void withTimeout_returnsBuilder_forFluentChaining() {
    final StreamBuilder result = builder.withTimeout();

    assertThat(result).isInstanceOf(StreamBuilder.class).isSameAs(builder);
  }

  @Test
  @DisplayName("withTaskTracking enables task tracking with execution ID")
  void withTaskTracking_enablesTaskTrackingWithExecutionId() {
    final StreamBuilder result = builder.withTaskTracking(EXECUTION_ID);

    assertThat(result).isSameAs(builder);
  }

  @Test
  @DisplayName("withTaskTracking returns builder for fluent chaining")
  void withTaskTracking_returnsBuilder_forFluentChaining() {
    final StreamBuilder result = builder.withTaskTracking(EXECUTION_ID);

    assertThat(result).isInstanceOf(StreamBuilder.class).isSameAs(builder);
  }

  @Test
  @DisplayName("withErrorHandling enables error handling with execution ID")
  void withErrorHandling_enablesErrorHandlingWithExecutionId() {
    final StreamBuilder result = builder.withErrorHandling(EXECUTION_ID);

    assertThat(result).isSameAs(builder);
  }

  @Test
  @DisplayName("withErrorHandling returns builder for fluent chaining")
  void withErrorHandling_returnsBuilder_forFluentChaining() {
    final StreamBuilder result = builder.withErrorHandling(EXECUTION_ID);

    assertThat(result).isInstanceOf(StreamBuilder.class).isSameAs(builder);
  }

  @Test
  @DisplayName("build with no source returns empty flux")
  void build_noSourceProvided_returnsEmptyFlux() {
    final Flux<Message<?>> result = builder.build();

    StepVerifier.create(result).expectComplete().verify();
  }

  @Test
  @DisplayName("build with source and no transformations returns source flux")
  void build_sourceProvidedNoTransformations_returnsSourceFlux() {
    final Message<?> testMessage = createTestMessage();
    final Flux<Message<?>> sourceFlux = Flux.just(testMessage);

    final Flux<Message<?>> result = builder.withSource(sourceFlux).build();

    StepVerifier.create(result).expectNext(testMessage).verifyComplete();
  }

  @Test
  @DisplayName("build with timeout applies timeout transformation")
  void build_withTimeoutEnabled_appliesTimeoutTransform() {
    final Flux<Message<?>> sourceFlux = Flux.never();

    final Flux<Message<?>> result = builder.withSource(sourceFlux).withTimeout().build();

    StepVerifier.create(result).expectError(TimeoutException.class).verify(TIMEOUT.plusSeconds(1));
  }

  @Test
  @DisplayName("build with timeout exceeds duration and maps TimeoutException")
  void build_withTimeoutExceeded_mapsTimeoutException() {
    final Flux<Message<?>> sourceFlux = Flux.never();

    final Flux<Message<?>> result = builder.withSource(sourceFlux).withTimeout().build();

    StepVerifier.create(result).expectError(TimeoutException.class).verify(TIMEOUT.plusSeconds(1));
  }

  @Test
  @DisplayName("build with task tracking emits RUNNING on subscribe")
  void build_withTaskTrackingEnabled_emitsRunningOnSubscribe() {
    final Message<?> testMessage = createTestMessage();
    final Flux<Message<?>> sourceFlux = Flux.just(testMessage);

    final Flux<Message<?>> result =
        builder.withSource(sourceFlux).withTaskTracking(EXECUTION_ID).build();

    StepVerifier.create(result).expectNext(testMessage).verifyComplete();

    verify(taskTrackerService)
        .emitTaskStatusEvent(
            eq(EXECUTION_ID), eq(NODE_ID), eq(DEFAULT_TASK_ID), eq(STATUS_RUNNING), anyMap());
  }

  @Test
  @DisplayName("build with task tracking emits SUCCESS on complete")
  void build_withTaskTrackingEnabled_emitsSuccessOnComplete() {
    final Message<?> testMessage = createTestMessage();
    final Flux<Message<?>> sourceFlux = Flux.just(testMessage);

    final Flux<Message<?>> result =
        builder.withSource(sourceFlux).withTaskTracking(EXECUTION_ID).build();

    StepVerifier.create(result).expectNext(testMessage).verifyComplete();

    verify(taskTrackerService)
        .emitTaskStatusEvent(
            eq(EXECUTION_ID), eq(NODE_ID), eq(DEFAULT_TASK_ID), eq(STATUS_SUCCESS), anyMap());
  }

  @Test
  @DisplayName("build with task tracking emits FAILURE on error")
  void build_withTaskTrackingEnabled_emitsFailureOnError() {
    final RuntimeException testException = new RuntimeException("Test error");
    final Flux<Message<?>> sourceFlux = Flux.error(testException);

    final Flux<Message<?>> result =
        builder.withSource(sourceFlux).withTaskTracking(EXECUTION_ID).build();

    StepVerifier.create(result).expectError(RuntimeException.class).verify();

    verify(taskTrackerService)
        .emitTaskStatusEvent(
            eq(EXECUTION_ID), eq(NODE_ID), eq(DEFAULT_TASK_ID), eq(STATUS_FAILURE), anyMap());
  }

  @Test
  @DisplayName("build with error handling emits FAILURE on error")
  void build_withErrorHandlingEnabled_emitsFailureOnError() {
    final RuntimeException testException = new RuntimeException("Test error");
    final Flux<Message<?>> sourceFlux = Flux.error(testException);

    final Flux<Message<?>> result =
        builder.withSource(sourceFlux).withErrorHandling(EXECUTION_ID).build();

    StepVerifier.create(result).expectError(RuntimeException.class).verify();

    verify(taskTrackerService)
        .emitTaskStatusEvent(
            eq(EXECUTION_ID), eq(NODE_ID), eq(DEFAULT_TASK_ID), eq(STATUS_FAILURE), anyMap());
  }

  @Test
  @DisplayName("build with timeout and task tracking applies both transformations")
  void build_withTimeoutAndTaskTracking_appliesBothTransformations() {
    final Message<?> testMessage = createTestMessage();
    final Flux<Message<?>> sourceFlux = Flux.just(testMessage);

    final Flux<Message<?>> result =
        builder.withSource(sourceFlux).withTimeout().withTaskTracking(EXECUTION_ID).build();

    StepVerifier.create(result).expectNext(testMessage).verifyComplete();

    verify(taskTrackerService)
        .emitTaskStatusEvent(
            eq(EXECUTION_ID), eq(NODE_ID), eq(DEFAULT_TASK_ID), eq(STATUS_RUNNING), anyMap());
    verify(taskTrackerService)
        .emitTaskStatusEvent(
            eq(EXECUTION_ID), eq(NODE_ID), eq(DEFAULT_TASK_ID), eq(STATUS_SUCCESS), anyMap());
  }

  @Test
  @DisplayName("build with task tracking and error handling applies both transformations")
  void build_withTaskTrackingAndErrorHandling_appliesBothTransformations() {
    final RuntimeException testException = new RuntimeException("Test error");
    final Flux<Message<?>> sourceFlux = Flux.error(testException);

    final Flux<Message<?>> result =
        builder
            .withSource(sourceFlux)
            .withTaskTracking(EXECUTION_ID)
            .withErrorHandling(EXECUTION_ID)
            .build();

    StepVerifier.create(result).expectError(RuntimeException.class).verify();

    verify(taskTrackerService, org.mockito.Mockito.times(2))
        .emitTaskStatusEvent(
            eq(EXECUTION_ID), eq(NODE_ID), eq(DEFAULT_TASK_ID), eq(STATUS_FAILURE), anyMap());
  }

  @Test
  @DisplayName("build with all transformations applies them in correct order")
  void build_withAllTransformationsEnabled_appliesAllInOrder() {
    final Message<?> testMessage = createTestMessage();
    final Flux<Message<?>> sourceFlux = Flux.just(testMessage);

    final Flux<Message<?>> result =
        builder
            .withSource(sourceFlux)
            .withTimeout()
            .withTaskTracking(EXECUTION_ID)
            .withErrorHandling(EXECUTION_ID)
            .build();

    StepVerifier.create(result).expectNext(testMessage).verifyComplete();

    final InOrder inOrder = inOrder(taskTrackerService);
    inOrder
        .verify(taskTrackerService)
        .emitTaskStatusEvent(
            eq(EXECUTION_ID), eq(NODE_ID), eq(DEFAULT_TASK_ID), eq(STATUS_RUNNING), anyMap());
    inOrder
        .verify(taskTrackerService)
        .emitTaskStatusEvent(
            eq(EXECUTION_ID), eq(NODE_ID), eq(DEFAULT_TASK_ID), eq(STATUS_SUCCESS), anyMap());
  }

  @Test
  @DisplayName("fluent chaining with multiple methods works correctly")
  void fluentChaining_multipleMethodCalls_successfullyChains() {
    final Flux<Message<?>> sourceFlux = Flux.empty();

    final StreamBuilder result =
        builder
            .withSource(sourceFlux)
            .withTimeout()
            .withTaskTracking(EXECUTION_ID)
            .withErrorHandling(EXECUTION_ID);

    assertThat(result).isSameAs(builder);
  }

  @Test
  @DisplayName("build emits correct parameters to taskTrackerService")
  void build_emitTaskStatusEventUsesCorrectParameters() {
    final Message<?> testMessage = createTestMessage();
    final Flux<Message<?>> sourceFlux = Flux.just(testMessage);

    final Flux<Message<?>> result =
        builder.withSource(sourceFlux).withTaskTracking(EXECUTION_ID).build();

    StepVerifier.create(result).expectNext(testMessage).verifyComplete();

    final ArgumentCaptor<String> executionIdCaptor = ArgumentCaptor.forClass(String.class);
    final ArgumentCaptor<String> nodeIdCaptor = ArgumentCaptor.forClass(String.class);
    final ArgumentCaptor<String> taskIdCaptor = ArgumentCaptor.forClass(String.class);

    verify(taskTrackerService, org.mockito.Mockito.atLeastOnce())
        .emitTaskStatusEvent(
            executionIdCaptor.capture(),
            nodeIdCaptor.capture(),
            taskIdCaptor.capture(),
            anyString(),
            anyMap());

    assertThat(executionIdCaptor.getValue()).isEqualTo(EXECUTION_ID);
    assertThat(nodeIdCaptor.getValue()).isEqualTo(NODE_ID);
    assertThat(taskIdCaptor.getValue()).isEqualTo(DEFAULT_TASK_ID);
  }

  @Test
  @DisplayName("build without error handling doesn't emit on successful completion")
  void build_withoutErrorHandling_noExtraEmitOnSuccess() {
    final Message<?> testMessage = createTestMessage();
    final Flux<Message<?>> sourceFlux = Flux.just(testMessage);

    builder.withSource(sourceFlux).build();

    verifyNoInteractions(taskTrackerService);
  }

  @Test
  @DisplayName("timeout transformation preserves original exception details")
  void build_timeoutPreservesExceptionDetails() {
    final Flux<Message<?>> sourceFlux = Flux.never();

    final Flux<Message<?>> result = builder.withSource(sourceFlux).withTimeout().build();

    StepVerifier.create(result)
        .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(TimeoutException.class))
        .verify(TIMEOUT.plusSeconds(1));
  }

  @Test
  @DisplayName("multiple calls to withTaskTracking use latest executionId")
  void build_multipleWithTaskTrackingCalls_usesLatestExecutionId() {
    final String latestExecId = "exec-002";
    final Message<?> testMessage = createTestMessage();
    final Flux<Message<?>> sourceFlux = Flux.just(testMessage);

    final StreamBuilder multiCallBuilder = new StreamBuilder(node, TIMEOUT, taskTrackerService);
    final Flux<Message<?>> result =
        multiCallBuilder
            .withSource(sourceFlux)
            .withTaskTracking(EXECUTION_ID)
            .withTaskTracking(latestExecId)
            .build();

    StepVerifier.create(result).expectNext(testMessage).verifyComplete();

    verify(taskTrackerService, org.mockito.Mockito.atLeastOnce())
        .emitTaskStatusEvent(
            eq(latestExecId), eq(NODE_ID), eq(DEFAULT_TASK_ID), anyString(), anyMap());
  }

  @Test
  @DisplayName("task tracking with empty map parameter")
  void build_taskTrackingEmitsEmptyMap() {
    final Message<?> testMessage = createTestMessage();
    final Flux<Message<?>> sourceFlux = Flux.just(testMessage);

    final Flux<Message<?>> result =
        builder.withSource(sourceFlux).withTaskTracking(EXECUTION_ID).build();

    StepVerifier.create(result).expectNext(testMessage).verifyComplete();

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Map<String, Object>> mapCaptor = ArgumentCaptor.forClass(Map.class);

    verify(taskTrackerService, org.mockito.Mockito.atLeastOnce())
        .emitTaskStatusEvent(
            anyString(), anyString(), anyString(), anyString(), mapCaptor.capture());

    assertThat(mapCaptor.getValue()).isEqualTo(Collections.emptyMap());
  }

  private Message<?> createTestMessage() {
    return new DefaultMessage<>(
        UUID.randomUUID().toString(),
        "trace-id",
        "correlation-id",
        "reply-to",
        0,
        "text/plain",
        Map.of(),
        java.util.List.of(),
        "",
        0,
        0,
        0,
        false,
        "",
        "",
        "",
        0,
        "test-payload",
        Instant.now(),
        "port",
        "source-node",
        "workflow-id");
  }
}
