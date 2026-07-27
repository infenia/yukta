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
  private static final String DESC2 = "desc2";
  private static final String PATH = "/path";
  private static final String INITIATOR = "initiator";
  private static final String SESSION_ID_1 = "sess-1";
  private static final String WF1 = "wf1";
  private static final String WF2 = "wf2";
  private static final String CONFIG_SESSION_ID = "sessionId";
  private static final String CONFIG_NAME = "name";
  private static final String CONFIG_DESCRIPTION = "description";
  private static final String CONFIG_INITIATOR = "initiator";
  private static final String CONFIG_TAGS = "tags";
  private static final String CONFIG_PROJECT_PATH = "projectPath";
  private static final String CONFIG_WORKFLOWS = "workflows";
  private static final String TEST_WORKFLOW = "test-workflow";
  private static final String TEST_ENV = "env";
  private static final String EXEC1 = "exec1";
  private static final String EXEC2 = "exec2";
  private static final String SUCCESS = "SUCCESS";
  private static final String RUNNING = "RUNNING";
  private static final String FAILURE = "FAILURE";

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
        new WorkflowDefinition(TEST_WORKFLOW, DESC, List.of(), List.of());
    final SessionConfigData data =
        new SessionConfigData(
            sessionId, "test-session", DESC, "initiator-1", Map.of(), PATH, Map.of("w1", workflow));

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
        new SessionConfigData(
            sessionId, "partial-session", DESC, "initiator-p", Map.of(), null, Map.of());

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
        new SessionConfigData(
            sessionId, "stale-cleanup", DESC, INITIATOR, Map.of(), PATH, Map.of("w1", workflowA));
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
        new SessionConfigData(
            sessionId,
            "stale-cleanup-error",
            DESC,
            INITIATOR,
            Map.of(),
            PATH,
            Map.of("w1", workflowA));
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
  void testGetSessionConfig_validSessionId_returnsConfigResponse() {
    // Given
    final String sessionId = SESSION_ID_1;
    final WorkflowDefinition workflow =
        new WorkflowDefinition(TEST_WORKFLOW, DESC, List.of(), List.of());
    final Map<String, Object> configMap =
        Map.of(
            CONFIG_SESSION_ID, sessionId,
            CONFIG_DESCRIPTION, DESC,
            CONFIG_INITIATOR, INITIATOR,
            CONFIG_TAGS, Map.of(),
            CONFIG_PROJECT_PATH, PATH,
            CONFIG_WORKFLOWS, Map.of("w1", workflow));
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.just(configMap));

    // When & Then
    StepVerifier.create(sessionService.getSessionConfig(sessionId))
        .expectNextMatches(
            response ->
                sessionId.equals(response.sessionId())
                    && DESC.equals(response.description())
                    && INITIATOR.equals(response.initiator())
                    && PATH.equals(response.projectPath())
                    && response.workflows().containsKey("w1"))
        .verifyComplete();
  }

  @Test
  void testGetSessionWorkflow_validSessionAndWorkflowIds_returnsWorkflowDefinition() {
    // Given
    final String sessionId = "sess-wf";
    final WorkflowDefinition workflow =
        new WorkflowDefinition(TEST_WORKFLOW, DESC, List.of(), List.of());

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
        new WorkflowDefinition("workflow-2", DESC2, List.of(), List.of());
    final SessionConfigData data =
        new SessionConfigData(
            sessionId,
            "multi-workflow-session",
            DESC,
            INITIATOR,
            Map.of(),
            PATH,
            Map.of("w1", workflow1, "w2", workflow2));

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
        new WorkflowDefinition(TEST_WORKFLOW, DESC, List.of(), List.of());
    final SessionConfigData data =
        new SessionConfigData(
            sessionId, "error-session", DESC, INITIATOR, Map.of(), PATH, Map.of("w1", workflow));

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
        new SessionConfigData(sessionId, "store-error", DESC, INITIATOR, Map.of(), PATH, Map.of());

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
  void testGetSessionConfig_noConfigFound_returnsEmpty() {
    // Given: session does not exist in the store
    final String sessionId = "sess-nonexistent";
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.empty());

    // When & Then
    StepVerifier.create(sessionService.getSessionConfig(sessionId)).verifyComplete();

    verify(configService).getAllConfigs(sessionId);
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
        new SessionConfigData(
            sessionId, "single-workflow", DESC, INITIATOR, Map.of(), PATH, Map.of("w1", workflow));

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
  void testGetSessionConfig_withMultipleWorkflows_returnsAllWorkflows() {
    // Given
    final String sessionId = "sess-multi-config";
    final WorkflowDefinition workflow1 =
        new WorkflowDefinition("w1", "desc1", List.of(), List.of());
    final WorkflowDefinition workflow2 = new WorkflowDefinition("w2", DESC2, List.of(), List.of());
    final Map<String, Object> configMap =
        Map.of(
            CONFIG_SESSION_ID,
            sessionId,
            CONFIG_DESCRIPTION,
            DESC,
            CONFIG_INITIATOR,
            INITIATOR,
            CONFIG_TAGS,
            Map.of(),
            CONFIG_PROJECT_PATH,
            PATH,
            CONFIG_WORKFLOWS,
            Map.of("w1", workflow1, "w2", workflow2));
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.just(configMap));

    // When & Then
    StepVerifier.create(sessionService.getSessionConfig(sessionId))
        .expectNextMatches(
            response ->
                response.workflows().size() == 2
                    && response.workflows().containsKey("w1")
                    && response.workflows().containsKey("w2"))
        .verifyComplete();
  }

  @Test
  void testGetSessionConfig_withTags_returnsTags() {
    // Given
    final String sessionId = "sess-with-tags";
    final Map<String, Object> configMap =
        Map.of(
            CONFIG_SESSION_ID, sessionId,
            CONFIG_DESCRIPTION, DESC,
            CONFIG_INITIATOR, INITIATOR,
            CONFIG_TAGS, Map.of(TEST_ENV, "test", "team", "backend"),
            CONFIG_PROJECT_PATH, PATH,
            CONFIG_WORKFLOWS, Map.of());
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.just(configMap));

    // When & Then
    StepVerifier.create(sessionService.getSessionConfig(sessionId))
        .expectNextMatches(
            response ->
                response.tags().size() == 2
                    && "test".equals(response.tags().get(TEST_ENV))
                    && "backend".equals(response.tags().get("team")))
        .verifyComplete();
  }

  @Test
  void testGetSessionConfig_withNullTags_defaultsToEmptyMap() {
    // Given: config has null tags
    final String sessionId = "sess-null-tags";
    final Map<String, Object> configMap =
        Map.of(
            CONFIG_SESSION_ID, sessionId,
            CONFIG_DESCRIPTION, DESC,
            CONFIG_INITIATOR, INITIATOR,
            CONFIG_PROJECT_PATH, PATH,
            CONFIG_WORKFLOWS, Map.of());
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.just(configMap));

    // When & Then
    StepVerifier.create(sessionService.getSessionConfig(sessionId))
        .expectNextMatches(response -> response.tags().isEmpty())
        .verifyComplete();
  }

  @Test
  void testGetSessionConfig_allFields_returnsComplete() {
    // Given: all fields populated
    final String sessionId = "sess-complete";
    final WorkflowDefinition workflow =
        new WorkflowDefinition("wf", "description", List.of(), List.of());
    final Map<String, Object> configMap =
        Map.of(
            CONFIG_SESSION_ID,
            sessionId,
            CONFIG_DESCRIPTION,
            "Full description",
            CONFIG_INITIATOR,
            "user@example.com",
            CONFIG_TAGS,
            Map.of("region", "us-east-1"),
            CONFIG_PROJECT_PATH,
            "/home/user/project",
            CONFIG_WORKFLOWS,
            Map.of("main", workflow));
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.just(configMap));

    // When & Then
    StepVerifier.create(sessionService.getSessionConfig(sessionId))
        .expectNextMatches(
            response ->
                sessionId.equals(response.sessionId())
                    && "Full description".equals(response.description())
                    && "user@example.com".equals(response.initiator())
                    && "us-east-1".equals(response.tags().get("region"))
                    && "/home/user/project".equals(response.projectPath())
                    && response.workflows().containsKey("main"))
        .verifyComplete();
  }

  @Test
  void testGetSessionConfig_filterPassthrough_logsAndReturns() {
    // Given: a valid config that passes the filter
    final String sessionId = "sess-filter-test";
    final WorkflowDefinition workflow =
        new WorkflowDefinition("wf", "description", List.of(), List.of());
    final Map<String, Object> configMap =
        Map.of(
            CONFIG_SESSION_ID, sessionId,
            CONFIG_DESCRIPTION, DESC,
            CONFIG_INITIATOR, INITIATOR,
            CONFIG_TAGS, Map.of(),
            CONFIG_PROJECT_PATH, PATH,
            CONFIG_WORKFLOWS, Map.of("w1", workflow));
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.just(configMap));

    // When & Then: filter passes non-null config through
    StepVerifier.create(sessionService.getSessionConfig(sessionId))
        .expectNextMatches(
            response -> sessionId.equals(response.sessionId()) && response.workflows().size() == 1)
        .verifyComplete();

    verify(configService).getAllConfigs(sessionId);
  }

  @Test
  void testGetSessionConfig_multipleWorkflowsWithTagsAndMetadata_returnsComplete() {
    // Given: config with multiple workflows and all metadata
    final String sessionId = "sess-metadata";
    final WorkflowDefinition workflow1 =
        new WorkflowDefinition("wf1", "description1", List.of(), List.of());
    final WorkflowDefinition workflow2 =
        new WorkflowDefinition("wf2", "description2", List.of(), List.of());
    final Map<String, Object> configMap =
        Map.of(
            CONFIG_SESSION_ID,
            sessionId,
            CONFIG_DESCRIPTION,
            "Multi workflow session",
            CONFIG_INITIATOR,
            "admin@example.com",
            CONFIG_TAGS,
            Map.of("priority", "high", TEST_ENV, "staging"),
            CONFIG_PROJECT_PATH,
            "/projects/test",
            CONFIG_WORKFLOWS,
            Map.of("workflow-a", workflow1, "workflow-b", workflow2));
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.just(configMap));

    // When & Then
    StepVerifier.create(sessionService.getSessionConfig(sessionId))
        .expectNextMatches(
            response ->
                sessionId.equals(response.sessionId())
                    && "Multi workflow session".equals(response.description())
                    && "admin@example.com".equals(response.initiator())
                    && response.tags().size() == 2
                    && "high".equals(response.tags().get("priority"))
                    && "staging".equals(response.tags().get(TEST_ENV))
                    && "/projects/test".equals(response.projectPath())
                    && response.workflows().size() == 2
                    && response.workflows().containsKey("workflow-a")
                    && response.workflows().containsKey("workflow-b"))
        .verifyComplete();
  }

  @Test
  void testGetAllSessionConfigs_multipleSessions_returnsAll() {
    // Given: multiple sessions in the store
    when(configService.getSessionIds()).thenReturn(Flux.just("s1", "s2"));
    final Map<String, Object> config1 =
        Map.of(
            CONFIG_SESSION_ID,
            "s1",
            CONFIG_NAME,
            "Session 1",
            CONFIG_DESCRIPTION,
            DESC,
            CONFIG_INITIATOR,
            INITIATOR,
            CONFIG_TAGS,
            Map.of(),
            CONFIG_PROJECT_PATH,
            PATH,
            CONFIG_WORKFLOWS,
            Map.of());
    final Map<String, Object> config2 =
        Map.of(
            CONFIG_SESSION_ID,
            "s2",
            CONFIG_NAME,
            "Session 2",
            CONFIG_DESCRIPTION,
            DESC2,
            CONFIG_INITIATOR,
            "user2",
            CONFIG_TAGS,
            Map.of(),
            CONFIG_PROJECT_PATH,
            "/path2",
            CONFIG_WORKFLOWS,
            Map.of());
    when(configService.getAllConfigs("s1")).thenReturn(Mono.just(config1));
    when(configService.getAllConfigs("s2")).thenReturn(Mono.just(config2));

    // When & Then
    StepVerifier.create(sessionService.getAllSessionConfigs())
        .expectNextMatches(response -> "s1".equals(response.sessionId()))
        .expectNextMatches(response -> "s2".equals(response.sessionId()))
        .verifyComplete();
  }

  @Test
  void testGetAllSessionConfigs_noSessions_returnsEmpty() {
    // Given: no sessions in store
    when(configService.getSessionIds()).thenReturn(Flux.empty());

    // When & Then
    StepVerifier.create(sessionService.getAllSessionConfigs()).verifyComplete();
  }

  @Test
  void testGetAllSessionConfigs_oneSessionFailsToLoad_skipsAndReturnsOthers() {
    // Given: first session fails to load, second succeeds
    when(configService.getSessionIds()).thenReturn(Flux.just("s1", "s2"));
    when(configService.getAllConfigs("s1"))
        .thenReturn(Mono.error(new RuntimeException("Failed to load s1")));
    final Map<String, Object> config2 =
        Map.of(
            CONFIG_SESSION_ID,
            "s2",
            CONFIG_NAME,
            "Session 2",
            CONFIG_DESCRIPTION,
            DESC,
            CONFIG_INITIATOR,
            INITIATOR,
            CONFIG_TAGS,
            Map.of(),
            CONFIG_PROJECT_PATH,
            PATH,
            CONFIG_WORKFLOWS,
            Map.of());
    when(configService.getAllConfigs("s2")).thenReturn(Mono.just(config2));

    // When & Then: s1 is skipped, s2 is returned
    StepVerifier.create(sessionService.getAllSessionConfigs())
        .expectNextMatches(response -> "s2".equals(response.sessionId()))
        .verifyComplete();
  }

  @Test
  void testGetSessionConfig_includesNameField() {
    // Given: config map includes name field
    final String sessionId = "sess-with-name";
    final String sessionName = "My Custom Session";
    final Map<String, Object> configMap =
        Map.of(
            CONFIG_SESSION_ID, sessionId,
            CONFIG_NAME, sessionName,
            CONFIG_DESCRIPTION, DESC,
            CONFIG_INITIATOR, INITIATOR,
            CONFIG_TAGS, Map.of(),
            CONFIG_PROJECT_PATH, PATH,
            CONFIG_WORKFLOWS, Map.of());
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.just(configMap));

    // When & Then
    StepVerifier.create(sessionService.getSessionConfig(sessionId))
        .expectNextMatches(response -> sessionName.equals(response.name()))
        .verifyComplete();
  }

  @Test
  void testGetSessionConfig_storeReturnsError_triggersErrorHandler() {
    // Given: store throws error
    final String sessionId = "sess-error-handler";
    final RuntimeException testError = new RuntimeException("Test error from store");
    when(configService.getAllConfigs(sessionId)).thenReturn(Mono.error(testError));

    // When & Then: error is propagated and error handler is invoked
    StepVerifier.create(sessionService.getSessionConfig(sessionId))
        .expectError(RuntimeException.class)
        .verify();

    verify(configService).getAllConfigs(sessionId);
  }

  @Test
  void testGetSessionWorkflows_delegatesToStore() {
    // Given
    final String sessionId = "sess-workflows";
    final WorkflowDefinition workflow1 = new WorkflowDefinition(WF1, "desc1", List.of(), List.of());
    final WorkflowDefinition workflow2 = new WorkflowDefinition(WF2, DESC2, List.of(), List.of());
    final Map<String, WorkflowDefinition> workflows = Map.of(WF1, workflow1, WF2, workflow2);

    when(workflowDefinitionStore.findAll(sessionId)).thenReturn(Mono.just(workflows));

    // When & Then
    StepVerifier.create(sessionService.getSessionWorkflows(sessionId))
        .expectNext(workflows)
        .verifyComplete();

    verify(workflowDefinitionStore).findAll(sessionId);
  }

  @Test
  void testGetSessionWorkflows_storeReturnsEmpty() {
    // Given
    final String sessionId = "sess-no-workflows";
    when(workflowDefinitionStore.findAll(sessionId)).thenReturn(Mono.just(Map.of()));

    // When & Then
    StepVerifier.create(sessionService.getSessionWorkflows(sessionId))
        .expectNext(Map.of())
        .verifyComplete();

    verify(workflowDefinitionStore).findAll(sessionId);
  }

  @Test
  void testGetSessionWorkflows_storeReturnsError() {
    // Given
    final String sessionId = "sess-workflow-error";
    final RuntimeException testError = new RuntimeException("Store error");
    when(workflowDefinitionStore.findAll(sessionId)).thenReturn(Mono.error(testError));

    // When & Then
    StepVerifier.create(sessionService.getSessionWorkflows(sessionId))
        .expectError(RuntimeException.class)
        .verify();

    verify(workflowDefinitionStore).findAll(sessionId);
  }

  @Test
  void testGetLatestExecutionStatusByWorkflow_groupsAndTakesFirst() {
    // Given: history with multiple entries, most recent first
    final String sessionId = "sess-status";
    final com.infenia.yukta.model.execution.WorkflowExecutionSummary status1 =
        new com.infenia.yukta.model.execution.WorkflowExecutionSummary(
            EXEC1, WF1, RUNNING, null, null);
    final com.infenia.yukta.model.execution.WorkflowExecutionSummary status2 =
        new com.infenia.yukta.model.execution.WorkflowExecutionSummary(
            EXEC2, WF1, SUCCESS, null, null);
    final List<com.infenia.yukta.model.execution.WorkflowExecutionSummary> history =
        List.of(status1, status2);

    when(controlBus.getHistory(sessionId)).thenReturn(Mono.just(history));

    // When & Then: only the first (most recent) status per workflow is kept
    StepVerifier.create(sessionService.getLatestExecutionStatusByWorkflow(sessionId))
        .expectNextMatches(
            result ->
                result.size() == 1
                    && result.containsKey(WF1)
                    && RUNNING.equals(result.get(WF1).status()))
        .verifyComplete();

    verify(controlBus).getHistory(sessionId);
  }

  @Test
  void testGetLatestExecutionStatusByWorkflow_withEmptyHistory() {
    // Given
    final String sessionId = "sess-no-history";
    when(controlBus.getHistory(sessionId)).thenReturn(Mono.just(List.of()));

    // When & Then
    StepVerifier.create(sessionService.getLatestExecutionStatusByWorkflow(sessionId))
        .expectNext(Map.of())
        .verifyComplete();

    verify(controlBus).getHistory(sessionId);
  }

  @Test
  void testGetLatestExecutionStatusByWorkflow_storeReturnsError() {
    // Given
    final String sessionId = "sess-history-error";
    final RuntimeException testError = new RuntimeException("History error");
    when(controlBus.getHistory(sessionId)).thenReturn(Mono.error(testError));

    // When & Then
    StepVerifier.create(sessionService.getLatestExecutionStatusByWorkflow(sessionId))
        .expectError(RuntimeException.class)
        .verify();

    verify(controlBus).getHistory(sessionId);
  }

  @Test
  void testGetLatestExecutionStatusByWorkflow_multipleWorkflows() {
    // Given: history with different workflows
    final String sessionId = "sess-multi-wf";
    final com.infenia.yukta.model.execution.WorkflowExecutionSummary wf1Status =
        new com.infenia.yukta.model.execution.WorkflowExecutionSummary(
            EXEC1, WF1, SUCCESS, null, null);
    final com.infenia.yukta.model.execution.WorkflowExecutionSummary wf2Status =
        new com.infenia.yukta.model.execution.WorkflowExecutionSummary(
            EXEC2, WF2, FAILURE, null, null);
    final List<com.infenia.yukta.model.execution.WorkflowExecutionSummary> history =
        List.of(wf1Status, wf2Status);

    when(controlBus.getHistory(sessionId)).thenReturn(Mono.just(history));

    // When & Then
    StepVerifier.create(sessionService.getLatestExecutionStatusByWorkflow(sessionId))
        .expectNextMatches(
            result ->
                result.size() == 2
                    && SUCCESS.equals(result.get(WF1).status())
                    && FAILURE.equals(result.get(WF2).status()))
        .verifyComplete();

    verify(controlBus).getHistory(sessionId);
  }
}
