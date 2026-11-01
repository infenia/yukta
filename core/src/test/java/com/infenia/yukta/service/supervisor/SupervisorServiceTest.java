// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.infenia.yukta.plugin.control.ControlActor;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorBinding;
import com.infenia.yukta.plugin.control.SupervisorFactory;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.time.Duration;
import java.util.List;
import java.util.Map;
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
        public String type() {
          return TYPE;
        }

        @Override
        public ExecutionSupervisor create(final SupervisorProfile profile) {
          if (profile.settings().containsKey("bad")) {
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

  private static SupervisorProfile profile(final String id) {
    return new SupervisorProfile(id, TYPE, Map.of(), false);
  }

  private static SupervisorBinding binding(
      final String id, final String profileId, final SupervisorScope scope, final boolean on) {
    return new SupervisorBinding(id, profileId, scope, on);
  }

  private SupervisorBinding attached(final String id, final SupervisorScope scope) {
    StepVerifier.create(service.saveProfile(profile("p"))).expectNextCount(1).verifyComplete();
    final SupervisorBinding binding = binding(id, "p", scope, true);
    StepVerifier.create(service.attach(binding)).expectNext(binding).verifyComplete();
    return binding;
  }

  @Test
  void savingProfileLeavesDisabledAndForeignBindingsAlone() {
    StepVerifier.create(service.saveProfile(profile("p"))).expectNextCount(1).verifyComplete();
    StepVerifier.create(service.saveProfile(profile("q"))).expectNextCount(1).verifyComplete();
    StepVerifier.create(service.attach(binding("off", "p", new SupervisorScope.Global(), false)))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(service.attach(binding("other", "q", new SupervisorScope.Global(), true)))
        .expectNextCount(1)
        .verifyComplete();

    StepVerifier.create(service.saveProfile(profile("p"))).expectNextCount(1).verifyComplete();

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
    StepVerifier.create(service.saveProfile(new SupervisorProfile("p", "nope", Map.of(), false)))
        .expectErrorSatisfies(
            e ->
                assertThat(e)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining(TYPE))
        .verify();
    StepVerifier.create(
            service.saveProfile(new SupervisorProfile("p", TYPE, Map.of("bad", 1), false)))
        .expectError(IllegalArgumentException.class)
        .verify();
    StepVerifier.create(service.saveProfile(profile("p")))
        .expectNext(profile("p"))
        .verifyComplete();

    StepVerifier.create(service.profiles()).expectNext(profile("p")).verifyComplete();
  }

  @Test
  void attachStartsEnabledBindingAndRecordsIt() {
    final SupervisorBinding binding = attached("b1", new SupervisorScope.Session("A"));

    final ArgumentCaptor<BoundSupervisor> started = ArgumentCaptor.forClass(BoundSupervisor.class);
    verify(host).attach(started.capture());
    assertThat(started.getValue().id()).isEqualTo("b1");
    assertThat(started.getValue().scope()).isEqualTo(new SupervisorScope.Session("A"));
    StepVerifier.create(service.bindings()).expectNext(binding).verifyComplete();
    assertThat(audit())
        .anyMatch(e -> String.valueOf(e.status()).contains("AttachSupervisor(b1, session:A)"));
  }

  @Test
  void disabledBindingIsKeptButNotStarted() {
    StepVerifier.create(service.saveProfile(profile("p"))).expectNextCount(1).verifyComplete();
    final SupervisorBinding binding = binding("b1", "p", new SupervisorScope.Global(), false);

    StepVerifier.create(service.attach(binding)).expectNext(binding).verifyComplete();

    verify(host, never()).attach(any());
    verify(host).detach("b1");
    StepVerifier.create(service.bindings()).expectNext(binding).verifyComplete();
  }

  @Test
  void attachNeedsKnownProfile() {
    StepVerifier.create(service.attach(binding("b1", "ghost", new SupervisorScope.Global(), true)))
        .expectError(NotFoundException.class)
        .verify();
  }

  @Test
  void attachRefusesTheIdOfConfiguredSupervisor() {
    org.mockito.Mockito.when(host.isBuiltIn("rules")).thenReturn(true);
    StepVerifier.create(service.saveProfile(profile("p"))).expectNextCount(1).verifyComplete();

    StepVerifier.create(service.attach(binding("rules", "p", new SupervisorScope.Global(), true)))
        .expectError(ConflictException.class)
        .verify();
  }

  @Test
  void restoreLogsAnUnreadableStoreInsteadOfDroppingTheError() {
    final SupervisorStore broken = mock(SupervisorStore.class);
    org.mockito.Mockito.when(broken.bindings())
        .thenReturn(Flux.error(new IllegalStateException("no database")));
    final SupervisorService failing = new SupervisorService(broken, List.of(factory), host, log);

    org.assertj.core.api.Assertions.assertThatCode(failing::restore).doesNotThrowAnyException();
  }

  @Test
  void attachIsLimited() {
    StepVerifier.create(service.saveProfile(profile("p"))).expectNextCount(1).verifyComplete();
    for (int i = 0; i < SupervisorService.MAX_BINDINGS; i++) {
      StepVerifier.create(
              service.attach(binding("b" + i, "p", new SupervisorScope.Global(), false)))
          .expectNextCount(1)
          .verifyComplete();
    }

    StepVerifier.create(
            service.attach(binding("one-more", "p", new SupervisorScope.Global(), true)))
        .expectError(ConflictException.class)
        .verify();
    // replacing an existing binding is not a new one
    StepVerifier.create(service.attach(binding("b0", "p", new SupervisorScope.Global(), false)))
        .expectNextCount(1)
        .verifyComplete();
  }

  @Test
  void detachStopsAndForgetsTheBinding() {
    attached("b1", new SupervisorScope.Global());

    StepVerifier.create(
            service.detach("b1").contextWrite(Context.of(ControlActor.CONTEXT_KEY, "key:ops")))
        .verifyComplete();

    verify(host).detach("b1");
    StepVerifier.create(service.bindings()).verifyComplete();
    assertThat(audit()).anyMatch(e -> String.valueOf(e.status()).contains("DetachSupervisor(b1)"));
    StepVerifier.create(service.detach("b1")).expectError(NotFoundException.class).verify();
  }

  @Test
  void profileInUseCannotBeDeleted() {
    attached("b1", new SupervisorScope.Global());

    StepVerifier.create(service.deleteProfile("p")).expectError(ConflictException.class).verify();
    StepVerifier.create(service.detach("b1")).verifyComplete();
    StepVerifier.create(service.deleteProfile("p")).verifyComplete();
    StepVerifier.create(service.deleteProfile("p")).expectError(NotFoundException.class).verify();
  }

  @Test
  void changingProfileRestartsItsEnabledBindings() {
    attached("b1", new SupervisorScope.Global());

    StepVerifier.create(
            service.saveProfile(new SupervisorProfile("p", TYPE, Map.of("level", 2), true)))
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
            service.attach(binding("wf-b", "p", new SupervisorScope.Workflow("w", "A"), true)))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(
            service.attach(binding("other", "p", new SupervisorScope.Session("B"), true)))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(service.attach(binding("global", "p", new SupervisorScope.Global(), true)))
        .expectNextCount(1)
        .verifyComplete();

    StepVerifier.create(service.removeForSession("A")).verifyComplete();

    StepVerifier.create(service.bindings().map(SupervisorBinding::id))
        .expectNext("global", "other")
        .verifyComplete();
    verify(host).detach("session-b");
    verify(host).detach("wf-b");
  }

  @Test
  void restoreStartsKeptEnabledBindingsAndSkipsBrokenOnes() {
    store.saveProfile(profile("p")).block();
    store.saveProfile(new SupervisorProfile("broken", TYPE, Map.of("bad", 1), false)).block();
    store.saveBinding(binding("on", "p", new SupervisorScope.Global(), true)).block();
    store.saveBinding(binding("off", "p", new SupervisorScope.Global(), false)).block();
    store.saveBinding(binding("orphan", "ghost", new SupervisorScope.Global(), true)).block();
    store.saveBinding(binding("bad", "broken", new SupervisorScope.Global(), true)).block();

    service.restore();

    await()
        .atMost(3, TimeUnit.SECONDS)
        .untilAsserted(() -> verify(host, times(1)).attach(any(BoundSupervisor.class)));
    verify(host, never()).detach(anyString());
  }

  @Test
  @ExpectedErrorLog("ERROR com.infenia.yukta.service.supervisor.SupervisorService - ")
  void restoreGivesUpAfterFiveRetriesWhenTheStoreStaysDown() {
    final SupervisorStore down = spy(new InMemorySupervisorStore());
    doReturn(Flux.error(new IllegalStateException("store down"))).when(down).bindings();
    final SupervisorService restoring = new SupervisorService(down, List.of(factory), host, log);
    final VirtualTimeScheduler time = VirtualTimeScheduler.getOrSet();
    try {
      restoring.restore();
      time.advanceTimeBy(Duration.ofMinutes(5));

      verify(down, times(6)).bindings();
      verify(host, never()).attach(any(BoundSupervisor.class));
    } finally {
      VirtualTimeScheduler.reset();
    }
  }

  /** A service whose store refuses to save a binding, after a binding {@code b} was attached. */
  private SupervisorService failingSave(final boolean firstEnabled) {
    final SupervisorStore failing = spy(new InMemorySupervisorStore());
    final SupervisorService failingService =
        new SupervisorService(failing, List.of(factory), host, log);
    StepVerifier.create(failingService.saveProfile(profile("p")))
        .expectNextCount(1)
        .verifyComplete();
    StepVerifier.create(
            failingService.attach(binding("b", "p", new SupervisorScope.Global(), firstEnabled)))
        .expectNextCount(1)
        .verifyComplete();
    doReturn(Mono.error(new IllegalStateException("db"))).when(failing).saveBinding(any());
    return failingService;
  }

  @Test
  void failedSaveRestartsTheBindingThatWasStored() {
    final SupervisorService failing = failingSave(true);

    StepVerifier.create(failing.attach(binding("b", "p", new SupervisorScope.Global(), true)))
        .expectError(IllegalStateException.class)
        .verify();

    verify(host, times(3)).attach(any());
  }

  @Test
  void failedSaveStopsTheSupervisorWhenTheStoredBindingIsOff() {
    final SupervisorService failing = failingSave(false);

    StepVerifier.create(failing.attach(binding("b", "p", new SupervisorScope.Global(), true)))
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
    StepVerifier.create(failingService.saveProfile(profile("p")))
        .expectNextCount(1)
        .verifyComplete();
    doReturn(Mono.error(new IllegalStateException("db"))).when(failing).saveBinding(any());

    StepVerifier.create(
            failingService.attach(binding("n", "p", new SupervisorScope.Global(), true)))
        .expectError(IllegalStateException.class)
        .verify();

    verify(host).detach("n");
  }

  @Test
  void failedSaveReportsTheSaveErrorEvenWhenTheRollbackCannotRead() {
    final SupervisorStore failing = spy(new InMemorySupervisorStore());
    final SupervisorService failingService =
        new SupervisorService(failing, List.of(factory), host, log);
    StepVerifier.create(failingService.saveProfile(profile("p")))
        .expectNextCount(1)
        .verifyComplete();
    doReturn(Mono.error(new IllegalStateException("db"))).when(failing).saveBinding(any());
    doReturn(Flux.empty())
        .doReturn(Flux.error(new IllegalStateException("read")))
        .when(failing)
        .bindings();

    StepVerifier.create(
            failingService.attach(binding("n", "p", new SupervisorScope.Global(), true)))
        .expectErrorMessage("db")
        .verify();
  }
}
