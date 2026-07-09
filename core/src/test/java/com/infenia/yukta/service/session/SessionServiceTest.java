// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
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
import com.infenia.yukta.service.workflow.store.PreparedWorkflowCache;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
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

/** Unit tests for SessionService. */
@SuppressWarnings({"PMD.CommentRequired", "PMD.TooManyMethods", "PMD.TooManyStaticImports"})
@ExtendWith(MockitoExtension.class)
@NoArgsConstructor
class SessionServiceTest {

  private static final String DESC = "desc";
  private static final String PATH = "/path";
  private static final String INITIATOR = "initiator";
  private static final String SESSION_ID_1 = "sess-1";

  @Mock private SessionConfigStore configService;
  @Mock private ControlBusGateway controlBus;
  @Mock private WorkflowDefinitionStore workflowDefinitionStore;
  @Mock private PreparedWorkflowCache preparedWorkflowCache;

  @Captor private ArgumentCaptor<SessionConfigData> configDataCaptor;
  @Captor private ArgumentCaptor<WorkflowDefinition> workflowCaptor;

  private SessionService sessionService;

  @BeforeEach
  void setUp() {
    sessionService =
        new SessionService(
            configService, controlBus, workflowDefinitionStore, preparedWorkflowCache);
  }

  @Test
  void applyConfig_withMultipleWorkflows_compilesAndCachesAllWorkflows() {
    // Given
    final String sessionId = SESSION_ID_1;
    final WorkflowDefinition workflow =
        new WorkflowDefinition("test-workflow", DESC, List.of(), List.of());
    final SessionConfigData data =
        new SessionConfigData(
            sessionId, DESC, "initiator-1", Map.of(), PATH, Map.of("w1", workflow));

    when(configService.applySessionConfig(configDataCaptor.capture())).thenReturn(Mono.empty());
    when(workflowDefinitionStore.findAll(sessionId)).thenReturn(Mono.just(Map.of()));
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
    final String sessionId = "sess-partial";
    final SessionConfigData data =
        new SessionConfigData(sessionId, DESC, "initiator-p", Map.of(), null, Map.of());

    when(configService.applySessionConfig(configDataCaptor.capture())).thenReturn(Mono.empty());
    when(workflowDefinitionStore.findAll(sessionId)).thenReturn(Mono.just(Map.of()));

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
  void applyConfig_workflowDroppedFromConfig_removesStaleDefinitionOnly() {
    // Given: the session previously had workflows "w1" and "w2" persisted...
    final String sessionId = "sess-stale-cleanup";
    final WorkflowDefinition workflowA = new WorkflowDefinition("w1", DESC, List.of(), List.of());
    final WorkflowDefinition workflowB = new WorkflowDefinition("w2", DESC, List.of(), List.of());
    when(workflowDefinitionStore.findAll(sessionId))
        .thenReturn(Mono.just(Map.of("w1", workflowA, "w2", workflowB)));
    when(workflowDefinitionStore.remove(eq(sessionId), any())).thenReturn(Mono.empty());

    // ...and the new config only keeps "w1", dropping "w2".
    final SessionConfigData data =
        new SessionConfigData(sessionId, DESC, INITIATOR, Map.of(), PATH, Map.of("w1", workflowA));
    when(configService.applySessionConfig(any())).thenReturn(Mono.empty());
    when(controlBus.compileAndCacheWorkflow(eq(sessionId), any())).thenReturn(Mono.empty());

    // When
    StepVerifier.create(sessionService.applyConfig(data)).verifyComplete();

    // Then: only the dropped workflow is removed; the kept one is left alone.
    verify(workflowDefinitionStore).remove(sessionId, "w2");
    verify(workflowDefinitionStore, org.mockito.Mockito.never()).remove(sessionId, "w1");
  }

  @Test
  void applyConfig_staleWorkflowRemovalFails_continuesWithCompilation() {
    // Given: removing the stale workflow "w2" fails...
    final String sessionId = "sess-stale-cleanup-error";
    final WorkflowDefinition workflowA = new WorkflowDefinition("w1", DESC, List.of(), List.of());
    final WorkflowDefinition workflowB = new WorkflowDefinition("w2", DESC, List.of(), List.of());
    when(workflowDefinitionStore.findAll(sessionId))
        .thenReturn(Mono.just(Map.of("w1", workflowA, "w2", workflowB)));
    when(workflowDefinitionStore.remove(sessionId, "w2"))
        .thenReturn(Mono.error(new IllegalStateException("boom")));

    final SessionConfigData data =
        new SessionConfigData(sessionId, DESC, INITIATOR, Map.of(), PATH, Map.of("w1", workflowA));
    when(configService.applySessionConfig(any())).thenReturn(Mono.empty());
    when(controlBus.compileAndCacheWorkflow(eq(sessionId), any())).thenReturn(Mono.empty());

    // When: the removal failure is swallowed as best-effort, so the rest of the pipeline
    // (cache invalidation, workflow compilation) still completes successfully.
    StepVerifier.create(sessionService.applyConfig(data)).verifyComplete();

    // Then
    verify(workflowDefinitionStore).remove(sessionId, "w2");
    verify(preparedWorkflowCache).invalidateAll(sessionId);
    verify(controlBus).compileAndCacheWorkflow(eq(sessionId), any());
  }

  @Test
  void testGetSessionConfig_validSessionId_returnsConfigMap() {
    // Given
    final String sessionId = SESSION_ID_1;
    final Map<String, Object> configMap = Map.of("k", "v");
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.just(configMap));

    // When & Then
    StepVerifier.create(sessionService.getSessionConfig(sessionId))
        .expectNextMatches(m -> "v".equals(m.get("k")))
        .verifyComplete();
  }

  @Test
  void testGetSessionWorkflow_validSessionAndWorkflowIds_returnsWorkflowDefinition() {
    // Given
    final String sessionId = "sess-wf";
    final WorkflowDefinition workflow =
        new WorkflowDefinition("test-workflow", DESC, List.of(), List.of());

    when(workflowDefinitionStore.find(sessionId, "w1")).thenReturn(Mono.just(workflow));

    // When & Then
    StepVerifier.create(sessionService.getSessionWorkflow(sessionId, "w1"))
        .expectNext(workflow)
        .verifyComplete();

    verify(workflowDefinitionStore).find(sessionId, "w1");
  }

  @Test
  void testGetSessionIds_multipleSessionsExist_returnsAllSessionIds() {
    // Given
    when(configService.getSessionIds()).thenReturn(Flux.just("s1", "s2"));

    // When & Then
    StepVerifier.create(sessionService.getSessionIds()).expectNext("s1", "s2").verifyComplete();

    verify(configService).getSessionIds();
  }

  @Test
  void applyConfig_withMultipleWorkflowsAndCompilationError_propagatesError() {
    // Given
    final String sessionId = "sess-multi";
    final WorkflowDefinition workflow1 =
        new WorkflowDefinition("workflow-1", "desc1", List.of(), List.of());
    final WorkflowDefinition workflow2 =
        new WorkflowDefinition("workflow-2", "desc2", List.of(), List.of());
    final SessionConfigData data =
        new SessionConfigData(
            sessionId, DESC, INITIATOR, Map.of(), PATH, Map.of("w1", workflow1, "w2", workflow2));

    when(configService.applySessionConfig(configDataCaptor.capture())).thenReturn(Mono.empty());
    when(workflowDefinitionStore.findAll(sessionId)).thenReturn(Mono.just(Map.of()));
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
    final String sessionId = "sess-error";
    final WorkflowDefinition workflow =
        new WorkflowDefinition("test-workflow", DESC, List.of(), List.of());
    final SessionConfigData data =
        new SessionConfigData(sessionId, DESC, INITIATOR, Map.of(), PATH, Map.of("w1", workflow));

    when(configService.applySessionConfig(any())).thenReturn(Mono.empty());
    when(workflowDefinitionStore.findAll(sessionId)).thenReturn(Mono.just(Map.of()));
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
    final String sessionId = "sess-store-error";
    final SessionConfigData data =
        new SessionConfigData(sessionId, DESC, INITIATOR, Map.of(), PATH, Map.of());

    when(configService.applySessionConfig(any()))
        .thenReturn(Mono.error(new RuntimeException("Store failed")));

    // When & Then
    StepVerifier.create(sessionService.applyConfig(data))
        .expectError(RuntimeException.class)
        .verify();
  }

  @Test
  void testGetSessionConfig_storeThrowsError_propagatesError() {
    // Given
    final String sessionId = "sess-config-error";
    when(configService.getAllConfigs(sessionId))
        .thenReturn(Mono.error(new RuntimeException("Config fetch failed")));

    // When & Then
    StepVerifier.create(sessionService.getSessionConfig(sessionId))
        .expectError(RuntimeException.class)
        .verify();
  }

  @Test
  void testGetSessionWorkflow_storeThrowsError_propagatesError() {
    // Given
    final String sessionId = "sess-wf-error";
    when(workflowDefinitionStore.find(sessionId, "w1"))
        .thenReturn(Mono.error(new RuntimeException("Workflow fetch failed")));

    // When & Then
    StepVerifier.create(sessionService.getSessionWorkflow(sessionId, "w1"))
        .expectError(RuntimeException.class)
        .verify();
  }

  @Test
  void testGetSessionIds_noSessionsExist_returnsEmpty() {
    // Given
    when(configService.getSessionIds()).thenReturn(Flux.empty());

    // When & Then
    StepVerifier.create(sessionService.getSessionIds()).verifyComplete();

    verify(configService).getSessionIds();
  }

  @Test
  void testGetSessionIds_storeThrowsError_propagatesError() {
    // Given
    when(configService.getSessionIds())
        .thenReturn(Flux.error(new RuntimeException("Session IDs fetch failed")));

    // When & Then
    StepVerifier.create(sessionService.getSessionIds())
        .expectError(RuntimeException.class)
        .verify();
  }

  @Test
  void testGetSessionConfig_configIsNull_returnsNull() {
    // Given
    final String sessionId = "sess-null-config";
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.empty());

    // When & Then
    StepVerifier.create(sessionService.getSessionConfig(sessionId)).verifyComplete();
  }

  @Test
  void testGetSessionWorkflow_workflowIsNull_returnsNull() {
    // Given
    final String sessionId = "sess-null-wf";
    when(workflowDefinitionStore.find(sessionId, "w1")).thenReturn(Mono.empty());

    // When & Then
    StepVerifier.create(sessionService.getSessionWorkflow(sessionId, "w1")).verifyComplete();
  }

  @Test
  void applyConfig_withSingleWorkflow_compilesSuccessfully() {
    // Given
    final String sessionId = "sess-single";
    final WorkflowDefinition workflow =
        new WorkflowDefinition("single-workflow", DESC, List.of(), List.of());
    final SessionConfigData data =
        new SessionConfigData(sessionId, DESC, INITIATOR, Map.of(), PATH, Map.of("w1", workflow));

    when(configService.applySessionConfig(configDataCaptor.capture())).thenReturn(Mono.empty());
    when(workflowDefinitionStore.findAll(sessionId)).thenReturn(Mono.just(Map.of()));
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
  void testGetSessionConfig_withMultipleConfigValues_returnsAllValues() {
    // Given
    final String sessionId = "sess-multi-config";
    final Map<String, Object> configMap =
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
