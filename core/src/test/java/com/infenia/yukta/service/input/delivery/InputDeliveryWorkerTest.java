// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input.delivery;

import static com.infenia.yukta.service.input.InputTestSupport.limits;
import static com.infenia.yukta.service.input.InputTestSupport.who;
import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputResponder;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.TimeoutAction;
import com.infenia.yukta.service.input.InMemoryInputRequestStore;
import com.infenia.yukta.service.input.InputBroker;
import com.infenia.yukta.service.input.InputTestSupport.MutableClock;
import com.infenia.yukta.service.input.route.FinalAction;
import com.infenia.yukta.service.input.route.InputRoute;
import com.infenia.yukta.service.input.route.Mode;
import com.infenia.yukta.service.input.route.ResponderType;
import com.infenia.yukta.service.input.route.RouteResolver;
import com.infenia.yukta.service.input.route.Tier;
import com.infenia.yukta.service.input.token.AnswerTokens;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import reactor.core.publisher.Mono;
import reactor.test.scheduler.VirtualTimeScheduler;

/** Deliveries: queued when a request enters a tier, sent with a token, retried, then given up. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.CouplingBetweenObjects",
  "PMD.ExcessiveImports",
  "PMD.TooManyMethods"
})
class InputDeliveryWorkerTest {

  private static final Duration INTERVAL = Duration.ofSeconds(1);

  private final MutableClock clock = new MutableClock();
  private final InMemoryInputRequestStore store =
      org.mockito.Mockito.spy(new InMemoryInputRequestStore(100));
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final AnswerTokens tokens = new AnswerTokens("worker-secret", clock);
  private final List<Map<String, Object>> delivered = new CopyOnWriteArrayList<>();
  private final AtomicBoolean fail = new AtomicBoolean();
  private final AtomicBoolean leading = new AtomicBoolean(true);
  private final Map<String, InputRoute> routes = new java.util.concurrent.ConcurrentHashMap<>();
  private final List<InputResponder> responders = new ArrayList<>();
  private InputBroker broker;
  private InputDeliveryWorker worker;

  private static Tier hook(final Duration timeout) {
    return new Tier(
        "hook",
        List.of("external:webhook:ops"),
        timeout,
        Mode.FIRST,
        0,
        0,
        ResponderType.HUMAN,
        Map.of("x", 1));
  }

  @SuppressWarnings("unchecked")
  private void setUp(final Tier... tiers) {
    routes.put("ext", new InputRoute("ext", List.of(tiers), FinalAction.FAIL));
    responders.add(
        new InputResponder() {
          @Override
          public String type() {
            return "webhook";
          }

          @Override
          public Mono<Void> deliver(final InputRequest request, final Map<String, Object> tier) {
            delivered.add(tier);
            return fail.get() ? Mono.error(new IllegalStateException("down")) : Mono.empty();
          }
        });
    final RouteResolver resolver = new RouteResolver(routes, null);
    broker =
        new InputBroker(
            store,
            new ExecutionEventFeed(),
            new InMemoryExecutionLogStore(),
            meters,
            new InputRedactor(List.of()),
            limits(),
            clock,
            resolver,
            null);
    final ObjectProvider<InputResponder> provider = org.mockito.Mockito.mock(ObjectProvider.class);
    org.mockito.Mockito.when(provider.stream()).thenAnswer(_ -> responders.stream());
    worker =
        new InputDeliveryWorker(
            store,
            resolver,
            tokens,
            broker,
            provider,
            leading::get,
            meters,
            clock,
            INTERVAL,
            VirtualTimeScheduler.create());
  }

  private InputRequest ask(final String run) {
    final InputRequestSpec spec =
        new InputRequestSpec(
            "key-" + run,
            InputKind.APPROVAL,
            "Deploy?",
            Map.of(),
            List.of(),
            null,
            InputRisk.LOW,
            Audience.CURRENT_TIER,
            Duration.ofHours(2),
            TimeoutAction.FAIL,
            null,
            "ext",
            "TEST");
    broker.ask(who(run, "node"), spec).subscribe(r -> {}, e -> {});
    return store.listOpen(null, run).blockFirst();
  }

  private List<InputDelivery> due() {
    return store.dueDeliveries(clock.instant().plus(Duration.ofDays(1))).collectList().block();
  }

  @Test
  void requestEnteringAnExternalTierIsQueuedAndDeliveredWithToken() {
    setUp(hook(Duration.ofMinutes(10)));
    final InputRequest request = ask("e1");
    assertThat(store.dueDeliveries(clock.instant()).collectList().block()).hasSize(1);

    worker.deliverDue().block();

    assertThat(delivered).hasSize(1);
    final Map<String, Object> settings = delivered.getFirst();
    assertThat(settings).containsEntry("x", 1).containsEntry("name", "ops");
    assertThat(
            tokens.verify(
                request.id(),
                "hook",
                (Long) settings.get("answerExpires"),
                (String) settings.get("answerToken")))
        .isTrue();
    assertThat((Long) settings.get("answerExpires"))
        .isEqualTo(clock.instant().plus(Duration.ofMinutes(10)).getEpochSecond());
    assertThat(due()).isEmpty();
    assertThat(
            meters
                .counter(
                    "yukta.input.delivery", "responder", "external:webhook:ops", "outcome", "sent")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void tokenLivesUntilTheDeadlineOfTheRequestOrAnHourWhenThereIsNone() {
    setUp(hook(null));
    final InputRequest request = ask("e1");
    worker.deliverDue().block();
    assertThat(delivered.getFirst().get("answerExpires"))
        .isEqualTo(java.util.Objects.requireNonNull(request.deadline()).getEpochSecond());

    final InputRequest bare =
        new InputRequest(
            "bare",
            "kb",
            "session",
            "workflow",
            "e2",
            "node",
            request.spec(),
            InputState.OPEN,
            0,
            "hook",
            List.of("external:webhook:ops"),
            null,
            null,
            null,
            List.of(),
            clock.instant(),
            clock.instant());
    store.createIfAbsent(bare).block();
    store
        .enqueueDelivery(
            new InputDelivery(
                "d-bare",
                "bare",
                "hook",
                "external:webhook:ops",
                InputDelivery.State.PENDING,
                0,
                clock.instant(),
                null))
        .block();
    delivered.clear();

    worker.deliverDue().block();

    assertThat(delivered.getFirst().get("answerExpires"))
        .isEqualTo(clock.instant().plus(Duration.ofHours(1)).getEpochSecond());
  }

  @Test
  void failedDeliveryIsTriedAgainWithGrowingWaitsAndThenGivenUp() {
    setUp(hook(null));
    ask("e1");
    fail.set(true);

    worker.deliverDue().block();
    final InputDelivery first = due().getFirst();
    assertThat(first.attempts()).isEqualTo(1);
    assertThat(first.nextAttempt()).isEqualTo(clock.instant().plusSeconds(1));
    assertThat(first.lastError()).isEqualTo("IllegalStateException");

    // not due yet: nothing is tried
    worker.deliverDue().block();
    assertThat(delivered).hasSize(1);

    for (int i = 1; i < InputDeliveryWorker.MAX_ATTEMPTS; i++) {
      clock.advance(Duration.ofMinutes(10));
      worker.deliverDue().block();
    }

    assertThat(delivered).hasSize(InputDeliveryWorker.MAX_ATTEMPTS);
    assertThat(due()).isEmpty();
    clock.advance(Duration.ofDays(1));
    worker.deliverDue().block();
    assertThat(delivered).hasSize(InputDeliveryWorker.MAX_ATTEMPTS);
    assertThat(
            meters
                .counter(
                    "yukta.input.delivery",
                    "responder",
                    "external:webhook:ops",
                    "outcome",
                    "failed")
                .count())
        .isEqualTo(1.0);
    assertThat(
            meters
                .counter(
                    "yukta.input.delivery", "responder", "external:webhook:ops", "outcome", "retry")
                .count())
        .isEqualTo(InputDeliveryWorker.MAX_ATTEMPTS - 1.0);
  }

  @Test
  void theWaitDoublesFromOneSecondUpToFiveMinutes() {
    assertThat(InputDeliveryWorker.backoff(0)).isEqualTo(Duration.ofSeconds(1));
    assertThat(InputDeliveryWorker.backoff(1)).isEqualTo(Duration.ofSeconds(2));
    assertThat(InputDeliveryWorker.backoff(3)).isEqualTo(Duration.ofSeconds(8));
    assertThat(InputDeliveryWorker.backoff(9)).isEqualTo(Duration.ofMinutes(5));
    assertThat(InputDeliveryWorker.backoff(40)).isEqualTo(Duration.ofMinutes(5));
  }

  @Test
  void requestThatEndedOrMovedOnIsNotDelivered() {
    setUp(hook(null));
    final InputRequest ended = ask("e1");
    broker.cancel(ended.id(), "gone").block();
    store
        .enqueueDelivery(
            new InputDelivery(
                "d-missing",
                "missing",
                "hook",
                "external:webhook:ops",
                InputDelivery.State.PENDING,
                0,
                clock.instant(),
                null))
        .block();
    final InputRequest moved = ask("e2");
    store
        .enqueueDelivery(
            new InputDelivery(
                "d-old",
                moved.id(),
                "old-tier",
                "external:webhook:ops",
                InputDelivery.State.PENDING,
                0,
                clock.instant(),
                null))
        .block();

    worker.deliverDue().block();

    // only the live delivery of the request in its own tier was sent
    assertThat(delivered).hasSize(1);
    assertThat(due()).isEmpty();
  }

  @Test
  void responderThatIsNotInstalledFailsTheDeliveryUntilItIsGivenUp() {
    setUp(hook(null));
    responders.clear();
    ask("e1");

    worker.deliverDue().block();

    assertThat(due().getFirst().lastError()).isEqualTo("IllegalStateException");
    assertThat(delivered).isEmpty();
  }

  @Test
  void theSpringConstructorBuildsWorkerThatIsNotRunningYet() {
    setUp(hook(null));
    final InputDeliveryWorker spring =
        new InputDeliveryWorker(
            store,
            new RouteResolver(routes, null),
            tokens,
            broker,
            providerOf(),
            leading::get,
            meters,
            com.infenia.yukta.testsupport.YuktaSettings.bind(Map.of()));

    assertThat(spring.isRunning()).isFalse();
  }

  @Test
  void storeFailureOnOneDeliveryDoesNotStopTheOthers() {
    setUp(hook(null));
    ask("e1");
    store
        .enqueueDelivery(
            new InputDelivery(
                "d-boom",
                "boom",
                "hook",
                "external:webhook:ops",
                InputDelivery.State.PENDING,
                0,
                clock.instant().minusSeconds(5),
                null))
        .block();
    org.mockito.Mockito.doReturn(Mono.error(new IllegalStateException("db")))
        .when(store)
        .findById("boom");

    worker.deliverDue().block();

    assertThat(delivered).hasSize(1);
  }

  @Test
  void selectorWithoutNameStillDeliversToTheResponderType() {
    setUp(hook(null));
    final InputRequest request = ask("e1");
    worker.deliverDue().block();
    delivered.clear();
    store
        .enqueueDelivery(
            new InputDelivery(
                "d-bare",
                request.id(),
                "hook",
                "external:webhook",
                InputDelivery.State.PENDING,
                0,
                clock.instant(),
                null))
        .block();

    worker.deliverDue().block();

    assertThat(delivered).hasSize(1);
    assertThat(delivered.getFirst()).containsEntry("name", null);
  }

  @Test
  void onlyTheLeaderDeliversWhileTheWorkerRuns() {
    setUp(hook(null));
    final VirtualTimeScheduler scheduler = VirtualTimeScheduler.create();
    final InputDeliveryWorker timed =
        new InputDeliveryWorker(
            store,
            new RouteResolver(routes, null),
            tokens,
            broker,
            providerOf(),
            leading::get,
            meters,
            clock,
            INTERVAL,
            scheduler);
    ask("e1");
    leading.set(false);

    timed.start();
    timed.start();
    assertThat(timed.isRunning()).isTrue();
    scheduler.advanceTimeBy(Duration.ofSeconds(3));
    assertThat(delivered).isEmpty();

    leading.set(true);
    scheduler.advanceTimeBy(Duration.ofSeconds(2));
    assertThat(delivered).hasSize(1);

    timed.stop();
    timed.stop();
    assertThat(timed.isRunning()).isFalse();
  }

  @SuppressWarnings("unchecked")
  private ObjectProvider<InputResponder> providerOf() {
    final ObjectProvider<InputResponder> provider = org.mockito.Mockito.mock(ObjectProvider.class);
    org.mockito.Mockito.when(provider.stream()).thenAnswer(_ -> responders.stream());
    return provider;
  }

  @Test
  void requestHandedUpToAnExternalTierIsQueuedThen() {
    final Tier auto =
        new Tier(
            "auto",
            List.of("supervisor:*"),
            Duration.ofSeconds(30),
            Mode.FIRST,
            0,
            0,
            ResponderType.AUTO,
            null);
    setUp(auto, hook(null));
    // a supervisor is attached so the first tier is not skipped
    final InputBroker withSupervisor = brokerWithSupervisor();
    withSupervisor.ask(who("e1", "node"), spec("ext")).subscribe(r -> {}, e -> {});
    assertThat(due()).isEmpty();

    clock.advance(Duration.ofSeconds(31));
    withSupervisor.sweep().block();

    assertThat(due()).hasSize(1);
    assertThat(due().getFirst().tier()).isEqualTo("hook");
  }

  private InputBroker brokerWithSupervisor() {
    @SuppressWarnings("unchecked")
    final ObjectProvider<com.infenia.yukta.service.input.EligibleSupervisors> directory =
        org.mockito.Mockito.mock(ObjectProvider.class);
    org.mockito.Mockito.when(directory.getIfAvailable())
        .thenReturn(
            () ->
                List.of(
                    new com.infenia.yukta.service.input.EligibleSupervisors.Candidate(
                        "watch",
                        com.infenia.yukta.plugin.control.SupervisorScope.parse(
                            "workflow:session/workflow"))));
    return new InputBroker(
        store,
        new ExecutionEventFeed(),
        new InMemoryExecutionLogStore(),
        meters,
        new InputRedactor(List.of()),
        limits(),
        clock,
        new RouteResolver(routes, null),
        directory);
  }

  private static InputRequestSpec spec(final String route) {
    return new InputRequestSpec(
        "key-x",
        InputKind.APPROVAL,
        "Deploy?",
        Map.of(),
        List.of(),
        null,
        InputRisk.LOW,
        Audience.CURRENT_TIER,
        Duration.ofHours(2),
        TimeoutAction.FAIL,
        null,
        route,
        "TEST");
  }

  @Test
  void deliveriesKeepTheirPartsAndCountTheirTries() {
    final Instant at = Instant.parse("2026-11-03T10:00:00Z");
    final InputDelivery pending =
        new InputDelivery(
            "d", "r", "t", "external:webhook:x", InputDelivery.State.PENDING, 0, at, null);

    final InputDelivery retry = pending.failed("boom", at.plusSeconds(1), false);
    final InputDelivery gaveUp = retry.failed("boom", at.plusSeconds(2), true);
    final InputDelivery done = pending.sent();

    assertThat(retry.state()).isEqualTo(InputDelivery.State.PENDING);
    assertThat(retry.attempts()).isEqualTo(1);
    assertThat(gaveUp.state()).isEqualTo(InputDelivery.State.FAILED);
    assertThat(gaveUp.attempts()).isEqualTo(2);
    assertThat(done.state()).isEqualTo(InputDelivery.State.SENT);
    assertThat(done.lastError()).isNull();
  }
}
