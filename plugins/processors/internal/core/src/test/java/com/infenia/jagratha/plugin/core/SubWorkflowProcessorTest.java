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
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.config.AppConfigService;
import com.infenia.yukta.model.PreparedWorkflow;
import com.infenia.yukta.model.WorkflowDefinition;
import com.infenia.yukta.plugin.Message;
import com.infenia.yukta.plugin.ResultCollector;
import com.infenia.yukta.service.WorkflowOrchestrator;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

@ExtendWith(MockitoExtension.class)
class SubWorkflowProcessorTest {

  @Mock private ObjectProvider<WorkflowOrchestrator> orchestratorProvider;
  @Mock private ObjectProvider<AppConfigService> configServiceProvider;
  @Mock private WorkflowOrchestrator orchestrator;
  @Mock private AppConfigService configService;

  @InjectMocks private SubWorkflowProcessor processor;

  @BeforeEach
  void setUp() {
    when(orchestratorProvider.getIfAvailable()).thenReturn(orchestrator);
    when(configServiceProvider.getIfAvailable()).thenReturn(configService);
  }

  @Test
  @SuppressWarnings("unchecked")
  void testSubWorkflowExecution() {
    final String parentSessionId = "parent";
    final String nodeId = "node1";
    final String childSessionId = parentSessionId + ":" + nodeId;
    final String subWorkflowId = "child-wf";

    final WorkflowDefinition subDef = mock(WorkflowDefinition.class);
    final PreparedWorkflow prepared = mock(PreparedWorkflow.class);
    final Message subResult = Message.create(UUID.randomUUID(), "success-result");

    when(configService.getWorkflow(eq(parentSessionId), eq(subWorkflowId)))
        .thenReturn(Mono.just(subDef));
    when(configService.getProjectPath(parentSessionId)).thenReturn(Mono.just("/path"));
    when(configService.setProjectPath(eq(childSessionId), any())).thenReturn(Mono.empty());
    when(configService.getWorkflows(parentSessionId)).thenReturn(Mono.just(Map.of()));
    when(configService.setWorkflows(eq(childSessionId), any())).thenReturn(Mono.empty());
    when(configService.getInitiator(parentSessionId)).thenReturn(Mono.just("user"));
    when(configService.setInitiator(eq(childSessionId), any())).thenReturn(Mono.empty());
    when(configService.getDescription(parentSessionId)).thenReturn(Mono.just("desc"));
    when(configService.setDescription(eq(childSessionId), any())).thenReturn(Mono.empty());

    when(orchestrator.prepareWorkflow(subDef)).thenReturn(Mono.just(prepared));
    when(orchestrator.execute(
            eq(childSessionId), eq(subWorkflowId), anyString(), eq(prepared), anyMap()))
        .thenAnswer(
            invocation -> {
              // Simulate terminal message collection
              return Mono.deferContextual(
                  ctx -> {
                    ctx.<ResultCollector>getOrEmpty("resultCollector")
                        .ifPresent(c -> c.add(subResult));
                    return Mono.empty();
                  });
            });

    final Map<String, Object> config =
        Map.of(
            "subWorkflowId", subWorkflowId,
            "inputMapper", "#root.payload",
            "outputMapper", "#root[0].payload");

    final Message inputMsg = Message.create(UUID.randomUUID(), Map.of("key", "val"));

    StepVerifier.create(
            processor
                .process(Flux.just(inputMsg), config)
                .contextWrite(Context.of("sessionId", parentSessionId, "nodeId", nodeId)))
        .expectNextMatches(msg -> "success-result".equals(msg.payload()))
        .verifyComplete();
  }
}
