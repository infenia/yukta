// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.session.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.session.SessionSnapshot;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoSettings;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Test suite for InMemorySessionConfigStore. */
@MockitoSettings
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
class InMemorySessionConfigStoreTest {

  /** Placeholder string value used in test cases. */
  private static final String OTHER = "Other";

  /** Initiator field name. */
  private static final String INITIATOR = "initiator";

  /** Description field name. */
  private static final String DESCRIPTION = "description";

  /** Short description field name. */
  private static final String DESC = "desc";

  /** Environment field name. */
  private static final String ENV = "env";

  /** Workflow identifier. */
  private static final String WORKFLOW = "wf1";

  /** Path value. */
  private static final String PATH = "/path";

  /** Empty session identifier. */
  private static final String EMPTY_SESSION_ID = "s-empty";

  /** Mock store for workflow definitions. */
  @Mock private WorkflowDefinitionStore workflowDefinitionStore;

  /** Service instance under test. */
  private InMemorySessionConfigStore configService;

  @BeforeEach
  void setUp() {
    configService = new InMemorySessionConfigStore(workflowDefinitionStore);
  }

  @Test
  void testApplySessionConfig() {
    final String sessionId = "sess-apply";
    final WorkflowDefinition workflow =
        new WorkflowDefinition(
            "test-workflow",
            DESC,
            List.of(new WorkflowDefinition.Node("n1", "gradle", Map.of())),
            List.of());
    final SessionConfigData data =
        new SessionConfigData(
            sessionId,
            "apply-session",
            "full desc",
            "initiator-x",
            Map.of(ENV, "prod"),
            "/full/path",
            Map.of("w1", workflow));

    when(workflowDefinitionStore.save(sessionId, workflow)).thenReturn(Mono.empty());

    StepVerifier.create(configService.applySessionConfig(data)).verifyComplete();

    StepVerifier.create(configService.getProjectPath(sessionId))
        .expectNext("/full/path")
        .verifyComplete();
    StepVerifier.create(configService.getDescription(sessionId))
        .expectNext("full desc")
        .verifyComplete();
    StepVerifier.create(configService.getInitiator(sessionId))
        .expectNext("initiator-x")
        .verifyComplete();
    StepVerifier.create(configService.getTags(sessionId))
        .expectNext(Map.of(ENV, "prod"))
        .verifyComplete();

    verify(workflowDefinitionStore).save(sessionId, workflow);
  }

  @Test
  void applySessionConfigDelegatesWorkflowsToStore() {
    final WorkflowDefinition workflow =
        new WorkflowDefinition(
            WORKFLOW, DESC, List.of(new WorkflowDefinition.Node("n1", "t", Map.of())), List.of());
    final SessionConfigData data =
        new SessionConfigData(
            "s1",
            "workflow-session",
            "some description",
            "init",
            Map.of(),
            PATH,
            Map.of(WORKFLOW, workflow));

    when(workflowDefinitionStore.save("s1", workflow)).thenReturn(Mono.empty());

    StepVerifier.create(configService.applySessionConfig(data)).verifyComplete();

    verify(workflowDefinitionStore).save("s1", workflow);
  }

  @Test
  void applySessionConfigWithEmptyWorkflows() {
    final SessionConfigData data =
        new SessionConfigData(
            EMPTY_SESSION_ID,
            "empty-session",
            "description",
            "initiator",
            Map.of("key", "value"),
            PATH,
            Map.of());

    StepVerifier.create(configService.applySessionConfig(data)).verifyComplete();

    StepVerifier.create(configService.getProjectPath(EMPTY_SESSION_ID))
        .expectNext(PATH)
        .verifyComplete();
    StepVerifier.create(configService.getDescription(EMPTY_SESSION_ID))
        .expectNext("description")
        .verifyComplete();
    StepVerifier.create(configService.getInitiator(EMPTY_SESSION_ID))
        .expectNext("initiator")
        .verifyComplete();
  }

  @Test
  void shouldReturnEmptyWhenSessionDoesNotExist() {
    StepVerifier.create(configService.getAllConfigs("non-existent-session")).verifyComplete();
  }

  @Test
  void applySessionConfigWithMultipleWorkflows() {
    final String sessionId = "sess-multi";
    final WorkflowDefinition workflow1 =
        new WorkflowDefinition(
            "wf1", "desc1", List.of(new WorkflowDefinition.Node("n1", "t1", Map.of())), List.of());
    final WorkflowDefinition workflow2 =
        new WorkflowDefinition(
            "wf2", "desc2", List.of(new WorkflowDefinition.Node("n2", "t2", Map.of())), List.of());
    final SessionConfigData data =
        new SessionConfigData(
            sessionId,
            "multi-workflow",
            "description",
            "initiator",
            Map.of(ENV, "test"),
            "/multi/path",
            Map.of("wf1", workflow1, "wf2", workflow2));

    when(workflowDefinitionStore.save(sessionId, workflow1)).thenReturn(Mono.empty());
    when(workflowDefinitionStore.save(sessionId, workflow2)).thenReturn(Mono.empty());

    StepVerifier.create(configService.applySessionConfig(data)).verifyComplete();

    verify(workflowDefinitionStore).save(sessionId, workflow1);
    verify(workflowDefinitionStore).save(sessionId, workflow2);
    StepVerifier.create(configService.getProjectPath(sessionId))
        .expectNext("/multi/path")
        .verifyComplete();
  }

  @Test
  void removeSession_forgetsTheSessionAndIsQuietForAnUnknownOne() {
    final SessionConfigData data =
        new SessionConfigData("gone", "n", "d", "i", Map.of(), "/p", Map.of());
    StepVerifier.create(configService.applySessionConfig(data)).verifyComplete();
    assertThat(configService.getSessionIds().collectList().block()).contains("gone");

    StepVerifier.create(configService.removeSession("gone")).verifyComplete();
    StepVerifier.create(configService.removeSession("never-was")).verifyComplete();

    assertThat(configService.getSessionIds().collectList().block()).doesNotContain("gone");
  }

  private static SessionConfigData data(final String id, final String initiator) {
    return new SessionConfigData(
        id, "n", "Sample", initiator, Map.of("k", "v"), "/meta/path", Map.of());
  }

  @Test
  void anAppliedSessionCanBeReadBackValueByValue() {
    when(workflowDefinitionStore.findAll("s1")).thenReturn(Mono.just(Map.of()));
    configService.applySessionConfig(data("s1", "Jules")).block();

    assertThat(configService.getInitiator("s1").block()).isEqualTo("Jules");
    assertThat(configService.getDescription("s1").block()).isEqualTo("Sample");
    assertThat(configService.getProjectPath("s1").block()).isEqualTo("/meta/path");
    assertThat(configService.getTags("s1").block()).isEqualTo(Map.of("k", "v"));
    assertThat(configService.getInitiatedTime("s1").block()).isNotBlank();
    final SessionSnapshot all = configService.getAllConfigs("s1").block();
    assertThat(all.name()).isEqualTo("n");
    assertThat(all.workflows()).isEmpty();
  }

  @Test
  void anUnknownSessionReadsAsEmpty() {
    assertThat(configService.getInitiator("none").block()).isEmpty();
    assertThat(configService.getDescription("none").block()).isEmpty();
    assertThat(configService.getProjectPath("none").block()).isEmpty();
    assertThat(configService.getInitiatedTime("none").block()).isEmpty();
    assertThat(configService.getTags("none").block()).isEmpty();
    assertThat(configService.getAllConfigs("none").block()).isNull();
  }

  @Test
  void setInitiator_createsTheSessionWhenItIsNew() {
    configService.setInitiator("new", "alice").block();

    assertThat(configService.getInitiator("new").block()).isEqualTo("alice");
    assertThat(configService.getSessionIds().collectList().block()).contains("new");
  }

  @Test
  void setInitiator_fillsAnEmptyInitiatorAndKeepsAnExistingOne() {
    configService.applySessionConfig(data("s1", "")).block();
    configService.setInitiator("s1", "first").block();
    configService.setInitiator("s1", "second").block();

    assertThat(configService.getInitiator("s1").block()).isEqualTo("first");
    assertThat(configService.getDescription("s1").block()).isEqualTo("Sample");
  }

  @Test
  void setInitiator_null_changesNothing() {
    StepVerifier.create(configService.setInitiator("s1", null)).verifyComplete();

    assertThat(configService.getSessionIds().collectList().block()).isEmpty();
  }

  @Test
  void logInitialization_runs() {
    configService.logInitialization();

    assertThat(configService.getSessionIds().collectList().block()).isEmpty();
  }
}
