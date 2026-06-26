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

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.junit.jupiter.MockitoSettings;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

@MockitoSettings
class ExecutionContextBuilderTest {

  @Test
  void testBuildContextWithAllFields() {
    ExecutionContextBuilder builder = new ExecutionContextBuilder();
    Context context =
        builder
            .sessionId("session-123")
            .workflowId("workflow-456")
            .executionId("exec-789")
            .nodeId("node-001")
            .payload(Map.of("key", "value"))
            .build();

    assertEquals("session-123", context.get("sessionId"));
    assertEquals("workflow-456", context.get("workflowId"));
    assertEquals("exec-789", context.get("executionId"));
    assertEquals("node-001", context.get("nodeId"));
    assertEquals(Map.of("key", "value"), context.get("payload"));
  }

  @Test
  void testApplyContextToMono() {
    ExecutionContextBuilder builder =
        new ExecutionContextBuilder()
            .sessionId("session-123")
            .workflowId("workflow-456")
            .executionId("exec-789")
            .nodeId("node-001")
            .payload(Map.of("data", "test"));

    Mono<String> mono = Mono.deferContextual(ctx -> Mono.just((String) ctx.get("sessionId")));

    StepVerifier.create(builder.applyContextTo(mono)).expectNext("session-123").verifyComplete();
  }

  @Test
  void testBuildContextIsImmutable() {
    ExecutionContextBuilder builder = new ExecutionContextBuilder().sessionId("session-1");
    Context ctx1 = builder.build();

    builder.sessionId("session-2");
    Context ctx2 = builder.build();

    assertEquals("session-1", ctx1.get("sessionId"));
    assertEquals("session-2", ctx2.get("sessionId"));
  }

  @Test
  void testContextBuilderConstants() {
    assertEquals("sessionId", ExecutionContextBuilder.CTX_SESSION_ID);
    assertEquals("workflowId", ExecutionContextBuilder.CTX_WORKFLOW_ID);
    assertEquals("executionId", ExecutionContextBuilder.CTX_EXECUTION_ID);
    assertEquals("nodeId", ExecutionContextBuilder.CTX_NODE_ID);
    assertEquals("payload", ExecutionContextBuilder.CTX_PAYLOAD);
  }

  @Test
  void testApplyContextToFlux() {
    ExecutionContextBuilder builder =
        new ExecutionContextBuilder().sessionId("session-123").workflowId("workflow-456");

    Flux<String> flux =
        Flux.deferContextual(
            ctx -> Flux.just((String) ctx.get("sessionId"), (String) ctx.get("workflowId")));

    StepVerifier.create(builder.applyContextTo(flux))
        .expectNext("session-123")
        .expectNext("workflow-456")
        .verifyComplete();
  }

  @Test
  void testBuildContextWithPartialFields() {
    ExecutionContextBuilder builder = new ExecutionContextBuilder();
    Context context = builder.sessionId("session-123").executionId("exec-789").build();

    assertEquals("session-123", context.get("sessionId"));
    assertEquals("exec-789", context.get("executionId"));
  }

  @Test
  void testBuildContextWithoutFields() {
    ExecutionContextBuilder builder = new ExecutionContextBuilder();
    Context context = builder.build();

    assertTrue(context.isEmpty());
  }

  @Test
  void testPayloadImmutability() {
    Map<String, Object> originalPayload = new java.util.HashMap<>();
    originalPayload.put("key1", "value1");

    ExecutionContextBuilder builder = new ExecutionContextBuilder().payload(originalPayload);
    Context context = builder.build();

    @SuppressWarnings("unchecked")
    Map<String, Object> contextPayload = (Map<String, Object>) context.get("payload");

    assertThrows(UnsupportedOperationException.class, () -> contextPayload.put("key2", "value2"));
  }

  @Test
  void testFluentChaining() {
    ExecutionContextBuilder builder =
        new ExecutionContextBuilder()
            .sessionId("s1")
            .workflowId("w1")
            .executionId("e1")
            .nodeId("n1")
            .payload(Map.of("data", "value"));

    Context context = builder.build();

    assertEquals("s1", context.get("sessionId"));
    assertEquals("w1", context.get("workflowId"));
    assertEquals("e1", context.get("executionId"));
    assertEquals("n1", context.get("nodeId"));
  }

  @Test
  void testPayloadWithNullValue() {
    ExecutionContextBuilder builder = new ExecutionContextBuilder().payload(null);
    Context context = builder.build();

    assertTrue(context.isEmpty());
  }

  @Test
  void testBuildContextWithOneField() {
    ExecutionContextBuilder builder = new ExecutionContextBuilder().sessionId("session-only");
    Context context = builder.build();

    assertEquals("session-only", context.get("sessionId"));
    assertEquals(1, context.stream().count());
  }

  @Test
  void testBuildContextWithWorkflowIdOnly() {
    ExecutionContextBuilder builder = new ExecutionContextBuilder().workflowId("workflow-only");
    Context context = builder.build();

    assertEquals("workflow-only", context.get("workflowId"));
  }

  @Test
  void testBuildContextWithExecutionIdOnly() {
    ExecutionContextBuilder builder = new ExecutionContextBuilder().executionId("exec-only");
    Context context = builder.build();

    assertEquals("exec-only", context.get("executionId"));
  }

  @Test
  void testBuildContextWithNodeIdOnly() {
    ExecutionContextBuilder builder = new ExecutionContextBuilder().nodeId("node-only");
    Context context = builder.build();

    assertEquals("node-only", context.get("nodeId"));
  }

  @Test
  void testBuildContextWithPayloadOnly() {
    ExecutionContextBuilder builder = new ExecutionContextBuilder().payload(Map.of("key", "value"));
    Context context = builder.build();

    assertEquals(Map.of("key", "value"), context.get("payload"));
  }

  @Test
  void testPayloadDefensiveCopy() {
    Map<String, Object> originalPayload = new java.util.HashMap<>();
    originalPayload.put("key1", "value1");

    ExecutionContextBuilder builder = new ExecutionContextBuilder().payload(originalPayload);

    // Modify original after setting
    originalPayload.put("key2", "value2");

    Context context = builder.build();

    @SuppressWarnings("unchecked")
    Map<String, Object> contextPayload = (Map<String, Object>) context.get("payload");

    // Context should only have key1
    assertEquals(1, contextPayload.size());
    assertTrue(contextPayload.containsKey("key1"));
    assertFalse(contextPayload.containsKey("key2"));
  }
}
