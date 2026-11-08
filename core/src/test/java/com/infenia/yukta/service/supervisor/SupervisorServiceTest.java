// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
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
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import com.infenia.yukta.testsupport.YuktaSettings;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.test.scheduler.VirtualTimeScheduler;
import reactor.util.context.Context;

@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyStaticImports",
  "PMD.ShortMethodName",
  "PMD.AvoidInstantiatingObjectsInLoops",
  "PMD.ExcessiveImports"
})
class SupervisorServiceTest {

  private static final String TYPE = "test";

  private final SupervisorStore store = new InMemorySupervisorStore();

  private final SupervisorHost host = mock(SupervisorHost.class);

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

    verify(sink).record(any());
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
    doReturn(Flux.empty())
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
}
