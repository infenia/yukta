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
package com.infenia.yukta.service.orchestrator.assembly;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.message.DefaultMessage;
import com.infenia.yukta.message.Message;
import com.infenia.yukta.service.control.ExecutionControl;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
@NoArgsConstructor
@SuppressWarnings({
  "PMD.CommentRequired",
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods",
  "PMD.LiteralsFirstInComparisons",
  "PMD.UseShortArrayInitializer"
})
class AssemblyContextTest {

  @Mock private ExecutionControl mockControl;
  @Mock private Disposable mockDisposable;
  @Mock private Runnable mockConnector;

  private Flux<Message<?>> mockStream;
  private Mono<Void> mockTerminal;

  @BeforeEach
  void setUp() {
    mockStream = Flux.just(DefaultMessage.create(null, "test-payload"));
    mockTerminal = Mono.empty();
  }

  @Test
  void testAllArgsConstructor() {
    final AssemblyContext context =
        new AssemblyContext(
            "exec-001",
            "session-001",
            "workflow-001",
            Map.of("key", "value"),
            mockControl,
            new Flux[] {mockStream},
            List.of(mockTerminal),
            List.of(mockDisposable),
            List.of(mockConnector));

    assertThat(context.executionId()).isEqualTo("exec-001");
    assertThat(context.sessionId()).isEqualTo("session-001");
    assertThat(context.workflowId()).isEqualTo("workflow-001");
    assertThat(context.payload()).containsEntry("key", "value");
    assertThat(context.control()).isSameAs(mockControl);
    assertThat(context.streams()).hasSize(1);
    assertThat(context.terminals()).hasSize(1);
    assertThat(context.disposables()).hasSize(1);
    assertThat(context.connectors()).hasSize(1);
  }

  @Test
  void testRecordComponentsNotNull() {
    final AssemblyContext context =
        new AssemblyContext(
            "exec-001",
            "session-001",
            "workflow-001",
            Map.of("key", "value"),
            mockControl,
            new Flux[] {mockStream},
            List.of(mockTerminal),
            List.of(mockDisposable),
            List.of(mockConnector));

    assertThat(context.executionId()).isNotNull();
    assertThat(context.sessionId()).isNotNull();
    assertThat(context.workflowId()).isNotNull();
    assertThat(context.payload()).isNotNull();
    assertThat(context.control()).isNotNull();
    assertThat(context.streams()).isNotNull();
    assertThat(context.terminals()).isNotNull();
    assertThat(context.disposables()).isNotNull();
    assertThat(context.connectors()).isNotNull();
  }

  @Test
  void testPayloadImmutability() {
    final Map<String, Object> originalPayload = Map.of("data", "value");
    final AssemblyContext context =
        new AssemblyContext(
            "exec-001",
            "session-001",
            "workflow-001",
            originalPayload,
            mockControl,
            new Flux[] {mockStream},
            List.of(mockTerminal),
            List.of(mockDisposable),
            List.of(mockConnector));

    final Map<String, Object> contextPayload = context.payload();
    assertThat(contextPayload).containsEntry("data", "value");
    assertThatThrownBy(() -> contextPayload.put("newKey", "newValue"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void testControlAccessor() {
    final AssemblyContext context =
        new AssemblyContext(
            "exec-001",
            "session-001",
            "workflow-001",
            Map.of(),
            mockControl,
            new Flux[] {mockStream},
            List.of(mockTerminal),
            List.of(mockDisposable),
            List.of(mockConnector));

    assertThat(context.control()).isSameAs(mockControl);
  }

  @Test
  void testStreamsAccessor() {
    final Flux<Message<?>> stream1 = Flux.just(DefaultMessage.create(null, "msg1"));
    final Flux<Message<?>> stream2 = Flux.just(DefaultMessage.create(null, "msg2"));
    final AssemblyContext context =
        new AssemblyContext(
            "exec-001",
            "session-001",
            "workflow-001",
            Map.of(),
            mockControl,
            new Flux[] {stream1, stream2},
            List.of(mockTerminal),
            List.of(mockDisposable),
            List.of(mockConnector));

    assertThat(context.streams()).hasSize(2);
    final Flux<Message<?>> combined = Flux.concat(context.streams()[0], context.streams()[1]);
    StepVerifier.create(combined)
        .expectNextMatches(msg -> msg.getPayload().equals("msg1"))
        .expectNextMatches(msg -> msg.getPayload().equals("msg2"))
        .verifyComplete();
  }

  @Test
  void testTerminalsAccessor() {
    final Mono<Void> terminal1 = Mono.empty();
    final Mono<Void> terminal2 = Mono.empty();
    final AssemblyContext context =
        new AssemblyContext(
            "exec-001",
            "session-001",
            "workflow-001",
            Map.of(),
            mockControl,
            new Flux[] {mockStream},
            List.of(terminal1, terminal2),
            List.of(mockDisposable),
            List.of(mockConnector));

    assertThat(context.terminals()).hasSize(2);
    final Mono<Void> combined = Mono.when(context.terminals());
    StepVerifier.create(combined).verifyComplete();
  }

  @Test
  void testDisposablesAccessor() {
    final AssemblyContext context =
        new AssemblyContext(
            "exec-001",
            "session-001",
            "workflow-001",
            Map.of(),
            mockControl,
            new Flux[] {mockStream},
            List.of(mockTerminal),
            List.of(mockDisposable, mockDisposable),
            List.of(mockConnector));

    assertThat(context.disposables()).hasSize(2);
  }

  @Test
  void testConnectorsAccessor() {
    final AssemblyContext context =
        new AssemblyContext(
            "exec-001",
            "session-001",
            "workflow-001",
            Map.of(),
            mockControl,
            new Flux[] {mockStream},
            List.of(mockTerminal),
            List.of(mockDisposable),
            List.of(mockConnector, mockConnector));

    assertThat(context.connectors()).hasSize(2);
  }

  @Test
  void testWithNullPayload() {
    final AssemblyContext context =
        new AssemblyContext(
            "exec-001",
            "session-001",
            "workflow-001",
            null,
            mockControl,
            new Flux[] {mockStream},
            List.of(mockTerminal),
            List.of(mockDisposable),
            List.of(mockConnector));

    assertThat(context.payload()).isNull();
  }

  @Test
  void testWithEmptyCollections() {
    final AssemblyContext context =
        new AssemblyContext(
            "exec-001",
            "session-001",
            "workflow-001",
            Map.of(),
            mockControl,
            new Flux[] {},
            List.of(),
            List.of(),
            List.of());

    assertThat(context.streams()).isEmpty();
    assertThat(context.terminals()).isEmpty();
    assertThat(context.disposables()).isEmpty();
    assertThat(context.connectors()).isEmpty();
  }

  @Test
  void testToString() {
    final AssemblyContext context =
        new AssemblyContext(
            "exec-001",
            "session-001",
            "workflow-001",
            Map.of(),
            mockControl,
            new Flux[] {mockStream},
            List.of(mockTerminal),
            List.of(mockDisposable),
            List.of(mockConnector));

    final String str = context.toString();
    assertThat(str).contains("exec-001");
    assertThat(str).contains("session-001");
    assertThat(str).contains("workflow-001");
  }

  @Test
  void testEquality() {
    final Flux<Message<?>>[] streams = new Flux[] {mockStream};
    final AssemblyContext context1 =
        new AssemblyContext(
            "exec-001",
            "session-001",
            "workflow-001",
            Map.of(),
            mockControl,
            streams,
            List.of(mockTerminal),
            List.of(mockDisposable),
            List.of(mockConnector));
    final AssemblyContext context2 =
        new AssemblyContext(
            "exec-001",
            "session-001",
            "workflow-001",
            Map.of(),
            mockControl,
            streams,
            List.of(mockTerminal),
            List.of(mockDisposable),
            List.of(mockConnector));

    assertThat(context1).isEqualTo(context2);
    assertThat(context1.hashCode()).isEqualTo(context2.hashCode());
  }

  @Test
  void testInequality() {
    final AssemblyContext context1 =
        new AssemblyContext(
            "exec-001",
            "session-001",
            "workflow-001",
            Map.of(),
            mockControl,
            new Flux[] {mockStream},
            List.of(mockTerminal),
            List.of(mockDisposable),
            List.of(mockConnector));
    final AssemblyContext context2 =
        new AssemblyContext(
            "exec-002",
            "session-002",
            "workflow-002",
            Map.of(),
            mockControl,
            new Flux[] {mockStream},
            List.of(mockTerminal),
            List.of(mockDisposable),
            List.of(mockConnector));

    assertThat(context1).isNotEqualTo(context2);
  }
}
