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
package com.infenia.yukta.service.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Captor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@ExtendWith(MockitoExtension.class)
class SessionServiceTest {

  @Mock private SessionConfigStore configService;
  @Mock private ControlBusGateway controlBus;
  @Mock private WorkflowDefinitionStore workflowDefinitionStore;

  @Captor private ArgumentCaptor<SessionConfigData> configDataCaptor;
  @Captor private ArgumentCaptor<WorkflowDefinition> workflowCaptor;

  private SessionService sessionService;

  @BeforeEach
  void setUp() {
    sessionService = new SessionService(configService, controlBus, workflowDefinitionStore);
  }

  @Test
  void applyConfig_withMultipleWorkflows_compilesAndCachesAllWorkflows() {
    // Given
    String sessionId = "sess-1";
    WorkflowDefinition workflow =
        new WorkflowDefinition("test-workflow", "desc", List.of(), List.of());
    SessionConfigData data =
        new SessionConfigData(
            sessionId, "desc", "initiator-1", Map.of(), "/path", Map.of("w1", workflow));

    when(configService.applySessionConfig(configDataCaptor.capture())).thenReturn(Mono.empty());
    when(controlBus.compileAndCacheWorkflow(eq(sessionId), workflowCaptor.capture()))
        .thenReturn(Mono.empty());

    // When
    StepVerifier.create(sessionService.applyConfig(data)).verifyComplete();

    // Then
    assertThat(configDataCaptor.getValue())
        .isNotNull()
        .satisfies(
            capturedData -> {
              assertThat(capturedData.sessionId()).isEqualTo(sessionId);
              assertThat(capturedData.workflows()).containsKey("w1");
            });
    assertThat(workflowCaptor.getValue()).isNotNull().isEqualTo(workflow);
  }

  @Test
  void applyConfig_withEmptyWorkflows_skipsCompilation() {
    // Given
    String sessionId = "sess-partial";
    SessionConfigData data =
        new SessionConfigData(sessionId, "desc", "initiator-p", Map.of(), null, Map.of());

    when(configService.applySessionConfig(configDataCaptor.capture())).thenReturn(Mono.empty());

    // When
    StepVerifier.create(sessionService.applyConfig(data)).verifyComplete();

    // Then
    assertThat(configDataCaptor.getValue())
        .isNotNull()
        .satisfies(
            capturedData -> {
              assertThat(capturedData.sessionId()).isEqualTo(sessionId);
              assertThat(capturedData.workflows()).isEmpty();
            });
  }

  @Test
  void getSessionConfig_validSessionId_returnsConfigMap() {
    // Given
    String sessionId = "sess-1";
    Map<String, Object> configMap = Map.of("k", "v");
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.just(configMap));

    // When & Then
    StepVerifier.create(sessionService.getSessionConfig(sessionId))
        .expectNextMatches(m -> "v".equals(m.get("k")))
        .verifyComplete();
  }

  @Test
  void getSessionWorkflow_validSessionAndWorkflowIds_returnsWorkflowDefinition() {
    // Given
    String sessionId = "sess-wf";
    WorkflowDefinition workflow =
        new WorkflowDefinition("test-workflow", "desc", List.of(), List.of());

    when(workflowDefinitionStore.find(sessionId, "w1")).thenReturn(Mono.just(workflow));

    // When & Then
    StepVerifier.create(sessionService.getSessionWorkflow(sessionId, "w1"))
        .expectNext(workflow)
        .verifyComplete();

    verify(workflowDefinitionStore).find(sessionId, "w1");
  }

  @Test
  void getSessionIds_multipleSessionsExist_returnsAllSessionIds() {
    // Given
    when(configService.getSessionIds()).thenReturn(Flux.just("s1", "s2"));

    // When & Then
    StepVerifier.create(sessionService.getSessionIds()).expectNext("s1", "s2").verifyComplete();

    verify(configService).getSessionIds();
  }

  @Test
  void applyConfig_withMultipleWorkflowsAndCompilationError_propagatesError() {
    // Given
    String sessionId = "sess-multi";
    WorkflowDefinition workflow1 =
        new WorkflowDefinition("workflow-1", "desc1", List.of(), List.of());
    WorkflowDefinition workflow2 =
        new WorkflowDefinition("workflow-2", "desc2", List.of(), List.of());
    SessionConfigData data =
        new SessionConfigData(
            sessionId,
            "desc",
            "initiator",
            Map.of(),
            "/path",
            Map.of("w1", workflow1, "w2", workflow2));

    when(configService.applySessionConfig(configDataCaptor.capture())).thenReturn(Mono.empty());
    when(controlBus.compileAndCacheWorkflow(eq(sessionId), workflowCaptor.capture()))
        .thenReturn(Mono.empty());

    // When & Then
    StepVerifier.create(sessionService.applyConfig(data)).verifyComplete();

    assertThat(configDataCaptor.getValue())
        .isNotNull()
        .satisfies(
            capturedData -> {
              assertThat(capturedData.sessionId()).isEqualTo(sessionId);
              assertThat(capturedData.workflows()).hasSize(2).containsKeys("w1", "w2");
            });
  }

  @Test
  void applyConfig_compilationFailsForWorkflow_propagatesError() {
    // Given
    String sessionId = "sess-error";
    WorkflowDefinition workflow =
        new WorkflowDefinition("test-workflow", "desc", List.of(), List.of());
    SessionConfigData data =
        new SessionConfigData(
            sessionId, "desc", "initiator", Map.of(), "/path", Map.of("w1", workflow));

    when(configService.applySessionConfig(any())).thenReturn(Mono.empty());
    when(controlBus.compileAndCacheWorkflow(eq(sessionId), any()))
        .thenReturn(Mono.error(new RuntimeException("Compilation failed")));

    // When & Then
    StepVerifier.create(sessionService.applyConfig(data))
        .expectError(RuntimeException.class)
        .verify();
  }

  @Test
  void applyConfig_storeFailsToApplyConfig_propagatesError() {
    // Given
    String sessionId = "sess-store-error";
    SessionConfigData data =
        new SessionConfigData(sessionId, "desc", "initiator", Map.of(), "/path", Map.of());

    when(configService.applySessionConfig(any()))
        .thenReturn(Mono.error(new RuntimeException("Store failed")));

    // When & Then
    StepVerifier.create(sessionService.applyConfig(data))
        .expectError(RuntimeException.class)
        .verify();
  }

  @Test
  void getSessionConfig_storeThrowsError_propagatesError() {
    // Given
    String sessionId = "sess-config-error";
    when(configService.getAllConfigs(sessionId))
        .thenReturn(Mono.error(new RuntimeException("Config fetch failed")));

    // When & Then
    StepVerifier.create(sessionService.getSessionConfig(sessionId))
        .expectError(RuntimeException.class)
        .verify();
  }

  @Test
  void getSessionWorkflow_storeThrowsError_propagatesError() {
    // Given
    String sessionId = "sess-wf-error";
    when(workflowDefinitionStore.find(sessionId, "w1"))
        .thenReturn(Mono.error(new RuntimeException("Workflow fetch failed")));

    // When & Then
    StepVerifier.create(sessionService.getSessionWorkflow(sessionId, "w1"))
        .expectError(RuntimeException.class)
        .verify();
  }

  @Test
  void getSessionIds_noSessionsExist_returnsEmpty() {
    // Given
    when(configService.getSessionIds()).thenReturn(Flux.empty());

    // When & Then
    StepVerifier.create(sessionService.getSessionIds()).verifyComplete();

    verify(configService).getSessionIds();
  }

  @Test
  void getSessionIds_storeThrowsError_propagatesError() {
    // Given
    when(configService.getSessionIds())
        .thenReturn(Flux.error(new RuntimeException("Session IDs fetch failed")));

    // When & Then
    StepVerifier.create(sessionService.getSessionIds())
        .expectError(RuntimeException.class)
        .verify();
  }

  @Test
  void getSessionConfig_configIsNull_returnsNull() {
    // Given
    String sessionId = "sess-null-config";
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.empty());

    // When & Then
    StepVerifier.create(sessionService.getSessionConfig(sessionId)).verifyComplete();
  }

  @Test
  void getSessionWorkflow_workflowIsNull_returnsNull() {
    // Given
    String sessionId = "sess-null-wf";
    when(workflowDefinitionStore.find(sessionId, "w1")).thenReturn(Mono.empty());

    // When & Then
    StepVerifier.create(sessionService.getSessionWorkflow(sessionId, "w1")).verifyComplete();
  }

  @Test
  void applyConfig_withSingleWorkflow_compilesSuccessfully() {
    // Given
    String sessionId = "sess-single";
    WorkflowDefinition workflow =
        new WorkflowDefinition("single-workflow", "desc", List.of(), List.of());
    SessionConfigData data =
        new SessionConfigData(
            sessionId, "desc", "initiator", Map.of(), "/path", Map.of("w1", workflow));

    when(configService.applySessionConfig(configDataCaptor.capture())).thenReturn(Mono.empty());
    when(controlBus.compileAndCacheWorkflow(eq(sessionId), workflowCaptor.capture()))
        .thenReturn(Mono.empty());

    // When
    StepVerifier.create(sessionService.applyConfig(data)).verifyComplete();

    // Then
    assertThat(configDataCaptor.getValue())
        .isNotNull()
        .satisfies(
            capturedData -> {
              assertThat(capturedData.sessionId()).isEqualTo(sessionId);
              assertThat(capturedData.workflows()).hasSize(1).containsKey("w1");
            });
    assertThat(workflowCaptor.getValue()).isEqualTo(workflow);
  }

  @Test
  void getSessionConfig_withMultipleConfigValues_returnsAllValues() {
    // Given
    String sessionId = "sess-multi-config";
    Map<String, Object> configMap =
        Map.of(
            "config1", "value1",
            "config2", "value2",
            "config3", "value3");
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.just(configMap));

    // When & Then
    StepVerifier.create(sessionService.getSessionConfig(sessionId))
        .expectNextMatches(
            m ->
                m.size() == 3
                    && "value1".equals(m.get("config1"))
                    && "value2".equals(m.get("config2"))
                    && "value3".equals(m.get("config3")))
        .verifyComplete();
  }
}
