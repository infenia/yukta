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
package com.infenia.yukta.service.orchestrator;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.plugin.core.WorkflowPlugin;
import com.infenia.yukta.plugin.gateway.ControlBusGateway;
import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import com.infenia.yukta.service.TaskTrackerService;
import java.time.Duration;
import java.util.Collections;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.MockitoAnnotations;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class StreamBuilderTest {

  @Mock private Node mockNode;
  @Mock private WorkflowPlugin mockPlugin;
  @Mock private ControlBusGateway mockControlBusGateway;
  @Mock private TaskTrackerService mockTracker;

  @BeforeEach
  void setUp() {
    MockitoAnnotations.openMocks(this);
    when(mockNode.nodeId()).thenReturn("node-001");
    when(mockControlBusGateway.emit(any())).thenReturn(Mono.empty());
  }

  @Test
  void testStreamBuilderWithTimeout() {
    Flux<Message<?>> sourceStream = Flux.just(DefaultMessage.create(null, "test-payload"));

    StreamBuilder builder =
        new StreamBuilder(mockNode, Duration.ofSeconds(5), mockTracker, mockControlBusGateway);

    Flux<Message<?>> built = builder.withSource(sourceStream).withTimeout().build();

    StepVerifier.create(built)
        .expectNextMatches(msg -> msg.getPayload().equals("test-payload"))
        .verifyComplete();
  }

  @Test
  void testStreamBuilderWithTaskTracking() {
    Flux<Message<?>> sourceStream = Flux.just(DefaultMessage.create(null, "test"));

    StreamBuilder builder =
        new StreamBuilder(mockNode, Duration.ofSeconds(5), mockTracker, mockControlBusGateway);

    Flux<Message<?>> built = builder.withSource(sourceStream).withTaskTracking("exec-001").build();

    StepVerifier.create(built).expectNextCount(1).verifyComplete();

    verify(mockTracker, times(1))
        .emitTaskStatusEvent("exec-001", "node-001", "default", "RUNNING", Collections.emptyMap());
    verify(mockTracker, times(1))
        .emitTaskStatusEvent("exec-001", "node-001", "default", "SUCCESS", Collections.emptyMap());
  }

  @Test
  void testStreamBuilderChaining() {
    Flux<Message<?>> sourceStream = Flux.just(DefaultMessage.create(null, "test"));

    StreamBuilder builder =
        new StreamBuilder(mockNode, Duration.ofSeconds(5), mockTracker, mockControlBusGateway);

    Flux<Message<?>> built =
        builder.withSource(sourceStream).withTimeout().withTaskTracking("exec-001").build();

    assert built != null;
    StepVerifier.create(built).expectNextCount(1).verifyComplete();
  }

  @Test
  void testStreamBuilderErrorHandling() {
    Flux<Message<?>> sourceStream = Flux.error(new RuntimeException("Test error"));

    StreamBuilder builder =
        new StreamBuilder(mockNode, Duration.ofSeconds(5), mockTracker, mockControlBusGateway);

    Flux<Message<?>> built = builder.withSource(sourceStream).withErrorHandling("exec-001").build();

    StepVerifier.create(built).expectError(RuntimeException.class).verify();

    verify(mockTracker)
        .emitTaskStatusEvent("exec-001", "node-001", "default", "FAILURE", Collections.emptyMap());
  }

  @Test
  void testStreamBuilderWithoutSource() {
    StreamBuilder builder =
        new StreamBuilder(mockNode, Duration.ofSeconds(5), mockTracker, mockControlBusGateway);

    Flux<Message<?>> built = builder.build();

    StepVerifier.create(built).verifyComplete();
  }

  @Test
  void testStreamBuilderWithTimeoutAndTaskTracking() {
    Flux<Message<?>> sourceStream = Flux.just(DefaultMessage.create(null, "test"));

    StreamBuilder builder =
        new StreamBuilder(mockNode, Duration.ofSeconds(5), mockTracker, mockControlBusGateway);

    Flux<Message<?>> built =
        builder.withSource(sourceStream).withTimeout().withTaskTracking("exec-001").build();

    StepVerifier.create(built).expectNextCount(1).verifyComplete();

    verify(mockTracker, times(1))
        .emitTaskStatusEvent("exec-001", "node-001", "default", "RUNNING", Collections.emptyMap());
    verify(mockTracker, times(1))
        .emitTaskStatusEvent("exec-001", "node-001", "default", "SUCCESS", Collections.emptyMap());
  }

  @Test
  void testStreamBuilderErrorHandlingWithControlBus() {
    Flux<Message<?>> sourceStream = Flux.error(new IllegalArgumentException("Invalid arg"));

    StreamBuilder builder =
        new StreamBuilder(mockNode, Duration.ofSeconds(5), mockTracker, mockControlBusGateway);

    Flux<Message<?>> built = builder.withSource(sourceStream).withErrorHandling("exec-002").build();

    StepVerifier.create(built).expectError(IllegalArgumentException.class).verify();

    verify(mockTracker)
        .emitTaskStatusEvent("exec-002", "node-001", "default", "FAILURE", Collections.emptyMap());
    verify(mockControlBusGateway, times(1)).emit(any());
  }

  @Test
  void testStreamBuilderAllTransformations() {
    Flux<Message<?>> sourceStream = Flux.just(DefaultMessage.create(null, "test"));

    StreamBuilder builder =
        new StreamBuilder(mockNode, Duration.ofSeconds(5), mockTracker, mockControlBusGateway);

    Flux<Message<?>> built =
        builder
            .withSource(sourceStream)
            .withTimeout()
            .withTaskTracking("exec-003")
            .withErrorHandling("exec-003")
            .build();

    StepVerifier.create(built).expectNextCount(1).verifyComplete();

    verify(mockTracker, times(1))
        .emitTaskStatusEvent("exec-003", "node-001", "default", "RUNNING", Collections.emptyMap());
    verify(mockTracker, times(1))
        .emitTaskStatusEvent("exec-003", "node-001", "default", "SUCCESS", Collections.emptyMap());
  }

  @Test
  void testStreamBuilderErrorDuringTaskTracking() {
    Flux<Message<?>> sourceStream = Flux.error(new NullPointerException("Null error"));

    StreamBuilder builder =
        new StreamBuilder(mockNode, Duration.ofSeconds(5), mockTracker, mockControlBusGateway);

    Flux<Message<?>> built = builder.withSource(sourceStream).withTaskTracking("exec-004").build();

    StepVerifier.create(built).expectError(NullPointerException.class).verify();

    verify(mockTracker, times(1))
        .emitTaskStatusEvent("exec-004", "node-001", "default", "RUNNING", Collections.emptyMap());
    verify(mockTracker, times(1))
        .emitTaskStatusEvent("exec-004", "node-001", "default", "FAILURE", Collections.emptyMap());
  }

  @Test
  void testStreamBuilderTimeoutOnly() {
    Flux<Message<?>> sourceStream = Flux.just(DefaultMessage.create(null, "msg1"));

    StreamBuilder builder =
        new StreamBuilder(mockNode, Duration.ofSeconds(5), mockTracker, mockControlBusGateway);

    Flux<Message<?>> built = builder.withSource(sourceStream).withTimeout().build();

    StepVerifier.create(built).expectNextCount(1).verifyComplete();
  }

  @Test
  void testStreamBuilderErrorHandlingOnly() {
    Flux<Message<?>> sourceStream = Flux.error(new Exception("Test exception"));

    StreamBuilder builder =
        new StreamBuilder(mockNode, Duration.ofSeconds(5), mockTracker, mockControlBusGateway);

    Flux<Message<?>> built = builder.withSource(sourceStream).withErrorHandling("exec-005").build();

    StepVerifier.create(built).expectError(Exception.class).verify();

    verify(mockTracker)
        .emitTaskStatusEvent("exec-005", "node-001", "default", "FAILURE", Collections.emptyMap());
    verify(mockControlBusGateway).emit(any());
  }

  @Test
  void testStreamBuilderMultipleElements() {
    Flux<Message<?>> sourceStream =
        Flux.just(
            DefaultMessage.create(null, "msg1"),
            DefaultMessage.create(null, "msg2"),
            DefaultMessage.create(null, "msg3"));

    StreamBuilder builder =
        new StreamBuilder(mockNode, Duration.ofSeconds(5), mockTracker, mockControlBusGateway);

    Flux<Message<?>> built = builder.withSource(sourceStream).withTaskTracking("exec-006").build();

    StepVerifier.create(built).expectNextCount(3).verifyComplete();

    verify(mockTracker, times(1))
        .emitTaskStatusEvent("exec-006", "node-001", "default", "RUNNING", Collections.emptyMap());
    verify(mockTracker, times(1))
        .emitTaskStatusEvent("exec-006", "node-001", "default", "SUCCESS", Collections.emptyMap());
  }
}
