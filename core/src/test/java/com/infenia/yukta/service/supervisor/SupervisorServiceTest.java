// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorAttachment;
import com.infenia.yukta.plugin.control.SupervisorCategory;
import com.infenia.yukta.plugin.control.SupervisorDescriptor;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.core.ConfigField;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.leader.LeaderElection;
import com.infenia.yukta.service.leader.NotLeaderException;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.review.BoundSupervisor;
import com.infenia.yukta.service.supervisor.ownership.NodeGroupIndex;
import com.infenia.yukta.testing.Fixtures;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import com.infenia.yukta.testsupport.YuktaSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;
import reactor.util.context.Context;

@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidInstantiatingObjectsInLoops",
  "PMD.CouplingBetweenObjects",
  "PMD.ExcessiveImports",
  "PMD.ShortMethodName",
  "PMD.TooManyMethods",
  "PMD.TooManyStaticImports",
  "PMD.UnitTestShouldIncludeAssert"
})
class SupervisorServiceTest {

  private static final String TYPE = "test";

  private final SupervisorStore store = new InMemorySupervisorStore();

  private final SupervisorHost host = loadingNoHolds();

  private final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();

  /** Makes an observer; settings with "bad" are refused. */
  private final SupervisorFactory factory =
      new SupervisorFactory() {
        @Override
        public List<ConfigField> getConfigSchema() {
          return List.of(ConfigField.optional("level", ConfigField.Type.NUMBER, null, "test"));
        }

        @Override
        public String type() {
          return TYPE;
        }

        @Override
        public ExecutionSupervisor create(final Supervisor supervisor) {
          if (supervisor.settings().containsKey("bad")) {
            throw new IllegalArgumentException("bad settings");
          }
          return new ExecutionSupervisor() {
            @Override
            public String id() {
              return "made";
            }

            @Override
            public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
              return Flux.empty();
            }
          };
        }
      };

  private final SupervisorService service =
      new SupervisorService(store, List.of(factory), host, log);

  private List<com.infenia.yukta.service.log.ExecutionLogEvent> audit() {
    return log.readAll().stream()
        .filter(e -> SupervisorService.AUDIT_RUN.equals(e.executionId()))
        .toList();
  }

  private static Supervisor supervisor(final String id) {
    return new Supervisor(id, TYPE, Map.of(), false);
  }

  private static SupervisorAttachment attachment(
      final String id, final String supervisorId, final SupervisorScope scope, final boolean on) {
    return new SupervisorAttachment(id, supervisorId, scope, on);
  }

  @Test
  void manualAttachmentCannotUseReservedPrefix() {
    StepVerifier.create(
            service.attach(
                attachment("def-0123456789abcdef", "p", new SupervisorScope.Global(), true)))
        .expectErrorSatisfies(
            error ->
                assertThat(error)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("reserved"))
        .verify();
  }

  private SupervisorAttachment attached(final String id, final SupervisorScope scope) {
    StepVerifier.create(service.saveSupervisor(supervisor("p")))
        .expectNextCount(1)
        .verifyComplete();
    final SupervisorAttachment attachment = attachment(id, "p", scope, true);
    StepVerifier.create(service.attach(attachment)).expectNext(attachment).verifyComplete();
    return attachment;
  }

  @Test
  void standbyRefusesSupervisorChanges_andLosingTheLeaseStopsTheAttachedOnes() {
    final LeaderElection election = mock(LeaderElection.class);
    service.setLeaderElection(election);
    final ArgumentCaptor<Runnable> onLoss = ArgumentCaptor.forClass(Runnable.class);
    verify(election).onLoss(onLoss.capture());
    when(election.isLeader()).thenReturn(false);

    StepVerifier.create(service.saveSupervisor(supervisor("p")))
        .expectError(NotLeaderException.class)
        .verify();
    StepVerifier.create(service.attach(attachment("b", "p", new SupervisorScope.Global(), true)))
        .expectError(NotLeaderException.class)
        .verify();
    StepVerifier.create(service.detach("b")).expectError(NotLeaderException.class).verify();
    onLoss.getValue().run();

    verify(host).detachAll();
  }

  @Test
  void leaderAcceptsSupervisorChanges() {
    final LeaderElection election = mock(LeaderElection.class);
    when(election.isLeader()).thenReturn(true);
    service.setLeaderElection(election);

    StepVerifier.create(service.saveSupervisor(supervisor("p")))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(service.attach(attachment("b", "p", new SupervisorScope.Global(), true)))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(service.detach("b")).verifyComplete();
  }

  @Test
  void savingProfileLeavesDisabledAndForeignBindingsAlone() {
    StepVerifier.create(service.saveSupervisor(supervisor("p")))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(service.saveSupervisor(supervisor("q")))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(service.attach(attachment("off", "p", new SupervisorScope.Global(), false)))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(
            service.attach(attachment("other", "q", new SupervisorScope.Global(), true)))
        .expectNextCount(1)
        .verifyComplete();

    StepVerifier.create(service.saveSupervisor(supervisor("p")))
        .expectNextCount(1)
        .verifyComplete();

    verify(host, times(1)).attach(any(BoundSupervisor.class));
  }

  @Test
  void auditRecordsAlsoGoToTheAuditSink() {
    final com.infenia.yukta.security.AuditSink sink =
        mock(com.infenia.yukta.security.AuditSink.class);
    service.setAuditSink(sink);

    attached("b1", new SupervisorScope.Global());

    final ArgumentCaptor<com.infenia.yukta.security.AuditRecord> records =
        ArgumentCaptor.forClass(com.infenia.yukta.security.AuditRecord.class);
    verify(sink, times(2)).record(records.capture());
    assertThat(records.getAllValues())
        .extracting(com.infenia.yukta.security.AuditRecord::command)
        .first()
        .isEqualTo("SaveSupervisor(p)");
  }

  @Test
  void typesListTheFactories() {
    assertThat(service.types()).containsExactly(TYPE);
  }

  @Test
  void profilesNeedKnownTypeAndValidSettings() {
    StepVerifier.create(service.saveSupervisor(new Supervisor("p", "nope", Map.of(), false)))
        .expectErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(TYPE))
        .verify();
    StepVerifier.create(service.saveSupervisor(new Supervisor("p", TYPE, Map.of("bad", 1), false)))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(service.saveSupervisor(supervisor("p")))
        .expectNext(supervisor("p"))
        .verifyComplete();

    StepVerifier.create(service.supervisors()).expectNext(supervisor("p")).verifyComplete();
  }

  @Test
  void attachStartsEnabledBindingAndRecordsIt() {
    final SupervisorAttachment attachment = attached("b1", new SupervisorScope.Session("A"));

    final ArgumentCaptor<BoundSupervisor> started = ArgumentCaptor.forClass(BoundSupervisor.class);
    verify(host).attach(started.capture());
    assertThat(started.getValue().id()).isEqualTo("b1");
    assertThat(started.getValue().scope()).isEqualTo(new SupervisorScope.Session("A"));
    StepVerifier.create(service.attachments()).expectNext(attachment).verifyComplete();
    assertThat(audit())
        .anyMatch(e -> String.valueOf(e.status()).contains("AttachSupervisor(b1, session:A)"));
  }

  @Test
  void disabledBindingIsKeptButNotStarted() {
    StepVerifier.create(service.saveSupervisor(supervisor("p")))
        .expectNextCount(1)
        .verifyComplete();
    final SupervisorAttachment attachment =
        attachment("b1", "p", new SupervisorScope.Global(), false);

    StepVerifier.create(service.attach(attachment)).expectNext(attachment).verifyComplete();

    verify(host, never()).attach(any());
    verify(host).detach("b1");
    StepVerifier.create(service.attachments()).expectNext(attachment).verifyComplete();
  }

  @Test
  void attachNeedsKnownProfile() {
    StepVerifier.create(
            service.attach(attachment("b1", "ghost", new SupervisorScope.Global(), true)))
        .expectError(NotFoundException.class)
        .verify();
  }

  @Test
  void attachRefusesTheIdOfConfiguredSupervisor() {
    when(host.isBuiltIn("rules")).thenReturn(true);
    StepVerifier.create(service.saveSupervisor(supervisor("p")))
        .expectNextCount(1)
        .verifyComplete();

    StepVerifier.create(
            service.attach(attachment("rules", "p", new SupervisorScope.Global(), true)))
        .expectError(ConflictException.class)
        .verify();
  }

  @Test
  void restoreLogsAnUnreadableStoreInsteadOfDroppingTheError() {
    final SupervisorStore broken = mock(SupervisorStore.class);
    when(broken.attachments()).thenReturn(Flux.error(new IllegalStateException("no database")));
    final SupervisorService failing = new SupervisorService(broken, List.of(factory), host, log);

    assertThatCode(failing::restore).doesNotThrowAnyException();
  }

  // --- ownership: one owner per unit, refused when an attachment is saved ---

  private void saveSupervisors(final String... ids) {
    for (final String id : ids) {
      StepVerifier.create(service.saveSupervisor(supervisor(id)))
          .expectNextCount(1)
          .verifyComplete();
    }
  }

  private void expectConflict(final SupervisorAttachment attachment, final String... mentions) {
    StepVerifier.create(service.attach(attachment))
        .expectErrorSatisfies(
            error ->
                assertThat(error)
                    .isInstanceOfSatisfying(
                        com.infenia.yukta.service.supervisor.ownership.OwnershipConflictException
                            .class,
                        conflict -> assertThat(conflict.getConflicts()).isNotEmpty())
                    .hasMessageContainingAll(mentions))
        .verify();
  }

  @Test
  void workflowAndItsNodesCannotBeOwnedBySeparateSupervisors() {
    saveSupervisors("a", "b");
    StepVerifier.create(
            service.attach(attachment("a1", "a", new SupervisorScope.Workflow("w", "s"), true)))
        .expectNextCount(1)
        .verifyComplete();

    expectConflict(
        attachment("b1", "b", new SupervisorScope.Section("w", "s", Set.of("build.*")), true),
        "workflow w",
        "owned by a");
  }

  @Test
  void sessionAndAnythingInsideItCannotBeOwnedBySeparateSupervisors() {
    saveSupervisors("a", "b");
    StepVerifier.create(
            service.attach(attachment("a1", "a", new SupervisorScope.Session("s"), true)))
        .expectNextCount(1)
        .verifyComplete();

    expectConflict(
        attachment("b1", "b", new SupervisorScope.Workflow("w", "s"), true), "owned by a");
    expectConflict(attachment("b2", "b", new SupervisorScope.Run("w", "s"), true), "owned by a");
  }

  @Test
  void overlappingPrefixesAndIdUnderPrefixAreRefused() {
    saveSupervisors("a", "b");
    StepVerifier.create(
            service.attach(
                attachment(
                    "a1", "a", new SupervisorScope.Section("w", "s", Set.of("build.*")), true)))
        .expectNextCount(1)
        .verifyComplete();

    expectConflict(
        attachment("b1", "b", new SupervisorScope.Section("w", "s", Set.of("build.test.*")), true),
        "owned by a");
    expectConflict(
        attachment("b2", "b", new SupervisorScope.Section("w", "s", Set.of("build.x")), true),
        "owned by a");
    StepVerifier.create(
            service.attach(
                attachment(
                    "b3", "b", new SupervisorScope.Section("w", "s", Set.of("deploy.*")), true)))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void groupSelectorNeedsTheSessionAndIsResolvedToTheNodesOfTheGroupWhenItStarts() {
    saveSupervisors("a");
    final NodeGroupIndex groups = new NodeGroupIndex();
    groups.put(
        "s",
        Map.of(
            "w",
            Fixtures.workflow(
                "w",
                "d",
                List.of(
                    Fixtures.node("build.compile", "T", Map.of()),
                    Fixtures.node("deploy", "T", Map.of())),
                List.of(),
                List.of())));
    service.setNodeGroups(groups);

    StepVerifier.create(
            service.attach(
                attachment(
                    "a0",
                    "a",
                    new SupervisorScope.Section("w", null, Set.of("group:build")),
                    true)))
        .expectErrorSatisfies(
            error ->
                assertThat(error)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage(SupervisorService.GROUPS_NEED_SESSION))
        .verify();
    StepVerifier.create(
            service.attach(
                attachment(
                    "a1", "a", new SupervisorScope.Section("w", "s", Set.of("group:build")), true)))
        .expectNextCount(1)
        .verifyComplete();
    final ArgumentCaptor<BoundSupervisor> bound = ArgumentCaptor.forClass(BoundSupervisor.class);
    verify(host, org.mockito.Mockito.atLeastOnce()).attach(bound.capture());
    // the bound supervisor owns build.compile, the one node of group build, and not deploy
    assertThat(bound.getValue().scope().matches("s", "w", "build.compile")).isTrue();
    assertThat(bound.getValue().scope().matches("s", "w", "deploy")).isFalse();
    assertThat(bound.getValue().attachment().scope().key()).isEqualTo("workflow:s/w//group:build");
  }

  @Test
  void theRunLevelAndTheNodesCanBeSplitBetweenTwoSupervisors() {
    saveSupervisors("a", "b");
    StepVerifier.create(
            service.attach(attachment("a1", "a", new SupervisorScope.Run("w", "s"), true)))
        .expectNextCount(1)
        .verifyComplete();

    StepVerifier.create(
            service.attach(
                attachment(
                    "b1", "b", new SupervisorScope.Section("w", "s", Set.of("build.*")), true)))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void disabledAttachmentOwnsNothingAndSupervisorMayHoldSeveralOfItsOwn() {
    saveSupervisors("a", "b");
    StepVerifier.create(
            service.attach(attachment("a1", "a", new SupervisorScope.Workflow("w", "s"), false)))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(
            service.attach(attachment("b1", "b", new SupervisorScope.Workflow("w", "s"), true)))
        .expectNextCount(1)
        .verifyComplete();
    // the same supervisor again, and replacing its own attachment, are not conflicts
    StepVerifier.create(
            service.attach(attachment("b2", "b", new SupervisorScope.Run("w", "s"), true)))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(
            service.attach(attachment("b1", "b", new SupervisorScope.Workflow("w", "s"), true)))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void onlyOneSupervisorOwnsTheServer() {
    saveSupervisors("a", "b");
    StepVerifier.create(service.attach(attachment("a1", "a", new SupervisorScope.Server(), true)))
        .expectNextCount(1)
        .verifyComplete();

    expectConflict(attachment("b1", "b", new SupervisorScope.Server(), true), "the server");
  }

  // --- hierarchy: parents ---

  private static Supervisor withParents(final String id, final String... parents) {
    return new Supervisor(id, TYPE, Map.of(), false, null, List.of(parents), Map.of());
  }

  @Test
  void supervisorMayReportToSeveralStoredParents() {
    saveSupervisors("p1", "p2");

    StepVerifier.create(service.saveSupervisor(withParents("child", "p1", "p2")))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void missingParentSelfReferenceCycleAndTooManyLevelsAreRefusedAtSave() {
    saveSupervisors("a");
    StepVerifier.create(service.saveSupervisor(withParents("x", "ghost")))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(service.saveSupervisor(withParents("y", "y")))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(service.saveSupervisor(withParents("b", "a")))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(service.saveSupervisor(withParents("a", "b")))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(service.saveSupervisor(withParents("c", "b")))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(service.saveSupervisor(withParents("d", "c")))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(service.saveSupervisor(withParents("e", "d")))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void parentCannotBeDeletedWhileSupervisorsReportToIt() {
    saveSupervisors("boss");
    StepVerifier.create(service.saveSupervisor(withParents("worker", "boss")))
        .expectNextCount(1)
        .verifyComplete();

    StepVerifier.create(service.deleteSupervisor("boss"))
        .expectErrorSatisfies(
            error ->
                assertThat(error)
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("parent of [worker]"))
        .verify();
    StepVerifier.create(service.deleteSupervisor("worker")).verifyComplete();
    StepVerifier.create(service.deleteSupervisor("boss")).verifyComplete();
  }

  @Test
  void deletingSupervisorRevokesItsAgentCredential() {
    final var credentials = new com.infenia.yukta.service.supervisor.agent.AgentCredentials();
    service.setAgentCredentials(credentials);
    saveSupervisors("agent");
    final String token = credentials.issue("agent", null).token();

    StepVerifier.create(service.deleteSupervisor("agent")).verifyComplete();

    // a supervisor saved again under the same id does not inherit the old credential
    assertThat(credentials.authenticate(token)).isEmpty();
  }

  @Test
  void attachIsLimited() {
    StepVerifier.create(service.saveSupervisor(supervisor("p")))
        .expectNextCount(1)
        .verifyComplete();
    for (int i = 0; i < SupervisorService.DEFAULT_MAX_ATTACHMENTS; i++) {
      StepVerifier.create(
              service.attach(attachment("b" + i, "p", new SupervisorScope.Global(), false)))
          .expectNextCount(1)
          .verifyComplete();
    }

    StepVerifier.create(
            service.attach(attachment("one-more", "p", new SupervisorScope.Global(), true)))
        .expectError(ConflictException.class)
        .verify();
    // replacing an existing attachment is not a new one
    StepVerifier.create(service.attach(attachment("b0", "p", new SupervisorScope.Global(), false)))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void attachmentLimitComesFromSettings() {
    final SupervisorService limited =
        new SupervisorService(new InMemorySupervisorStore(), List.of(factory), host, log);
    limited.setProperties(YuktaSettings.bind(Map.of("yukta.supervisor.max-attachments", "2")));
    StepVerifier.create(limited.saveSupervisor(supervisor("p")))
        .expectNextCount(1)
        .verifyComplete();
    for (final String id : List.of("b0", "b1")) {
      StepVerifier.create(limited.attach(attachment(id, "p", new SupervisorScope.Global(), false)))
          .expectNextCount(1)
          .verifyComplete();
    }

    StepVerifier.create(limited.attach(attachment("b2", "p", new SupervisorScope.Global(), false)))
        .expectErrorSatisfies(
            error ->
                assertThat(error)
                    .isInstanceOf(ConflictException.class)
                    .hasMessage("At most 2 attachments are allowed"))
        .verify();
  }

  @Test
  void detachStopsAndForgetsTheBinding() {
    attached("b1", new SupervisorScope.Global());

    StepVerifier.create(
            service.detach("b1").contextWrite(Context.of(ControlActor.CONTEXT_KEY, "key:ops")))
        .verifyComplete();

    verify(host).detach("b1");
    StepVerifier.create(service.attachments()).verifyComplete();
    assertThat(audit()).anyMatch(e -> String.valueOf(e.status()).contains("DetachSupervisor(b1)"));
    StepVerifier.create(service.detach("b1")).expectError(NotFoundException.class).verify();
  }

  @Test
  void profileInUseCannotBeDeleted() {
    attached("b1", new SupervisorScope.Global());

    StepVerifier.create(service.deleteSupervisor("p"))
        .expectError(ConflictException.class)
        .verify();
    StepVerifier.create(service.detach("b1")).verifyComplete();
    StepVerifier.create(service.deleteSupervisor("p")).verifyComplete();
    StepVerifier.create(service.deleteSupervisor("p"))
        .expectError(NotFoundException.class)
        .verify();
  }

  @Test
  void changingProfileRestartsItsEnabledBindings() {
    attached("b1", new SupervisorScope.Global());

    StepVerifier.create(service.saveSupervisor(new Supervisor("p", TYPE, Map.of("level", 2), true)))
        .expectNextCount(1)
        .verifyComplete();

    final ArgumentCaptor<BoundSupervisor> started = ArgumentCaptor.forClass(BoundSupervisor.class);
    verify(host, times(2)).attach(started.capture());
    assertThat(started.getAllValues().getLast().dryRun()).isTrue();
  }

  @Test
  void deletingSessionDetachesOnlyItsBindings() {
    attached("session-b", new SupervisorScope.Session("A"));
    StepVerifier.create(
            service.attach(attachment("wf-b", "p", new SupervisorScope.Workflow("w", "A"), true)))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(
            service.attach(attachment("other", "p", new SupervisorScope.Session("B"), true)))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(
            service.attach(attachment("global", "p", new SupervisorScope.Global(), true)))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(
            service.attach(
                attachment("section-b", "p", SupervisorScope.parse("workflow:A/w//build.*"), true)))
        .expectNextCount(1)
        .verifyComplete();

    StepVerifier.create(service.removeForSession("A")).verifyComplete();

    StepVerifier.create(service.attachments().map(SupervisorAttachment::id))
        .expectNext("global", "other")
        .verifyComplete();
    verify(host).detach("session-b");
    verify(host).detach("wf-b");
    verify(host).detach("section-b");
  }

  @Test
  void restoreStartsKeptEnabledBindingsAndSkipsBrokenOnes() {
    store.saveSupervisor(supervisor("p")).block();
    store.saveSupervisor(new Supervisor("broken", TYPE, Map.of("bad", 1), false)).block();
    store.saveAttachment(attachment("on", "p", new SupervisorScope.Global(), true)).block();
    store.saveAttachment(attachment("off", "p", new SupervisorScope.Global(), false)).block();
    store.saveAttachment(attachment("orphan", "ghost", new SupervisorScope.Global(), true)).block();
    store.saveAttachment(attachment("bad", "broken", new SupervisorScope.Global(), true)).block();

    service.restore();

    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(() -> verify(host, times(1)).attach(any(BoundSupervisor.class)));
    verify(host, never()).detach(anyString());
  }

  @Test
  void restoreKeepsTheFirstOwnerOfUnitAndMarksTheOverlappingAttachmentsAsConflicts() {
    store.saveSupervisor(supervisor("a")).block();
    store.saveSupervisor(supervisor("b")).block();
    store
        .saveAttachment(attachment("m1", "a", new SupervisorScope.Workflow("w", "s"), true))
        .block();
    store
        .saveAttachment(
            attachment("m2", "b", new SupervisorScope.Section("w", "s", Set.of("build.*")), true))
        .block();
    store.saveAttachment(attachment("m3", "b", new SupervisorScope.Run("w2", "s"), true)).block();

    service.restore();

    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(store.attachments().collectList().block())
                    .extracting(
                        SupervisorAttachment::id,
                        SupervisorAttachment::enabled,
                        SupervisorAttachment::conflict)
                    .containsExactlyInAnyOrder(
                        org.assertj.core.groups.Tuple.tuple("m1", true, false),
                        org.assertj.core.groups.Tuple.tuple("m2", false, true),
                        org.assertj.core.groups.Tuple.tuple("m3", true, false)));
    // the owner and the free unit start; the conflicting one does not
    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(() -> verify(host, times(2)).attach(any(BoundSupervisor.class)));
  }

  @Test
  void restoreCountsStartedAndFailedBindingsAndSkipsWhenStandby() {
    final SimpleMeterRegistry meters = new SimpleMeterRegistry();
    service.setMeterRegistry(meters);
    final LeaderElection election = mock(LeaderElection.class);
    service.setLeaderElection(election);
    store.saveSupervisor(supervisor("p")).block();
    store.saveSupervisor(new Supervisor("broken", TYPE, Map.of("bad", 1), false)).block();
    store.saveAttachment(attachment("on", "p", new SupervisorScope.Global(), true)).block();
    store.saveAttachment(attachment("bad", "broken", new SupervisorScope.Global(), true)).block();

    service.restore();
    verify(host, never()).attach(any(BoundSupervisor.class));

    when(election.isLeader()).thenReturn(true);
    service.restore();
    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                assertThat(
                        meters
                            .get("yukta.supervisor.restored")
                            .tag("result", "failed")
                            .gauge()
                            .value())
                    .isEqualTo(1.0));
    assertThat(meters.get("yukta.supervisor.restored").tag("result", "started").gauge().value())
        .isEqualTo(1.0);
  }

  @Test
  @ExpectedErrorLog("ERROR com.infenia.yukta.service.supervisor.SupervisorService - ")
  void restoreGivesUpAfterFiveRetriesWhenTheStoreStaysDown() {
    final SupervisorStore down = spy(new InMemorySupervisorStore());
    doReturn(Flux.error(new IllegalStateException("store down"))).when(down).attachments();
    final SupervisorService restoring = new SupervisorService(down, List.of(factory), host, log);
    final VirtualTimeScheduler time = VirtualTimeScheduler.getOrSet();
    try {
      restoring.restore();
      time.advanceTimeBy(Duration.ofMinutes(5));

      verify(down, times(6)).attachments();
      verify(host, never()).attach(any(BoundSupervisor.class));
    } finally {
      VirtualTimeScheduler.reset();
    }
  }

  /**
   * A service whose store refuses to save a attachment, after a attachment {@code b} was attached.
   */
  private SupervisorService failingSave(final boolean firstEnabled) {
    final SupervisorStore failing = spy(new InMemorySupervisorStore());
    final SupervisorService failingService =
        new SupervisorService(failing, List.of(factory), host, log);
    StepVerifier.create(failingService.saveSupervisor(supervisor("p")))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(
            failingService.attach(attachment("b", "p", new SupervisorScope.Global(), firstEnabled)))
        .expectNextCount(1)
        .verifyComplete();
    doReturn(Mono.error(new IllegalStateException("db"))).when(failing).saveAttachment(any());
    return failingService;
  }

  @Test
  void failedSaveRestartsTheBindingThatWasStored() {
    final SupervisorService failing = failingSave(true);

    StepVerifier.create(failing.attach(attachment("b", "p", new SupervisorScope.Global(), true)))
        .expectError(IllegalStateException.class)
        .verify();

    verify(host, times(3)).attach(any());
  }

  @Test
  void failedSaveStopsTheSupervisorWhenTheStoredBindingIsOff() {
    final SupervisorService failing = failingSave(false);

    StepVerifier.create(failing.attach(attachment("b", "p", new SupervisorScope.Global(), true)))
        .expectError(IllegalStateException.class)
        .verify();

    verify(host, times(1)).attach(any());
    verify(host, times(2)).detach("b");
  }

  @Test
  void failedSaveOfNewBindingStopsItsSupervisor() {
    final SupervisorStore failing = spy(new InMemorySupervisorStore());
    final SupervisorService failingService =
        new SupervisorService(failing, List.of(factory), host, log);
    StepVerifier.create(failingService.saveSupervisor(supervisor("p")))
        .expectNextCount(1)
        .verifyComplete();
    doReturn(Mono.error(new IllegalStateException("db"))).when(failing).saveAttachment(any());

    StepVerifier.create(
            failingService.attach(attachment("n", "p", new SupervisorScope.Global(), true)))
        .expectError(IllegalStateException.class)
        .verify();

    verify(host).detach("n");
  }

  @Test
  void failedSaveReportsTheSaveErrorEvenWhenTheRollbackCannotRead() {
    final SupervisorStore failing = spy(new InMemorySupervisorStore());
    final SupervisorService failingService =
        new SupervisorService(failing, List.of(factory), host, log);
    StepVerifier.create(failingService.saveSupervisor(supervisor("p")))
        .expectNextCount(1)
        .verifyComplete();
    doReturn(Mono.error(new IllegalStateException("db"))).when(failing).saveAttachment(any());
    // the attachment count, the ownership check, then the rollback that cannot read
    doReturn(Flux.empty())
        .doReturn(Flux.empty())
        .doReturn(Flux.error(new IllegalStateException("read")))
        .when(failing)
        .attachments();

    StepVerifier.create(
            failingService.attach(attachment("n", "p", new SupervisorScope.Global(), true)))
        .expectErrorMessage("db")
        .verify();
  }

  @Test
  void savingProfileWithBadIdIsRefusedAndStoresNothing() {
    StepVerifier.create(service.saveSupervisor(supervisor("key:admin")))
        .expectErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("supervisor id")
                    .hasMessageContaining("key:admin"))
        .verify();

    StepVerifier.create(store.supervisors()).verifyComplete();
  }

  @Test
  void attachingBindingWithBadIdIsRefusedAndStoresNothing() {
    StepVerifier.create(service.saveSupervisor(supervisor("p")))
        .expectNextCount(1)
        .verifyComplete();

    StepVerifier.create(service.attach(attachment("a b", "p", new SupervisorScope.Global(), true)))
        .expectErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("attachment id"))
        .verify();

    StepVerifier.create(store.attachments()).verifyComplete();
    verify(host, never()).attach(any(BoundSupervisor.class));
  }

  @Test
  void catalogAndSecretQueriesReadTheFactoryOfTheType() {
    final SupervisorFactory declaring =
        new SupervisorFactory() {
          @Override
          public String type() {
            return "declaring";
          }

          @Override
          public ExecutionSupervisor create(final Supervisor supervisor) {
            throw new UnsupportedOperationException("not used by this test");
          }

          @Override
          public SupervisorDescriptor describe() {
            return new SupervisorDescriptor(
                "Declaring",
                "Declares its settings.",
                "Use it in tests.",
                SupervisorCategory.RULES,
                Optional.empty(),
                Map.of());
          }

          @Override
          public List<ConfigField> getConfigSchema() {
            return List.of(
                ConfigField.required("url", ConfigField.Type.STRING, "where"),
                ConfigField.optional("token", ConfigField.Type.SECRET, null, "credential"),
                ConfigField.optional("retries", ConfigField.Type.NUMBER, 3, "how often"));
          }
        };
    final SupervisorService declaringService =
        new SupervisorService(new InMemorySupervisorStore(), List.of(declaring), host, log);

    assertThat(declaringService.secretSettings("declaring")).containsExactly("token");
    assertThat(declaringService.secretSettings("unknown")).isEmpty();
    assertThat(declaringService.describe("declaring"))
        .map(SupervisorDescriptor::displayName)
        .hasValue("Declaring");
    assertThat(declaringService.describe("unknown")).isEmpty();
    assertThat(declaringService.configSchema("declaring"))
        .extracting(ConfigField::name)
        .containsExactly("url", "token", "retries");
    assertThat(declaringService.configSchema("unknown")).isEmpty();
  }

  @Test
  void validIdIsAccepted() {
    StepVerifier.create(service.saveSupervisor(supervisor("My.profile_1-x")))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void restoreFailsTheBindingWhoseIdBreaksRule() {
    StepVerifier.create(store.saveSupervisor(supervisor("p"))).verifyComplete();
    StepVerifier.create(
            store.saveAttachment(attachment("key:admin", "p", new SupervisorScope.Global(), true)))
        .verifyComplete();
    StepVerifier.create(
            store.saveAttachment(attachment("good", "p", new SupervisorScope.Global(), true)))
        .verifyComplete();

    service.restore();

    verify(host, times(1)).attach(any(BoundSupervisor.class));
  }

  @Test
  void everyChangeIsVersionAndRollbackSavesAnEarlierOneAsNewVersion() {
    final Supervisor first = new Supervisor("v", TYPE, Map.of("level", 1), false);
    final Supervisor second = new Supervisor("v", TYPE, Map.of("level", 2), false);
    StepVerifier.create(service.saveSupervisor(first)).expectNextCount(1).verifyComplete();
    StepVerifier.create(service.saveSupervisor(first)).expectNextCount(1).verifyComplete();
    StepVerifier.create(service.saveSupervisor(second)).expectNextCount(1).verifyComplete();

    StepVerifier.create(service.versions("v"))
        .assertNext(
            versions -> {
              // the repeat of the same settings is not a version
              assertThat(versions)
                  .extracting(SupervisorVersions.Version::version)
                  .containsExactly(2, 1);
              assertThat(versions.get(0).supervisor()).isEqualTo(second);
            })
        .verifyComplete();

    StepVerifier.create(service.rollback("v", 1)).expectNext(first).verifyComplete();
    StepVerifier.create(service.versions("v"))
        .assertNext(
            versions ->
                assertThat(versions)
                    .extracting(SupervisorVersions.Version::version)
                    .containsExactly(3, 2, 1))
        .verifyComplete();
  }

  @Test
  void versionThatIsNotKeptAndAnUnknownSupervisorAreNotFound() {
    StepVerifier.create(service.saveSupervisor(supervisor("v")))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(service.rollback("v", 9)).expectError(NotFoundException.class).verify();
    StepVerifier.create(service.versions("nope")).expectError(NotFoundException.class).verify();
  }

  @Test
  void deletingSupervisorForgetsItsVersions() {
    StepVerifier.create(service.saveSupervisor(supervisor("v")))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(service.deleteSupervisor("v")).verifyComplete();
    StepVerifier.create(service.saveSupervisor(supervisor("v")))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(service.versions("v"))
        .assertNext(versions -> assertThat(versions).hasSize(1))
        .verifyComplete();
  }

  @Test
  void onlyTheNewestTwentyVersionsAreKept() {
    final SupervisorVersions kept = new SupervisorVersions();
    for (int i = 0; i < 25; i++) {
      kept.record(
          new Supervisor("k", TYPE, Map.of("level", i), false),
          Instant.EPOCH,
          SupervisorVersions.KEPT);
    }
    assertThat(kept.list("k")).hasSize(SupervisorVersions.KEPT);
    assertThat(kept.list("k").getFirst().version()).isEqualTo(25);
    assertThat(kept.list("k").getLast().version()).isEqualTo(6);
    assertThat(kept.list("other")).isEmpty();
  }

  /** Pauses a run when it fails; with the setting "plain" it is a supervisor without causes. */
  private SupervisorService replaying(final boolean plain) {
    return replaying(plain, com.infenia.yukta.plugin.control.engine.Cause.tick());
  }

  private SupervisorService replaying(
      final boolean plain, final com.infenia.yukta.plugin.control.engine.Cause cause) {
    final SupervisorFactory pausing =
        new SupervisorFactory() {
          @Override
          public List<ConfigField> getConfigSchema() {
            return List.of();
          }

          @Override
          public String type() {
            return "pausing";
          }

          @Override
          public ExecutionSupervisor create(final Supervisor supervisor) {
            if (plain) {
              return new ExecutionSupervisor() {
                @Override
                public String id() {
                  return "made";
                }

                @Override
                public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
                  return events
                      .filter(e -> e instanceof ExecutionEvent.WorkflowStatusChanged)
                      .map(e -> new ExecutionControlCommand.PauseWorkflowCommand(e.executionId()));
                }
              };
            }
            return new com.infenia.yukta.plugin.control.engine.DirectiveSupervisor() {
              @Override
              public String id() {
                return "made";
              }

              @Override
              public Flux<com.infenia.yukta.plugin.control.engine.Directive.Command> directives(
                  final Flux<ExecutionEvent> events) {
                return events
                    .filter(
                        e ->
                            e instanceof ExecutionEvent.WorkflowStatusChanged changed
                                && "FAILURE".equals(changed.status()))
                    .map(
                        e ->
                            new com.infenia.yukta.plugin.control.engine.Directive.Command(
                                "k",
                                new ExecutionControlCommand.PauseWorkflowCommand(e.executionId()),
                                cause,
                                "failed",
                                null));
              }
            };
          }
        };
    final SupervisorService made =
        new SupervisorService(new InMemorySupervisorStore(), List.of(pausing), host, log);
    StepVerifier.create(made.saveSupervisor(new Supervisor("r", "pausing", Map.of(), false)))
        .expectNextCount(1)
        .verifyComplete();
    log.append(com.infenia.yukta.service.log.ExecutionLogEvent.started("run1", "s", "w", "v1"));
    log.append(
        com.infenia.yukta.service.log.ExecutionLogEvent.finished(
            "run1", "s", "w", "FAILURE", Map.of("n1", "FAILURE")));
    return made;
  }

  @Test
  void replayShowsWhatTheSupervisorWouldHaveDoneAndWhetherItMayDoIt() {
    StepVerifier.create(replaying(false).replay("r", null, "run1"))
        .assertNext(
            decisions ->
                assertThat(decisions)
                    .singleElement()
                    .satisfies(
                        d -> {
                          assertThat(d.command()).isEqualTo("PauseWorkflowCommand");
                          assertThat(d.executionId()).isEqualTo("run1");
                          assertThat(d.cause()).isEqualTo("tick");
                          assertThat(d.reason()).isEqualTo("failed");
                          // a tick is initiative, and the supervisor lists none
                          assertThat(d.allowed()).isFalse();
                        }))
        .verifyComplete();
  }

  @Test
  void replayOfPlainSupervisorSeesEveryStatusEvent() {
    StepVerifier.create(replaying(true).replay("r", Map.of(), "run1"))
        .assertNext(
            decisions -> {
              assertThat(decisions).hasSize(2);
              assertThat(decisions).allSatisfy(d -> assertThat(d.allowed()).isTrue());
            })
        .verifyComplete();
  }

  @Test
  void replayNeedsTheSupervisorAndRecordedRun() {
    final SupervisorService made = replaying(false);
    StepVerifier.create(made.replay("nope", null, "run1"))
        .expectError(NotFoundException.class)
        .verify();
    StepVerifier.create(made.replay("r", null, "unknown"))
        .expectError(NotFoundException.class)
        .verify();
  }

  private static Boolean replayedAllowed(
      final SupervisorService made,
      final @org.jspecify.annotations.Nullable Map<String, Object> draft) {
    return java.util.Objects.requireNonNull(made.replay("r", draft, "run1").block())
        .getFirst()
        .allowed();
  }

  @Test
  void replayAllowsInitiativeListedAndRefusesCommandNotAllowed() {
    assertThat(
            replayedAllowed(
                replaying(false),
                Map.of(
                    "allowedCommands", List.of("PauseWorkflowCommand"),
                    "initiative", List.of("PauseWorkflowCommand"))))
        .isTrue();
    final SupervisorService reacting =
        replaying(false, com.infenia.yukta.plugin.control.engine.Cause.event("ev"));
    assertThat(replayedAllowed(reacting, null)).isTrue();
    assertThat(replayedAllowed(reacting, Map.of("allowedCommands", List.of("StopWorkflowCommand"))))
        .isFalse();
  }

  @Test
  void resolverLearnsTheStoredSupervisorsWhenOneIsSaved() {
    final com.infenia.yukta.service.supervisor.hierarchy.SupervisorResolver resolver =
        new com.infenia.yukta.service.supervisor.hierarchy.SupervisorResolver();
    service.setResolver(resolver);

    StepVerifier.create(service.saveSupervisor(supervisor("p")))
        .expectNextCount(1)
        .verifyComplete();

    assertThat(resolver.supervisor("p")).isPresent();
  }

  @Test
  void presetsComeFromTheFactoryOfTheType() {
    assertThat(service.presets(TYPE)).isPresent();
    assertThat(service.presets("nope")).isEmpty();
  }

  @Test
  void savingSupervisorWhoseAttachmentCannotRestartStillSaves() {
    attached("b1", new SupervisorScope.Workflow("w", "s"));
    org.mockito.Mockito.doThrow(new IllegalStateException("host down"))
        .when(host)
        .attach(any(BoundSupervisor.class));

    StepVerifier.create(service.saveSupervisor(new Supervisor("p", TYPE, Map.of("level", 2), true)))
        .expectNextCount(1)
        .verifyComplete();
    assertThat(store.supervisors().filter(Supervisor::dryRun).collectList().block()).hasSize(1);
  }

  @Test
  void restoreComparesSectionsOfOneWorkflowAndCarriesOnWhenMarkCannotBeSaved() {
    final SupervisorStore failing = spy(new InMemorySupervisorStore());
    failing.saveSupervisor(supervisor("a")).block();
    failing.saveSupervisor(supervisor("b")).block();
    failing
        .saveAttachment(
            attachment("m1", "a", new SupervisorScope.Section("w", "s", Set.of("build.*")), true))
        .block();
    failing
        .saveAttachment(
            attachment(
                "m2", "b", new SupervisorScope.Section("w", "s", Set.of("build.test")), true))
        .block();
    doReturn(Mono.error(new IllegalStateException("disk full")))
        .when(failing)
        .saveAttachment(org.mockito.ArgumentMatchers.argThat(SupervisorAttachment::conflict));
    final SupervisorService restoring = new SupervisorService(failing, List.of(factory), host, log);

    restoring.restore();

    // the mark was not saved, so both stay enabled in the store; the restore still starts
    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(
            () ->
                verify(host, org.mockito.Mockito.atLeastOnce()).attach(any(BoundSupervisor.class)));
    assertThat(failing.attachments().filter(SupervisorAttachment::conflict).collectList().block())
        .isEmpty();
  }

  @Test
  void selectorsAreComparedWithTheNodesOfStoredWorkflow() {
    final com.infenia.yukta.service.workflow.store.InMemoryWorkflowDefinitionStore definitions =
        new com.infenia.yukta.service.workflow.store.InMemoryWorkflowDefinitionStore();
    definitions
        .save(
            "s",
            Fixtures.workflow(
                "w",
                "d",
                List.of(
                    Fixtures.node("build.compile", "T", Map.of()),
                    Fixtures.node("deploy", "T", Map.of())),
                List.of(),
                List.of()))
        .block();
    service.setDefinitions(definitions);
    saveSupervisors("a", "b", "c");
    StepVerifier.create(service.attach(attachment("g", "c", new SupervisorScope.Global(), true)))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(
            service.attach(attachment("other", "c", new SupervisorScope.Session("s2"), true)))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(
            service.attach(
                attachment(
                    "x", "a", new SupervisorScope.Section("w", "s", Set.of("build.*")), true)))
        .expectNextCount(1)
        .verifyComplete();

    StepVerifier.create(
            service.attach(
                attachment(
                    "y", "b", new SupervisorScope.Section("w", "s", Set.of("deploy")), true)))
        .expectNextCount(1)
        .verifyComplete();
    expectConflict(
        attachment("z", "b", new SupervisorScope.Section("w", "s", Set.of("build.compile")), true),
        "owned by a");
  }

  @Test
  void supervisorThatReadsGetsTheReadsOfItsScope() {
    final List<com.infenia.yukta.plugin.control.engine.EngineReads> given =
        new java.util.concurrent.CopyOnWriteArrayList<>();
    final SupervisorFactory reading =
        new SupervisorFactory() {
          @Override
          public List<ConfigField> getConfigSchema() {
            return List.of();
          }

          @Override
          public String type() {
            return "reading";
          }

          @Override
          public ExecutionSupervisor create(final Supervisor supervisor) {
            return new Reader(given);
          }
        };
    final SupervisorService made =
        new SupervisorService(new InMemorySupervisorStore(), List.of(reading), host, log);
    final com.infenia.yukta.service.supervisor.engine.SupervisorQueries queries =
        mock(com.infenia.yukta.service.supervisor.engine.SupervisorQueries.class);
    when(queries.readsFor(any(), any()))
        .thenReturn(com.infenia.yukta.plugin.control.engine.EngineReads.NONE);
    @SuppressWarnings("unchecked")
    final org.springframework.beans.factory.ObjectProvider<
            com.infenia.yukta.service.supervisor.engine.SupervisorQueries>
        provider = mock(org.springframework.beans.factory.ObjectProvider.class);
    when(provider.getIfAvailable())
        .thenReturn(queries, (com.infenia.yukta.service.supervisor.engine.SupervisorQueries) null);
    StepVerifier.create(made.saveSupervisor(new Supervisor("r", "reading", Map.of(), false)))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(
            made.attach(attachment("zero", "r", new SupervisorScope.Workflow("zero", "s"), true)))
        .expectNextCount(1)
        .verifyComplete();
    made.setQueries(provider);

    for (final String id : List.of("one", "two")) {
      StepVerifier.create(
              made.attach(attachment(id, "r", new SupervisorScope.Workflow(id, "s"), true)))
          .expectNextCount(1)
          .verifyComplete();
    }

    // without queries nothing is given; then the first start finds them and the second does not
    assertThat(given).containsExactly(com.infenia.yukta.plugin.control.engine.EngineReads.NONE);
  }

  /** A supervisor that keeps the reads it is given. */
  private record Reader(List<com.infenia.yukta.plugin.control.engine.EngineReads> given)
      implements ExecutionSupervisor, com.infenia.yukta.plugin.control.engine.ReadsAware {

    @Override
    public String id() {
      return "reader";
    }

    @Override
    public Flux<ExecutionControlCommand> supervise(final Flux<ExecutionEvent> events) {
      return Flux.empty();
    }

    @Override
    public void readsFrom(final com.infenia.yukta.plugin.control.engine.EngineReads reads) {
      given.add(reads);
    }
  }

  /** A host mock whose hold ledger loads nothing, as a restore loads it first. */
  private static SupervisorHost loadingNoHolds() {
    final SupervisorHost mocked = mock(SupervisorHost.class);
    lenient().when(mocked.loadHolds(any())).thenReturn(Mono.empty());
    return mocked;
  }
}
