// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.notify;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.model.notify.NotificationRuleSettings;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.plugin.input.TimeoutAction;
import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.plugin.notify.NotificationSeverity;
import com.infenia.yukta.service.input.InputRequestStore;
import com.infenia.yukta.service.input.InputTestSupport.MutableClock;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.service.review.AttachedSupervisors;
import com.infenia.yukta.service.review.BoundSupervisor;
import com.infenia.yukta.service.review.ExecutionEventFeed;
import com.infenia.yukta.service.sessionstore.InternalSessions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

@NoArgsConstructor
@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.CouplingBetweenObjects",
  "PMD.TooManyStaticImports",
  "PMD.ExcessiveImports",
  "PMD.AvoidDuplicateLiterals",
  "PMD.AvoidInstantiatingObjectsInLoops",
  "PMD.AvoidFieldNameMatchingMethodName"
})
class NotificationProducerTest {

  private static final Instant NOW = Instant.parse("2026-11-15T10:00:00Z");
  private static final String OPERATOR = "role:OPERATOR";
  private static final String SUPERVISORS = "supervisor:*";
  private static final String ITEM = "input:r1";
  private static final String EXEC = "e1";

  private final MutableClock clock = new MutableClock();
  private final InMemoryNotificationStore store = new InMemoryNotificationStore(100);
  private final NotificationService service =
      new NotificationService(store, new InputRedactor(List.of()), clock);
  private final InputRequestStore inputs = mock(InputRequestStore.class);
  private final TaskTrackerService tracker = mock(TaskTrackerService.class);
  private final InternalSessions internal = mock(InternalSessions.class);
  private final AttachedSupervisors supervisors = mock(AttachedSupervisors.class);
  private final AtomicBoolean leader = new AtomicBoolean(true);
  private final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
  private final ExecutionEventFeed feed = new ExecutionEventFeed();

  private NotificationProducer producer = producer(Map.of());

  private static YuktaProperties props(final Map<String, NotificationRuleSettings> rules) {
    return new YuktaProperties(
        null,
        null,
        null,
        null,
        null,
        null,
        new YuktaProperties.Notifications(
            true, Duration.ofDays(7), 100, Duration.ofSeconds(2), rules, Map.of()),
        null,
        null,
        null,
        null,
        null,
        null);
  }

  private NotificationProducer producer(final Map<String, NotificationRuleSettings> rules) {
    when(tracker.lineage(anyString()))
        .thenAnswer(call -> new TaskTrackerService.Lineage(call.getArgument(0), 0));
    when(internal.checkInternal(org.mockito.ArgumentMatchers.any())).thenReturn(Mono.just(false));
    when(inputs.listOpen(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Flux.empty());
    return new NotificationProducer(
        feed,
        service,
        inputs,
        tracker,
        internal,
        supervisors,
        leader::get,
        new NotificationRules(props(rules)),
        true,
        Duration.ofSeconds(2),
        Duration.ofMinutes(5),
        scheduler);
  }

  @AfterEach
  void tearDown() {
    producer.stop();
  }

  private static InputRequest request(
      final String id, final InputState state, final InputRisk risk, final String... targets) {
    return request("Deploy build 2.4?\nIt goes to production.", id, state, risk, targets);
  }

  private static InputRequest request(
      final String prompt,
      final String id,
      final InputState state,
      final InputRisk risk,
      final String... targets) {
    final InputRequestSpec spec =
        new InputRequestSpec(
            "k-" + id,
            InputKind.APPROVAL,
            prompt,
            Map.of("secret", "never shown"),
            List.of(),
            null,
            risk,
            Audience.CURRENT_TIER,
            Duration.ofMinutes(10),
            TimeoutAction.FAIL,
            null,
            null,
            "TEST");
    return new InputRequest(
        id,
        spec.key(),
        "s1",
        "wf",
        EXEC,
        "node",
        spec,
        state,
        0,
        "default",
        List.of(targets),
        null,
        NOW.plusSeconds(600),
        NOW.plusSeconds(600),
        List.of(),
        NOW,
        NOW,
        List.of());
  }

  private List<Notification> items() {
    return store.find(NotificationQuery.ALL).collectList().block();
  }

  private void handle(final ExecutionEvent event) {
    producer.handle(event).block();
  }

  // ---- input requests ----

  @Test
  void tierOfPeopleRaisesOneActionWithRiskAsSeverityAndNeverTheContext() {
    handle(
        new ExecutionEvent.InputRequested(
            request("r1", InputState.OPEN, InputRisk.HIGH, OPERATOR), NOW));
    handle(
        new ExecutionEvent.InputRequested(
            request("r1", InputState.OPEN, InputRisk.HIGH, OPERATOR), NOW));

    assertThat(items())
        .singleElement()
        .satisfies(n -> {
          assertThat(n.key()).isEqualTo(ITEM);
          assertThat(n.severity()).isEqualTo(NotificationSeverity.ERROR);
          assertThat(n.title()).isEqualTo("Deploy build 2.4?");
          assertThat(n.body()).isEqualTo("It goes to production.");
          assertThat(n.audience()).containsExactly(OPERATOR);
          assertThat(n.inputRequestId()).isEqualTo("r1");
          assertThat(n.toString()).doesNotContain("never shown");
        });
  }

  @Test
  void riskMapsToSeverity() {
    handle(
        new ExecutionEvent.InputRequested(
            request("a", InputState.OPEN, InputRisk.LOW, OPERATOR), NOW));
    handle(
        new ExecutionEvent.InputRequested(
            request("b", InputState.OPEN, InputRisk.MEDIUM, "key:arun"), NOW));

    assertThat(items())
        .extracting(Notification::severity)
        .containsExactlyInAnyOrder(NotificationSeverity.INFO, NotificationSeverity.WARN);
  }

  @Test
  void tierOfSupervisorsOnlyRaisesNothing() {
    handle(
        new ExecutionEvent.InputRequested(
            request("r1", InputState.OPEN, InputRisk.LOW, SUPERVISORS), NOW));

    assertThat(items()).isEmpty();
  }

  @Test
  void mixedTierWaitsTheGraceAndRaisesNothingWhenAnsweredInside() {
    final InputRequest open =
        request("r1", InputState.OPEN, InputRisk.MEDIUM, SUPERVISORS, OPERATOR);
    when(inputs.findById("r1"))
        .thenReturn(
            Mono.just(request("r1", InputState.ANSWERED, InputRisk.MEDIUM, SUPERVISORS, OPERATOR)));

    handle(new ExecutionEvent.InputRequested(open, NOW));
    scheduler.advanceTimeBy(Duration.ofSeconds(1));
    assertThat(items()).isEmpty();
    scheduler.advanceTimeBy(Duration.ofSeconds(2));

    assertThat(items()).isEmpty();
  }

  @Test
  void mixedTierStillOpenAfterTheGraceRaisesExactlyOneItem() {
    final InputRequest open =
        request("r1", InputState.OPEN, InputRisk.MEDIUM, SUPERVISORS, OPERATOR);
    when(inputs.findById("r1")).thenReturn(Mono.just(open));

    handle(new ExecutionEvent.InputRequested(open, NOW));
    assertThat(items()).isEmpty();
    scheduler.advanceTimeBy(Duration.ofSeconds(3));

    assertThat(items()).hasSize(1);
  }

  @Test
  void ifTheTierChangedToSupervisorsOnlyDuringTheGraceNothingIsRaised() {
    final InputRequest open = request("r1", InputState.OPEN, InputRisk.LOW, SUPERVISORS, OPERATOR);
    when(inputs.findById("r1"))
        .thenReturn(Mono.just(request("r1", InputState.ESCALATED, InputRisk.LOW, SUPERVISORS)));

    handle(new ExecutionEvent.InputRequested(open, NOW));
    scheduler.advanceTimeBy(Duration.ofSeconds(3));

    assertThat(items()).isEmpty();
  }

  @Test
  void answerThatOvertakesTheRaiseClosesTheItemToo() {
    final InputRequest open = request("r1", InputState.OPEN, InputRisk.LOW, SUPERVISORS, OPERATOR);
    when(inputs.findById("r1"))
        .thenReturn(
            Mono.just(open),
            Mono.just(request("r1", InputState.ANSWERED, InputRisk.LOW, OPERATOR)));

    handle(new ExecutionEvent.InputRequested(open, NOW));
    scheduler.advanceTimeBy(Duration.ofSeconds(3));

    assertThat(items())
        .singleElement()
        .satisfies(n -> {
          assertThat(n.state()).isEqualTo(Notification.State.RESOLVED);
          assertThat(n.resolution()).isEqualTo("ended: answered");
        });
  }

  @Test
  void failureAfterTheGraceIsLoggedNotRaised() {
    final InputRequest open = request("r1", InputState.OPEN, InputRisk.LOW, SUPERVISORS, OPERATOR);
    when(inputs.findById("r1")).thenReturn(Mono.error(new IllegalStateException("down")));

    handle(new ExecutionEvent.InputRequested(open, NOW));
    scheduler.advanceTimeBy(Duration.ofSeconds(3));

    assertThat(items()).isEmpty();
  }

  @Test
  void serverThatIsNoLongerLeaderRaisesNothingAfterTheGrace() {
    final InputRequest open = request("r1", InputState.OPEN, InputRisk.LOW, SUPERVISORS, OPERATOR);
    when(inputs.findById("r1")).thenReturn(Mono.just(open));

    handle(new ExecutionEvent.InputRequested(open, NOW));
    leader.set(false);
    scheduler.advanceTimeBy(Duration.ofSeconds(3));
    handle(new ExecutionEvent.InputRequested(open, NOW));

    assertThat(items()).isEmpty();
  }

  @Test
  void escalationChangesTheAudienceOfTheSameItemAndAddsTheReason() {
    handle(
        new ExecutionEvent.InputRequested(
            request("r1", InputState.OPEN, InputRisk.LOW, OPERATOR), NOW));

    handle(
        new ExecutionEvent.InputEscalated(
            request("r1", InputState.ESCALATED, InputRisk.LOW, "key:arun"), "no answer", NOW));

    assertThat(items())
        .singleElement()
        .satisfies(n -> {
          assertThat(n.audience()).containsExactly("key:arun");
          assertThat(n.body()).startsWith("Handed up: no answer");
        });
  }

  @Test
  void escalationToSupervisorsOnlyTakesTheItemOffEveryonesList() {
    handle(
        new ExecutionEvent.InputRequested(
            request("r1", InputState.OPEN, InputRisk.LOW, OPERATOR), NOW));

    handle(
        new ExecutionEvent.InputEscalated(
            request("r1", InputState.ESCALATED, InputRisk.LOW, SUPERVISORS), "x", NOW));

    assertThat(items()).singleElement().satisfies(n -> assertThat(n.audience()).isEmpty());
  }

  @Test
  void escalationToPeopleWithNoItemYetRaisesIt() {
    handle(
        new ExecutionEvent.InputEscalated(
            request("r1", InputState.ESCALATED, InputRisk.LOW, OPERATOR), "handed up", NOW));

    assertThat(items()).hasSize(1);
  }

  @Test
  void escalationToSupervisorsOnlyWithNoItemRaisesNothing() {
    handle(
        new ExecutionEvent.InputEscalated(
            request("r1", InputState.ESCALATED, InputRisk.LOW, SUPERVISORS), "x", NOW));

    assertThat(items()).isEmpty();
  }

  @Test
  void answerExpiryAndCancellationResolveTheItem() {
    final String[] ids = {"a", "b", "c", "d", "e"};
    for (final String id : ids) {
      handle(
          new ExecutionEvent.InputRequested(
              request(id, InputState.OPEN, InputRisk.LOW, OPERATOR), NOW));
    }
    handle(answered("a", new Responder(Responder.Type.HUMAN, "arun"), "approve"));
    handle(answered("b", new Responder(Responder.Type.SUPERVISOR, "guard"), "reject"));
    handle(answered("c", new Responder(Responder.Type.EXTERNAL, "bot"), "ok"));
    handle(answered("d", Responder.SYSTEM, "default"));
    handle(new ExecutionEvent.InputExpired(EXEC, "s1", "wf", "e", NOW, "node"));
    handle(new ExecutionEvent.InputCancelled(EXEC, "s1", "wf", "e", "run-ended", NOW, "node"));

    assertThat(items())
        .extracting(Notification::resolution)
        .containsExactlyInAnyOrder(
            "answered by key:arun: approve",
            "answered by supervisor:guard: reject",
            "answered by external:bot: ok",
            "answered by system: default",
            "expired");
    assertThat(items())
        .allSatisfy(n -> assertThat(n.state()).isEqualTo(Notification.State.RESOLVED));
  }

  private static ExecutionEvent answered(
      final String id, final Responder responder, final String decision) {
    return new ExecutionEvent.InputAnswered(EXEC, "s1", "wf", id, responder, decision, NOW, "node");
  }

  @Test
  void cancellationResolvesWithItsReason() {
    handle(
        new ExecutionEvent.InputRequested(
            request("r1", InputState.OPEN, InputRisk.LOW, OPERATOR), NOW));
    handle(new ExecutionEvent.InputCancelled(EXEC, "s1", "wf", "r1", "run-ended", NOW, "node"));

    assertThat(items().getFirst().resolution()).isEqualTo("cancelled: run-ended");
  }

  // ---- runs ----

  private static ExecutionEvent status(final String status) {
    return new ExecutionEvent.WorkflowStatusChanged(EXEC, "s1", "wf", status, NOW);
  }

  @Test
  void failedRootRunRaisesAnErrorForOperatorsByDefault() {
    for (final String status : List.of("FAILURE", "TIMED_OUT", "ABORTED")) {
      handle(new ExecutionEvent.WorkflowStatusChanged("e-" + status, "s1", "wf", status, NOW));
    }

    assertThat(items())
        .hasSize(3)
        .allSatisfy(n -> {
          assertThat(n.category()).isEqualTo("run.failed");
          assertThat(n.severity()).isEqualTo(NotificationSeverity.ERROR);
          assertThat(n.audience()).containsExactly(OPERATOR);
          assertThat(n.title()).isEqualTo("Run failed: wf");
        });
  }

  @Test
  void successRunningAndUnknownStatusesRaiseNothingByDefault() {
    handle(status("SUCCESS"));
    handle(status("RUNNING"));
    handle(status("STOPPED"));
    handle(status("not-a-status"));

    assertThat(items()).isEmpty();
  }

  @Test
  void childRunAndAnInternalSessionRaiseNoRunAlert() {
    when(tracker.lineage(EXEC)).thenReturn(new TaskTrackerService.Lineage("parent", 1));
    handle(status("FAILURE"));
    when(tracker.lineage("e2")).thenReturn(new TaskTrackerService.Lineage("e2", 0));
    when(internal.checkInternal("hidden")).thenReturn(Mono.just(true));
    handle(new ExecutionEvent.WorkflowStatusChanged("e2", "hidden", "wf", "FAILURE", NOW));

    assertThat(items()).isEmpty();
  }

  @Test
  void ruleTurnsOnRunFinishedForOneWorkflowWithItsOwnAudienceAndSeverity() {
    producer.stop();
    producer =
        producer(
            Map.of(
                "done",
                new NotificationRuleSettings(
                    "run.finished",
                    List.of("s*"),
                    List.of("wf"),
                    List.of("key:arun"),
                    NotificationSeverity.WARN,
                    List.of(),
                    Duration.ofMinutes(5))));

    handle(status("SUCCESS"));
    handle(new ExecutionEvent.WorkflowStatusChanged("e9", "s1", "other", "SUCCESS", NOW));
    handle(new ExecutionEvent.WorkflowStatusChanged("e8", "x", "wf", "SUCCESS", NOW));
    handle(new ExecutionEvent.WorkflowStatusChanged("e7", null, "wf", "SUCCESS", NOW));

    assertThat(items())
        .singleElement()
        .satisfies(n -> {
          assertThat(n.category()).isEqualTo("run.finished");
          assertThat(n.severity()).isEqualTo(NotificationSeverity.WARN);
          assertThat(n.audience()).containsExactly("key:arun");
        });
  }

  @Test
  void ruleForRunFailedNarrowsTheDefaultToItsSessions() {
    producer.stop();
    producer =
        producer(
            Map.of(
                "release",
                new NotificationRuleSettings(
                    "run.failed",
                    List.of("release-*"),
                    List.of(),
                    List.of(),
                    null,
                    List.of(),
                    Duration.ZERO)));

    handle(status("FAILURE"));
    handle(new ExecutionEvent.WorkflowStatusChanged("e2", "release-1", "wf", "FAILURE", NOW));

    assertThat(items())
        .singleElement()
        .satisfies(n -> assertThat(n.sessionId()).isEqualTo("release-1"));
  }

  private static ExecutionEvent outcome(
      final String command, final String actor, final boolean applied) {
    return new ExecutionEvent.ControlOutcome(
        EXEC, "s1", "wf", command, actor, applied, "too costly", NOW, null, null);
  }

  @Test
  void supervisorThatPausesOrStopsRunRaisesRunHeldWhenRuleAsksForIt() {
    producer.stop();
    producer =
        producer(
            Map.of(
                "held",
                new NotificationRuleSettings(
                    "run.held", List.of(), List.of(), List.of(), null, List.of(), Duration.ZERO)));
    when(supervisors.find("guard")).thenReturn(Optional.of(mock(BoundSupervisor.class)));
    when(supervisors.find("arun")).thenReturn(Optional.empty());

    handle(outcome("PauseWorkflowCommand", "guard", true));
    handle(outcome("StopWorkflowCommand", "guard", true));
    handle(outcome("PauseWorkflowCommand", "guard", false));
    handle(outcome("ResumeWorkflowCommand", "guard", true));
    handle(outcome("PauseWorkflowCommand", "arun", true));
    handle(outcome("PauseWorkflowCommand", null, true));

    assertThat(items())
        .singleElement()
        .satisfies(n -> {
          assertThat(n.key()).isEqualTo("run.held:e1");
          assertThat(n.severity()).isEqualTo(NotificationSeverity.WARN);
        });
  }

  @Test
  void stopWordsTheTitleAsStopped() {
    producer.stop();
    producer =
        producer(
            Map.of(
                "held",
                new NotificationRuleSettings(
                    "run.held", List.of(), List.of(), List.of(), null, List.of(), Duration.ZERO)));
    when(supervisors.find("guard")).thenReturn(Optional.of(mock(BoundSupervisor.class)));

    handle(outcome("StopWorkflowCommand", "guard", true));

    assertThat(items().getFirst().title()).isEqualTo("Run stopped by supervisor \"guard\"");
  }

  @Test
  void runHeldIsOffWithoutRule() {
    when(supervisors.find("guard")).thenReturn(Optional.of(mock(BoundSupervisor.class)));

    handle(outcome("PauseWorkflowCommand", "guard", true));

    assertThat(items()).isEmpty();
  }

  @Test
  void otherEventsAndStandbyServerRaiseNothing() {
    handle(new ExecutionEvent.EventsMissed(3, NOW));
    leader.set(false);
    handle(status("FAILURE"));

    assertThat(items()).isEmpty();
  }

  @Test
  void failingServiceIsLoggedNotRaised() {
    when(tracker.lineage(EXEC)).thenThrow(new IllegalStateException("boom"));

    handle(status("FAILURE"));

    assertThat(items()).isEmpty();
  }

  // ---- repair and lifecycle ----

  @Test
  void theRepairRaisesTheActionOfAnOpenRequestThatHasNoneAndNoMore() {
    final InputRequest withItem = request("r1", InputState.OPEN, InputRisk.LOW, OPERATOR);
    final InputRequest without = request("r2", InputState.OPEN, InputRisk.LOW, OPERATOR);
    final InputRequest bots = request("r3", InputState.OPEN, InputRisk.LOW, SUPERVISORS);
    when(inputs.listOpen(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Flux.just(withItem, without, bots));
    handle(new ExecutionEvent.InputRequested(withItem, NOW));

    producer.reconcile().block();
    producer.reconcile().block();

    assertThat(items()).extracting(Notification::key).containsExactlyInAnyOrder(ITEM, "input:r2");
  }

  @Test
  void startedProducerReadsTheTapAndRepairsOnStart() {
    when(inputs.listOpen(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Flux.just(request("r0", InputState.OPEN, InputRisk.LOW, OPERATOR)));
    producer.start();
    producer.start();

    feed.publish(
        new ExecutionEvent.InputRequested(
            request("r1", InputState.OPEN, InputRisk.LOW, OPERATOR), NOW));

    assertThat(producer.isRunning()).isTrue();
    await()
        .untilAsserted(() ->
            assertThat(items())
                .extracting(Notification::key)
                .containsExactlyInAnyOrder(ITEM, "input:r0"));
    producer.stop();
    assertThat(producer.isRunning()).isFalse();
  }

  @Test
  void standbyServerDoesNotRepairOnStartAndTheRepairFailureIsLogged() {
    leader.set(false);
    producer.start();
    producer.stop();
    leader.set(true);
    when(inputs.listOpen(org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any()))
        .thenReturn(Flux.error(new IllegalStateException("down")));

    producer.start();

    assertThat(producer.isRunning()).isTrue();
  }

  @Test
  void disabledProducerDoesNotStart() {
    final NotificationProducer off =
        new NotificationProducer(
            feed,
            service,
            inputs,
            tracker,
            internal,
            supervisors,
            leader::get,
            new NotificationRules(props(Map.of())),
            false,
            Duration.ofSeconds(2),
            Duration.ofMinutes(5),
            scheduler);

    off.start();

    assertThat(off.isRunning()).isFalse();
  }

  @Test
  void theSpringConstructorReadsTheSettings() {
    final NotificationProducer spring =
        new NotificationProducer(
            feed,
            service,
            inputs,
            tracker,
            internal,
            supervisors,
            leader::get,
            new NotificationRules(props(Map.of())),
            props(Map.of()));

    assertThat(spring.isRunning()).isFalse();
  }

  @Test
  void zeroGraceRaisesAtOnceEvenWithSupervisors() {
    final NotificationProducer fast =
        new NotificationProducer(
            feed,
            service,
            inputs,
            tracker,
            internal,
            supervisors,
            leader::get,
            new NotificationRules(props(Map.of())),
            true,
            Duration.ZERO,
            Duration.ofMinutes(5),
            scheduler);

    fast.handle(
            new ExecutionEvent.InputRequested(
                request("r1", InputState.OPEN, InputRisk.LOW, SUPERVISORS, OPERATOR), NOW))
        .block();

    assertThat(items()).hasSize(1);
  }

  @Test
  void oneLinePromptHasNoBodyUnlessItIsHandedUp() {
    handle(
        new ExecutionEvent.InputRequested(
            request("Deploy?", "r1", InputState.OPEN, InputRisk.LOW, OPERATOR), NOW));
    handle(
        new ExecutionEvent.InputRequested(
            request("Deploy?", "r2", InputState.OPEN, InputRisk.LOW, OPERATOR), NOW));
    handle(
        new ExecutionEvent.InputEscalated(
            request("Deploy?", "r2", InputState.ESCALATED, InputRisk.LOW, "key:arun"),
            "slow",
            NOW));

    assertThat(items())
        .extracting(Notification::key, Notification::body)
        .containsExactlyInAnyOrder(
            org.assertj.core.api.Assertions.tuple("input:r1", null),
            org.assertj.core.api.Assertions.tuple("input:r2", "Handed up: slow"));
  }
}
