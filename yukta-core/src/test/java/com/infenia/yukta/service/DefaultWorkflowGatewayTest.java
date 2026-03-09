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
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.config.AppConfigService;
import com.infenia.yukta.model.PreparedWorkflow;
import com.infenia.yukta.model.WorkflowDefinition;
import com.infenia.yukta.plugin.exception.WorkflowExecutionException;
import com.infenia.yukta.plugin.message.Message;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

class DefaultWorkflowGatewayTest {

  private WorkflowOrchestrator orchestrator;
  private AppConfigService configService;
  private DefaultWorkflowGateway gateway;

  @BeforeEach
  void setUp() {
    orchestrator = mock(WorkflowOrchestrator.class);
    configService = mock(AppConfigService.class);
    ObjectProvider<WorkflowOrchestrator> orchProv = mock(ObjectProvider.class);
    ObjectProvider<AppConfigService> cfgServProv = mock(ObjectProvider.class);

    when(orchProv.getIfAvailable()).thenReturn(orchestrator);
    when(cfgServProv.getIfAvailable()).thenReturn(configService);

    gateway = new DefaultWorkflowGateway(orchProv, cfgServProv);
  }

  @Test
  void testExecuteSubWorkflow_Success() {
    WorkflowDefinition def = mock(WorkflowDefinition.class);
    PreparedWorkflow prepared = mock(PreparedWorkflow.class);

    when(configService.getWorkflow(anyString(), anyString())).thenReturn(Mono.just(def));
    when(configService.getProjectPath(anyString())).thenReturn(Mono.just("path"));
    when(configService.setProjectPath(anyString(), anyString())).thenReturn(Mono.empty());
    when(configService.getWorkflows(anyString())).thenReturn(Mono.just(Map.of()));
    when(configService.setWorkflows(anyString(), any())).thenReturn(Mono.empty());
    when(configService.getInitiator(anyString())).thenReturn(Mono.just("initiator"));
    when(configService.setInitiator(anyString(), anyString())).thenReturn(Mono.empty());
    when(configService.getDescription(anyString())).thenReturn(Mono.just("desc"));
    when(configService.setDescription(anyString(), anyString())).thenReturn(Mono.empty());
    when(orchestrator.prepareWorkflow(def)).thenReturn(Mono.just(prepared));
    when(orchestrator.execute(anyString(), anyString(), anyString(), eq(prepared), any()))
        .thenReturn(Mono.empty());

    StepVerifier.create(gateway.executeSubWorkflow("p", "c", "w", Map.of()))
        .expectNextMatches(List::isEmpty)
        .verifyComplete();
  }

  @Test
  void testExecuteSubWorkflow_ServicesNotAvailable() {
    ObjectProvider<WorkflowOrchestrator> orchProv = mock(ObjectProvider.class);
    ObjectProvider<AppConfigService> cfgServProv = mock(ObjectProvider.class);
    when(orchProv.getIfAvailable()).thenReturn(null);
    DefaultWorkflowGateway g = new DefaultWorkflowGateway(orchProv, cfgServProv);

    StepVerifier.create(g.executeSubWorkflow("p", "c", "w", Map.of()))
        .expectError(WorkflowExecutionException.class)
        .verify();

    when(orchProv.getIfAvailable()).thenReturn(orchestrator);
    when(cfgServProv.getIfAvailable()).thenReturn(null);
    DefaultWorkflowGateway g2 = new DefaultWorkflowGateway(orchProv, cfgServProv);
    StepVerifier.create(g2.executeSubWorkflow("p", "c", "w", Map.of()))
        .expectError(WorkflowExecutionException.class)
        .verify();
  }

  @Test
  void testExecuteSubWorkflow_WorkflowNotFound() {
    when(configService.getWorkflow(anyString(), anyString())).thenReturn(Mono.empty());

    StepVerifier.create(gateway.executeSubWorkflow("p", "c", "w", Map.of()))
        .expectError(WorkflowExecutionException.class)
        .verify();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testSendAndReceive_Success() {
    Message request = mock(Message.class);
    when(request.getMetadata()).thenReturn(Map.of("workflowId", "w"));
    when(request.getPayload()).thenReturn("p");
    when(request.withPayload(any())).thenReturn(request);

    WorkflowDefinition def = mock(WorkflowDefinition.class);
    PreparedWorkflow prepared = mock(PreparedWorkflow.class);
    when(configService.getWorkflow(anyString(), anyString())).thenReturn(Mono.just(def));
    when(configService.getProjectPath(anyString())).thenReturn(Mono.just("path"));
    when(configService.setProjectPath(anyString(), anyString())).thenReturn(Mono.empty());
    when(configService.getWorkflows(anyString())).thenReturn(Mono.just(Map.of()));
    when(configService.setWorkflows(anyString(), any())).thenReturn(Mono.empty());
    when(configService.getInitiator(anyString())).thenReturn(Mono.just("initiator"));
    when(configService.setInitiator(anyString(), anyString())).thenReturn(Mono.empty());
    when(configService.getDescription(anyString())).thenReturn(Mono.just("desc"));
    when(configService.setDescription(anyString(), anyString())).thenReturn(Mono.empty());
    when(orchestrator.prepareWorkflow(def)).thenReturn(Mono.just(prepared));
    when(orchestrator.execute(anyString(), anyString(), anyString(), eq(prepared), any()))
        .thenReturn(Mono.empty());

    StepVerifier.create(gateway.sendAndReceive(request)).expectNext(request).verifyComplete();

    // Coverage for Map payload
    Message request2 = mock(Message.class);
    when(request2.getMetadata()).thenReturn(Map.of("workflowId", "w"));
    when(request2.getPayload()).thenReturn(Map.of("k", "v"));
    when(request2.withPayload(any())).thenReturn(request2);
    StepVerifier.create(gateway.sendAndReceive(request2)).expectNext(request2).verifyComplete();
  }

  @Test
  void testSendAndReceive_MissingWorkflowId() {
    Message<String> request = mock(Message.class);
    when(request.getMetadata()).thenReturn(Map.of());

    StepVerifier.create(gateway.sendAndReceive(request))
        .expectError(WorkflowExecutionException.class)
        .verify();
  }

  @Test
  @SuppressWarnings("unchecked")
  void testSend() {
    Message request = mock(Message.class);
    when(request.getMetadata()).thenReturn(Map.of("workflowId", "w"));
    when(request.getPayload()).thenReturn("p");
    when(request.withPayload(any())).thenReturn(request);

    WorkflowDefinition def = mock(WorkflowDefinition.class);
    PreparedWorkflow prepared = mock(PreparedWorkflow.class);
    when(configService.getWorkflow(anyString(), anyString())).thenReturn(Mono.just(def));
    when(configService.getProjectPath(anyString())).thenReturn(Mono.just("path"));
    when(configService.setProjectPath(anyString(), anyString())).thenReturn(Mono.empty());
    when(configService.getWorkflows(anyString())).thenReturn(Mono.just(Map.of()));
    when(configService.setWorkflows(anyString(), any())).thenReturn(Mono.empty());
    when(configService.getInitiator(anyString())).thenReturn(Mono.just("initiator"));
    when(configService.setInitiator(anyString(), anyString())).thenReturn(Mono.empty());
    when(configService.getDescription(anyString())).thenReturn(Mono.just("desc"));
    when(configService.setDescription(anyString(), anyString())).thenReturn(Mono.empty());
    when(orchestrator.prepareWorkflow(def)).thenReturn(Mono.just(prepared));
    when(orchestrator.execute(anyString(), anyString(), anyString(), eq(prepared), any()))
        .thenReturn(Mono.empty());

    StepVerifier.create(gateway.send(request)).verifyComplete();
  }

  @Test
  void testReceive() {
    StepVerifier.create(gateway.receive()).verifyComplete();
  }

  @Test
  void testExecuteSubWorkflow_GenericError() {
    when(configService.getWorkflow(anyString(), anyString()))
        .thenReturn(Mono.error(new RuntimeException("Oops")));

    StepVerifier.create(gateway.executeSubWorkflow("p", "c", "w", Map.of()))
        .expectError(WorkflowExecutionException.class)
        .verify();
  }

  @Test
  void testExecuteSubWorkflow_WorkflowExecutionExceptionPropagated() {
    when(configService.getWorkflow(anyString(), anyString()))
        .thenReturn(Mono.error(new WorkflowExecutionException("Already wrapped")));

    StepVerifier.create(gateway.executeSubWorkflow("p", "c", "w", Map.of()))
        .expectErrorMatches(
            e ->
                e instanceof WorkflowExecutionException && e.getMessage().equals("Already wrapped"))
        .verify();
  }
}
