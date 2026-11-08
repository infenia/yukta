// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.model.session.SessionConfigData;
import com.infenia.yukta.model.supervisor.SupervisorRef;
import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.SupervisorSource;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.session.store.SessionConfigStore;
import com.infenia.yukta.service.supervisor.SettingsValidationException.Issue;
import com.infenia.yukta.service.workflow.store.InMemoryWorkflowDefinitionStore;
import com.infenia.yukta.testing.Fixtures;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Declared supervisors are attached when a definition is saved and follow it afterwards. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.CouplingBetweenObjects",
  "PMD.UseConcurrentHashMap",
  "PMD.AvoidFieldNameMatchingMethodName",
  "PMD.UnitTestShouldIncludeAssert"
})
class DefinitionSupervisorsTest {

  private static final String SESSION = "s1";

  private final SupervisorStore store = new InMemorySupervisorStore();

  private final SupervisorHost host = mock(SupervisorHost.class);

  private final InMemoryWorkflowDefinitionStore definitions = new InMemoryWorkflowDefinitionStore();

  private final SessionConfigStore sessions = mock(SessionConfigStore.class);

  private final SupervisorFactory factory =
      new SupervisorFactory() {
        @Override
        public List<ConfigField> getConfigSchema() {
          return List.of();
        }

        @Override
        public String type() {
          return "t";
        }

        @Override
        public ExecutionSupervisor create(final Supervisor supervisor) {
          return mock(ExecutionSupervisor.class);
        }
      };

  private final SupervisorService service =
      new SupervisorService(store, List.of(factory), host, new InMemoryExecutionLogStore());

  private final DefinitionSupervisors declared =
      new DefinitionSupervisors(service, store, host, definitions, sessions);

  @BeforeEach
  void register() {
    declared.register();
    when(sessions.getSessionIds()).thenReturn(Flux.just(SESSION));
  }

  private void supervisor(final String id) {
    StepVerifier.create(service.saveSupervisor(new Supervisor(id, "t", Map.of(), false)))
        .expectNextCount(1)
        .verifyComplete();
  }

  private static WorkflowDefinition workflow(final String id, final SupervisorRef... refs) {
    return Fixtures.workflow(
        id,
        "d",
        List.of(
            Fixtures.node("build.compile", "T", Map.of()),
            Fixtures.node("build.test", "T", Map.of()),
            Fixtures.node("deploy", "T", Map.of())),
        List.of(),
        List.of(refs));
  }

  private static SessionConfigData session(
      final List<SupervisorRef> refs, final WorkflowDefinition... workflows) {
    final Map<String, WorkflowDefinition> byId = new java.util.LinkedHashMap<>();
    for (final WorkflowDefinition w : workflows) {
      byId.put(w.workflowId(), w);
    }
    return new SessionConfigData(SESSION, "n", "d", "i", Map.of(), ".", byId, refs);
  }

  private void save(final SessionConfigData data) {
    data.workflows().values().forEach(w -> definitions.save(SESSION, w).block());
    StepVerifier.create(declared.apply(data)).verifyComplete();
  }

  private List<SupervisorAttachment> attachments() {
    return service.attachments().collectList().block();
  }

  private static SupervisorRef ref(final String id, final String... nodes) {
    return new SupervisorRef(id, nodes.length == 0 ? null : List.of(nodes), null);
  }

  private void manual(final String id, final String supervisorId, final SupervisorScope scope) {
    StepVerifier.create(service.attach(new SupervisorAttachment(id, supervisorId, scope, true)))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void checkRefusesADeclarationThatTakesWhatAnotherSupervisorOwns() {
    supervisor("a");
    supervisor("b");
    manual("m1", "b", new SupervisorScope.Section("w1", SESSION, java.util.Set.of("build.*")));

    StepVerifier.create(declared.check(session(List.of(), workflow("w1", ref("a")))))
        .assertNext(
            issues ->
                assertThat(issues)
                    .singleElement()
                    .satisfies(
                        issue -> {
                          assertThat(issue.field())
                              .isEqualTo("workflows.w1.supervisors[0].supervisor");
                          assertThat(issue.message()).contains("workflow w1", "owned by b");
                        }))
        .verifyComplete();
  }

  @Test
  void checkRefusesTwoDeclarationsOfDifferentSupervisorsOnTheSameNodes() {
    supervisor("a");
    supervisor("b");

    StepVerifier.create(
            declared.check(
                session(List.of(), workflow("w1", ref("a", "build.*"), ref("b", "build.test")))))
        .assertNext(
            issues ->
                assertThat(issues)
                    .singleElement()
                    .satisfies(
                        issue -> {
                          assertThat(issue.field())
                              .isEqualTo("workflows.w1.supervisors[1].supervisor");
                          assertThat(issue.message()).contains("owned by a");
                        }))
        .verifyComplete();
  }

  @Test
  void checkRefusesANewVersionThatMakesTwoManualAttachmentsTakeTheSameNode() {
    supervisor("a");
    supervisor("b");
    manual("m1", "a", new SupervisorScope.Section("w1", SESSION, java.util.Set.of("group:ci")));
    manual("m2", "b", new SupervisorScope.Section("w1", SESSION, java.util.Set.of("deploy")));
    final WorkflowDefinition regrouped =
        Fixtures.workflow(
            "w1",
            "d",
            List.of(
                new WorkflowDefinition.Node("build.compile", "T", Map.of(), "ci", null),
                new WorkflowDefinition.Node("deploy", "T", Map.of(), "ci", null)),
            List.of(),
            List.of());

    StepVerifier.create(declared.check(session(List.of(), regrouped)))
        .assertNext(
            issues ->
                assertThat(issues)
                    .singleElement()
                    .satisfies(
                        issue -> {
                          assertThat(issue.field()).isEqualTo("workflows.w1.nodes");
                          assertThat(issue.message()).contains("node deploy", "owned by");
                        }))
        .verifyComplete();
  }

  @Test
  void declaredSupervisorsAreAttachedWithTheirScopesAndSource() {
    supervisor("guard");
    save(session(List.of(ref("guard")), workflow("w1", ref("guard"), ref("guard", "build.*"))));

    assertThat(attachments())
        .extracting(a -> a.scope().key(), SupervisorAttachment::source)
        .containsExactlyInAnyOrder(
            org.assertj.core.groups.Tuple.tuple("session:s1", SupervisorSource.DEFINITION),
            org.assertj.core.groups.Tuple.tuple("workflow:s1/w1", SupervisorSource.DEFINITION),
            org.assertj.core.groups.Tuple.tuple(
                "workflow:s1/w1//build.*", SupervisorSource.DEFINITION));
    assertThat(attachments())
        .allSatisfy(
            a -> {
              assertThat(a.id()).matches("def-[0-9a-f]{16}");
              assertThat(a.originSessionId()).isEqualTo(SESSION);
            });
    assertThat(attachments().stream().filter(a -> a.originWorkflowId() != null)).hasSize(2);
  }

  @Test
  void idsDoNotDependOnTheOrderOfTheDeclarations() {
    final String one = DefinitionSupervisors.attachmentId(SESSION, "w1", ref("g", "b", "a"));
    final String same = DefinitionSupervisors.attachmentId(SESSION, "w1", ref("g", "a", "b"));

    assertThat(one).isEqualTo(same);
    assertThat(DefinitionSupervisors.attachmentId(SESSION, "w1", ref("g"))).isNotEqualTo(one);
    assertThat(DefinitionSupervisors.attachmentId(SESSION, "w2", ref("g", "a", "b")))
        .isNotEqualTo(one);
    assertThat(DefinitionSupervisors.attachmentId("s2", "w1", ref("g", "a", "b")))
        .isNotEqualTo(one);
  }

  @Test
  void reorderingLeavesTheAttachmentsAndTheirRunsAlone() {
    supervisor("a");
    supervisor("b");
    save(session(List.of(), workflow("w1", ref("a"), ref("b"))));
    final List<String> before =
        attachments().stream().map(SupervisorAttachment::id).sorted().toList();

    save(session(List.of(), workflow("w1", ref("b"), ref("a"))));

    assertThat(attachments().stream().map(SupervisorAttachment::id).sorted().toList())
        .isEqualTo(before);
    org.mockito.Mockito.verify(host, org.mockito.Mockito.never())
        .detach(org.mockito.ArgumentMatchers.any());
  }

  @Test
  void removedDeclarationsAreDetachedAndManualAttachmentsAreKept() {
    supervisor("guard");
    StepVerifier.create(
            service.attach(
                new SupervisorAttachment("manual", "guard", new SupervisorScope.Global(), true)))
        .expectNextCount(1)
        .verifyComplete();
    save(session(List.of(ref("guard")), workflow("w1", ref("guard"))));
    assertThat(attachments()).hasSize(3);

    save(session(List.of(), workflow("w1")));

    assertThat(attachments()).extracting(SupervisorAttachment::id).containsExactly("manual");
  }

  @Test
  void togglingEnabledUpdatesTheAttachmentInPlace() {
    supervisor("guard");
    save(session(List.of(), workflow("w1", ref("guard"))));
    final String id = attachments().getFirst().id();

    save(session(List.of(), workflow("w1", new SupervisorRef("guard", null, false))));

    assertThat(attachments())
        .singleElement()
        .satisfies(
            a -> {
              assertThat(a.id()).isEqualTo(id);
              assertThat(a.enabled()).isFalse();
            });
  }

  @Test
  void declarationThatCannotBeAttachedDoesNotFailAndTheRestStillAttach() {
    supervisor("guard");
    save(session(List.of(), workflow("w1", ref("missing"), ref("guard"))));

    assertThat(attachments())
        .extracting(SupervisorAttachment::supervisorId)
        .containsExactly("guard");
  }

  @Test
  void sectionThatIsNotValidIsSkipped() {
    supervisor("guard");
    save(session(List.of(), workflow("w1", ref("guard", "a,b"), ref("guard"))));

    assertThat(attachments()).hasSize(1);
  }

  @Test
  void reconcileAllCleansUpAfterSessionThatIsGone() {
    supervisor("guard");
    save(session(List.of(ref("guard"))));
    assertThat(attachments()).hasSize(1);
    definitions.removeAll(SESSION).block();
    store.saveSessionRefs(SESSION, List.of()).block();
    when(sessions.getSessionIds()).thenReturn(Flux.empty());

    StepVerifier.create(declared.reconcileAll()).verifyComplete();

    assertThat(attachments()).isEmpty();
  }

  @Test
  void forgetRemovesWhatTheSessionDeclared() {
    supervisor("guard");
    save(session(List.of(ref("guard")), workflow("w1", ref("guard"))));
    definitions.removeAll(SESSION).block();

    StepVerifier.create(declared.forget(SESSION)).verifyComplete();

    assertThat(attachments()).isEmpty();
    StepVerifier.create(declared.sessionRefs(SESSION))
        .assertNext(refs -> assertThat(refs).isEmpty())
        .verifyComplete();
  }

  @Test
  void concurrentReconcilesOfOneSessionEndWithTheLatestDefinitions() {
    supervisor("guard");
    definitions.save(SESSION, workflow("w1", ref("guard"))).block();

    StepVerifier.create(Mono.when(declared.reconcile(SESSION), declared.reconcile(SESSION)))
        .verifyComplete();

    assertThat(attachments()).hasSize(1);
  }

  @Test
  void checkReportsMissingServerOwnedMisusedAndDuplicateDeclarations() {
    supervisor("guard");
    when(host.isBuiltIn("rules")).thenReturn(true);
    final SessionConfigData data =
        session(
            List.of(ref("missing"), ref("rules"), ref("guard", "x"), ref("guard"), ref("guard")),
            workflow(
                "w1",
                ref("guard", "nothing.*"),
                ref("guard", "a,b"),
                ref("guard", "deploy"),
                ref("guard", "deploy")));

    final List<Issue> issues = declared.check(data).block();

    assertThat(issues)
        .extracting(Issue::field)
        .containsExactlyInAnyOrder(
            "supervisors[0].supervisor",
            "supervisors[1].supervisor",
            "supervisors[2].nodes",
            "supervisors[4].supervisor",
            "workflows.w1.supervisors[0].nodes",
            "workflows.w1.supervisors[1].nodes",
            "workflows.w1.supervisors[3].supervisor");
    assertThat(issues.stream().filter(i -> "supervisors[1].supervisor".equals(i.field())))
        .singleElement()
        .satisfies(i -> assertThat(i.message()).contains("server"));
  }

  @Test
  void requireRefusesWorkflowThatNamesMissingSupervisorAndAcceptsGoodOne() {
    supervisor("guard");

    declared.require(workflow("w1", ref("guard"))).block();
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> declared.require(workflow("w1", ref("nope"))).block())
        .isInstanceOf(SettingsValidationException.class)
        .hasMessageContaining("supervisors[0].supervisor");
  }

  @Test
  void cleanSessionAndDraftWorkflowHaveNoProblems() {
    supervisor("guard");

    assertThat(
            declared
                .check(session(List.of(ref("guard")), workflow("w1", ref("guard", "build.*"))))
                .block())
        .isEmpty();
    assertThat(declared.check(workflow("w1", ref("nope"))).block())
        .extracting(Issue::field)
        .containsExactly("supervisors[0].supervisor");
  }

  @Test
  void deletingSupervisorThatDefinitionDeclaresNamesWhoDeclaresIt() {
    supervisor("guard");
    definitions.save(SESSION, workflow("w1", ref("guard"))).block();
    store.saveSessionRefs(SESSION, List.of(ref("guard"))).block();

    StepVerifier.create(service.deleteSupervisor("guard"))
        .expectErrorSatisfies(
            error ->
                assertThat(error)
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("session s1")
                    .hasMessageContaining("workflow s1/w1"))
        .verify();
  }

  @Test
  void supervisorNoDefinitionDeclaresCanBeDeleted() {
    supervisor("guard");

    StepVerifier.create(service.deleteSupervisor("guard")).verifyComplete();
  }

  @Test
  void deletingSessionRemovesItsDefinitionAttachmentsThroughTheirOrigin() {
    supervisor("guard");
    save(session(List.of(), workflow("w1", ref("guard"))));

    StepVerifier.create(service.removeForSession(SESSION)).verifyComplete();

    assertThat(attachments()).isEmpty();
  }

  @Test
  void onReadyReconcilesEverySessionOnSingleServer() {
    supervisor("guard");
    definitions.save(SESSION, workflow("w1", ref("guard"))).block();

    declared.onReady();

    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(attachments()).hasSize(1));
  }

  @Test
  void standbyServerReconcilesNothingUntilItTakesOver() {
    supervisor("guard");
    definitions.save(SESSION, workflow("w1", ref("guard"))).block();
    final com.infenia.yukta.service.leader.LeaderElection election =
        mock(com.infenia.yukta.service.leader.LeaderElection.class);
    when(election.isLeader()).thenReturn(false);
    declared.setLeaderElection(election);

    declared.onReady();

    assertThat(attachments()).isEmpty();
    final org.mockito.ArgumentCaptor<Runnable> takeover =
        org.mockito.ArgumentCaptor.forClass(Runnable.class);
    org.mockito.Mockito.verify(election).onTakeover(takeover.capture());
    when(election.isLeader()).thenReturn(true);
    takeover.getValue().run();
    org.awaitility.Awaitility.await()
        .atMost(java.time.Duration.ofSeconds(5))
        .untilAsserted(() -> assertThat(attachments()).hasSize(1));
  }

  @Test
  @ExpectedErrorLog("ERROR com.infenia.yukta.service.supervisor.DefinitionSupervisors - ")
  void storeThatCannotBeReadIsLoggedAndNeverFailsTheCaller() {
    when(sessions.getSessionIds()).thenReturn(Flux.error(new IllegalStateException("no database")));

    declared.onReady();
    StepVerifier.create(declared.reconcileAll()).expectError(IllegalStateException.class).verify();
    final SupervisorStore broken = mock(SupervisorStore.class);
    when(broken.sessionRefs(SESSION)).thenReturn(Mono.error(new IllegalStateException("down")));
    final SupervisorService brokenService =
        new SupervisorService(broken, List.of(factory), host, new InMemoryExecutionLogStore());
    final DefinitionSupervisors failing =
        new DefinitionSupervisors(brokenService, broken, host, definitions, sessions);

    StepVerifier.create(failing.reconcile(SESSION)).verifyComplete();
  }

  @Test
  void detachThatFindsNothingToRemoveIsLoggedAndTheRestGoesOn() {
    final SupervisorStore stubborn =
        new InMemorySupervisorStore() {
          @Override
          public Mono<Boolean> deleteAttachment(final String id) {
            return Mono.just(false);
          }
        };
    final SupervisorService stubbornService =
        new SupervisorService(stubborn, List.of(factory), host, new InMemoryExecutionLogStore());
    final DefinitionSupervisors stubbornDeclared =
        new DefinitionSupervisors(stubbornService, stubborn, host, definitions, sessions);
    stubbornService.saveSupervisor(new Supervisor("guard", "t", Map.of(), false)).block();
    stubborn
        .saveAttachment(
            new SupervisorAttachment(
                "def-0000000000000000",
                "guard",
                new SupervisorScope.Session(SESSION),
                true,
                SupervisorSource.DEFINITION,
                SESSION,
                null))
        .block();

    StepVerifier.create(stubbornDeclared.reconcile(SESSION)).verifyComplete();
  }

  @Test
  void reconcileLeavesTheDefinitionAttachmentsOfOtherSessionsAlone() {
    supervisor("guard");
    store
        .saveAttachment(
            new SupervisorAttachment(
                "def-1111111111111111",
                "guard",
                new SupervisorScope.Session("other"),
                true,
                SupervisorSource.DEFINITION,
                "other",
                null))
        .block();

    save(session(List.of(), workflow("w1", ref("guard"))));

    assertThat(attachments())
        .extracting(SupervisorAttachment::originSessionId)
        .contains("other", SESSION);
  }

  @Test
  void reconcileAllIgnoresManualAttachmentsWhenLookingForOrphans() {
    supervisor("guard");
    StepVerifier.create(
            service.attach(
                new SupervisorAttachment("manual", "guard", new SupervisorScope.Global(), true)))
        .expectNextCount(1)
        .verifyComplete();
    when(sessions.getSessionIds()).thenReturn(Flux.empty());

    StepVerifier.create(declared.reconcileAll()).verifyComplete();

    assertThat(attachments()).extracting(SupervisorAttachment::id).containsExactly("manual");
  }

  @Test
  void usageDoesNotCountSessionsThatDeclareOtherSupervisors() {
    supervisor("guard");
    store.saveSessionRefs(SESSION, List.of(ref("other"))).block();
    definitions.save(SESSION, workflow("w1", ref("other"))).block();

    assertThat(declared.declaredBy("guard").block()).isEmpty();
  }

  @Test
  void overlappingReconcilesOfOneSessionRunOneAfterTheOther() {
    supervisor("guard");
    final WorkflowDefinitionStoreSlow slow = new WorkflowDefinitionStoreSlow();
    slow.save(SESSION, workflow("w1", ref("guard"))).block();
    final DefinitionSupervisors slowed =
        new DefinitionSupervisors(service, store, host, slow, sessions);

    StepVerifier.create(Mono.when(slowed.reconcile(SESSION), slowed.reconcile(SESSION)))
        .verifyComplete();

    assertThat(attachments()).hasSize(1);
  }

  @Test
  void hashOfAnUnknownAlgorithmIsAnError() {
    org.assertj.core.api.Assertions.assertThatThrownBy(
            () -> DefinitionSupervisors.hash("NOPE", "x"))
        .isInstanceOf(IllegalStateException.class);
    assertThat(DefinitionSupervisors.hash("SHA-256", "x")).hasSize(64);
  }

  @Test
  void removingSessionKeepsTheAttachmentsOfOtherSessions() {
    supervisor("guard");
    save(session(List.of(ref("guard"))));
    StepVerifier.create(
            service.attach(
                new SupervisorAttachment(
                    "other", "guard", new SupervisorScope.Session("elsewhere"), true)))
        .expectNextCount(1)
        .verifyComplete();

    StepVerifier.create(service.removeForSession(SESSION)).verifyComplete();

    assertThat(attachments()).extracting(SupervisorAttachment::id).containsExactly("other");
  }

  @Test
  void removingSessionTakesDefinitionAttachmentsWhoseScopeNamesNoSession() {
    supervisor("guard");
    StepVerifier.create(
            service.attach(
                new SupervisorAttachment(
                    "def-2222222222222222",
                    "guard",
                    new SupervisorScope.Workflow("w", null),
                    true,
                    SupervisorSource.DEFINITION,
                    SESSION,
                    "w")))
        .expectNextCount(1)
        .verifyComplete();

    StepVerifier.create(service.removeForSession(SESSION)).verifyComplete();

    assertThat(attachments()).isEmpty();
  }

  /** A definition store that answers late, so two reconciles overlap. */
  private static final class WorkflowDefinitionStoreSlow extends InMemoryWorkflowDefinitionStore {
    @Override
    public Mono<Map<String, WorkflowDefinition>> findAll(final String sessionId) {
      return super.findAll(sessionId).delayElement(java.time.Duration.ofMillis(100));
    }
  }
}
