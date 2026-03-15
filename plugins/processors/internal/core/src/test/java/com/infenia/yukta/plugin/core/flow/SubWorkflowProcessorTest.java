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
package com.infenia.yukta.plugin.core.flow;

import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.gateway.WorkflowGateway;
import com.infenia.yukta.plugin.message.DefaultMessage;
import com.infenia.yukta.plugin.message.Message;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

@ExtendWith(MockitoExtension.class)
class SubWorkflowProcessorTest {

  @Mock private WorkflowGateway workflowGateway;

  @InjectMocks private SubWorkflowProcessor processor;

  @Test
  @SuppressWarnings("unchecked")
  void testSubWorkflowExecution() {
    final String parentSessionId = "parent";
    final String nodeId = "node1";
    final String childSessionId = parentSessionId + ":" + nodeId;
    final String subWorkflowId = "child-wf";

    final Message subResult = DefaultMessage.create(UUID.randomUUID(), "success-result");

    when(workflowGateway.executeSubWorkflow(
            eq(parentSessionId), eq(childSessionId), eq(subWorkflowId), anyMap()))
        .thenReturn(Mono.just(List.of(subResult)));

    final Map<String, Object> config =
        Map.of(
            "subWorkflowId", subWorkflowId,
            "inputMapper", "#root.payload",
            "outputMapper", "#root[0].payload");

    final Message inputMsg = DefaultMessage.create(UUID.randomUUID(), Map.of("key", "val"));

    StepVerifier.create(
            processor
                .process(Flux.just(inputMsg), config)
                .contextWrite(Context.of("sessionId", parentSessionId, "nodeId", nodeId)))
        .expectNextMatches(msg -> "success-result".equals(msg.getPayload()))
        .verifyComplete();
  }

  @Test
  void testMetadata() {
    org.junit.jupiter.api.Assertions.assertEquals("SUB_WORKFLOW", processor.getType());
    org.junit.jupiter.api.Assertions.assertNotNull(processor.getDescription());
    org.junit.jupiter.api.Assertions.assertNotNull(processor.getUsagePattern());
    org.junit.jupiter.api.Assertions.assertTrue(processor.getUiDesign().isPresent());
    org.junit.jupiter.api.Assertions.assertNotNull(processor.getDefaultTimeout());
  }

  @Test
  void testPrepare() {
    final Map<String, Object> config = Map.of("inputMapper", "'in'", "outputMapper", "'out'");
    StepVerifier.create(processor.prepare(config)).verifyComplete();
  }

  @Test
  void testProcessMissingSubWorkflowId() {
    StepVerifier.create(
            processor.process(
                Flux.just(DefaultMessage.create(UUID.randomUUID(), "test")), Map.of()))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void testMapInputDefault() {
    final String subWorkflowId = "wf";
    when(workflowGateway.executeSubWorkflow(
            eq("p"), eq("p:n"), eq(subWorkflowId), eq(Map.of("payload", "data"))))
        .thenReturn(Mono.just(List.of(DefaultMessage.create(UUID.randomUUID(), "res"))));

    final Map<String, Object> config = Map.of("subWorkflowId", subWorkflowId);
    final Message inputMsg = DefaultMessage.create(UUID.randomUUID(), "data");

    StepVerifier.create(
            processor
                .process(Flux.just(inputMsg), config)
                .contextWrite(Context.of("sessionId", "p", "nodeId", "n")))
        .expectNextMatches(
            m -> {
              List results = (List) m.getPayload();
              return "res".equals(((Message) results.get(0)).getPayload());
            })
        .verifyComplete();
  }

  @Test
  void testMapInputMapPayload() {
    final String subWorkflowId = "wf";
    final Map<String, Object> payload = Map.of("k", "v");
    when(workflowGateway.executeSubWorkflow(eq("p"), eq("p:n"), eq(subWorkflowId), eq(payload)))
        .thenReturn(Mono.just(List.of(DefaultMessage.create(UUID.randomUUID(), "res"))));

    final Map<String, Object> config = Map.of("subWorkflowId", subWorkflowId);
    final Message inputMsg = DefaultMessage.create(UUID.randomUUID(), payload);

    StepVerifier.create(
            processor
                .process(Flux.just(inputMsg), config)
                .contextWrite(Context.of("sessionId", "p", "nodeId", "n")))
        .expectNextCount(1)
        .verifyComplete();
  }
}
