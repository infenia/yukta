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

import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoSettings;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** Unit tests for {@link ExecutionContextBuilder}. */
@MockitoSettings
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.UseConcurrentHashMap", "PMD.TooManyMethods"})
class ExecutionContextBuilderTest {

  @Test
  void testBuildContextWithAllFields() {
    final ExecutionContextBuilder builder = new ExecutionContextBuilder();
    final Context context =
        builder
            .sessionId("session-123")
            .workflowId("workflow-456")
            .executionId("exec-789")
            .nodeId("node-001")
            .payload(Map.of("key", "value"))
            .build();

    assertThat(context.<String>get("sessionId")).isEqualTo("session-123");
    assertThat(context.<String>get("workflowId")).isEqualTo("workflow-456");
    assertThat(context.<String>get("executionId")).isEqualTo("exec-789");
    assertThat(context.<String>get("nodeId")).isEqualTo("node-001");
    assertThat(context.<Map<String, Object>>get("payload")).isEqualTo(Map.of("key", "value"));
  }

  @Test
  void testApplyContextToMono() {
    final ExecutionContextBuilder builder =
        new ExecutionContextBuilder()
            .sessionId("session-123")
            .workflowId("workflow-456")
            .executionId("exec-789")
            .nodeId("node-001")
            .payload(Map.of("data", "test"));

    final Mono<String> mono = Mono.deferContextual(ctx -> Mono.just(ctx.get("sessionId")));

    StepVerifier.create(builder.applyContextTo(mono)).expectNext("session-123").verifyComplete();
  }

  @Test
  void testBuildContextIsImmutable() {
    final ExecutionContextBuilder builder = new ExecutionContextBuilder().sessionId("session-1");
    final Context ctx1 = builder.build();

    builder.sessionId("session-2");
    final Context ctx2 = builder.build();

    assertThat(ctx1.<String>get("sessionId")).isEqualTo("session-1");
    assertThat(ctx2.<String>get("sessionId")).isEqualTo("session-2");
  }

  @Test
  void testContextBuilderConstants() {
    assertThat(ExecutionContextBuilder.CTX_SESSION_ID).isEqualTo("sessionId");
    assertThat(ExecutionContextBuilder.CTX_WORKFLOW_ID).isEqualTo("workflowId");
    assertThat(ExecutionContextBuilder.CTX_EXECUTION_ID).isEqualTo("executionId");
    assertThat(ExecutionContextBuilder.CTX_NODE_ID).isEqualTo("nodeId");
    assertThat(ExecutionContextBuilder.CTX_PAYLOAD).isEqualTo("payload");
  }

  @Test
  void testApplyContextToFlux() {
    final ExecutionContextBuilder builder =
        new ExecutionContextBuilder().sessionId("session-123").workflowId("workflow-456");

    final Flux<String> flux =
        Flux.deferContextual(ctx -> Flux.just(ctx.get("sessionId"), ctx.get("workflowId")));

    StepVerifier.create(builder.applyContextTo(flux))
        .expectNext("session-123")
        .expectNext("workflow-456")
        .verifyComplete();
  }

  @Test
  void testBuildContextWithEmptyBuilder() {
    final ExecutionContextBuilder builder = new ExecutionContextBuilder();
    final Context context = builder.build();

    assertThat(context.isEmpty()).isTrue();
  }

  @Test
  void testPayloadImmutability() {
    final Map<String, Object> originalPayload = new java.util.HashMap<>();
    originalPayload.put("key1", "value1");

    final ExecutionContextBuilder builder = new ExecutionContextBuilder().payload(originalPayload);
    final Context context = builder.build();

    final Map<String, Object> contextPayload = context.get("payload");

    assertThatThrownBy(() -> contextPayload.put("key2", "value2"))
        .isInstanceOf(UnsupportedOperationException.class);
  }

  @Test
  void testFluentChaining() {
    final ExecutionContextBuilder builder =
        new ExecutionContextBuilder()
            .sessionId("s1")
            .workflowId("w1")
            .executionId("e1")
            .nodeId("n1")
            .payload(Map.of("data", "value"));

    final Context context = builder.build();

    assertThat(context.<String>get("sessionId")).isEqualTo("s1");
    assertThat(context.<String>get("workflowId")).isEqualTo("w1");
    assertThat(context.<String>get("executionId")).isEqualTo("e1");
    assertThat(context.<String>get("nodeId")).isEqualTo("n1");
  }

  @Test
  void testPayloadWithNullValue() {
    final ExecutionContextBuilder builder = new ExecutionContextBuilder().payload(null);
    final Context context = builder.build();

    assertThat(context.isEmpty()).isTrue();
  }

  @Test
  void testBuildContextWithOneField() {
    final ExecutionContextBuilder builder = new ExecutionContextBuilder().sessionId("session-only");
    final Context context = builder.build();

    assertThat(context.<String>get("sessionId")).isEqualTo("session-only");
    assertThat(context.stream().count()).isEqualTo(1L);
  }

  @Test
  void testBuildContextWithWorkflowIdOnly() {
    final ExecutionContextBuilder builder =
        new ExecutionContextBuilder().workflowId("workflow-only");
    final Context context = builder.build();

    assertThat(context.<String>get("workflowId")).isEqualTo("workflow-only");
  }

  @Test
  void testBuildContextWithExecutionIdOnly() {
    final ExecutionContextBuilder builder = new ExecutionContextBuilder().executionId("exec-only");
    final Context context = builder.build();

    assertThat(context.<String>get("executionId")).isEqualTo("exec-only");
  }

  @Test
  void testBuildContextWithNodeIdOnly() {
    final ExecutionContextBuilder builder = new ExecutionContextBuilder().nodeId("node-only");
    final Context context = builder.build();

    assertThat(context.<String>get("nodeId")).isEqualTo("node-only");
  }

  @Test
  void testBuildContextWithPayloadOnly() {
    final ExecutionContextBuilder builder =
        new ExecutionContextBuilder().payload(Map.of("key", "value"));
    final Context context = builder.build();

    assertThat(context.<Map<String, Object>>get("payload")).isEqualTo(Map.of("key", "value"));
  }

  @Test
  void testPayloadDefensiveCopy() {
    final Map<String, Object> originalPayload = new java.util.HashMap<>();
    originalPayload.put("key1", "value1");

    final ExecutionContextBuilder builder = new ExecutionContextBuilder().payload(originalPayload);

    // Modify original after setting
    originalPayload.put("key2", "value2");

    final Context context = builder.build();

    final Map<String, Object> contextPayload = context.get("payload");

    // Context should only have key1
    assertThat(contextPayload).hasSize(1);
    assertThat(contextPayload.containsKey("key1")).isTrue();
    assertThat(contextPayload.containsKey("key2")).isFalse();
  }
}
