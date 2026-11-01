// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import static com.infenia.yukta.service.input.InputTestSupport.limits;
import static com.infenia.yukta.service.input.InputTestSupport.spec;
import static com.infenia.yukta.service.input.InputTestSupport.who;
import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.config.YuktaProperties;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputCancelledException;
import com.infenia.yukta.plugin.input.InputExpiredException;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputLimitException;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.plugin.input.TimeoutAction;
import com.infenia.yukta.service.input.InputTestSupport.MutableClock;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.log.InMemoryExecutionLogStore;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CopyOnWriteArrayList;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.Disposable;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;
import reactor.test.StepVerifier;

@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.ExcessiveImports", "PMD.AvoidDuplicateLiterals"})
class InputBrokerTest {

  private final InMemoryInputRequestStore store = new InMemoryInputRequestStore(100);
  private final ExecutionEventFeed feed = new ExecutionEventFeed();
  private final InMemoryExecutionLogStore log = new InMemoryExecutionLogStore();
  private final SimpleMeterRegistry meters = new SimpleMeterRegistry();
  private final MutableClock clock = new MutableClock();
  private final List<ExecutionEvent> events = new CopyOnWriteArrayList<>();
  private final InputBroker inputBroker = broker(limits());

  private InputBroker broker(final YuktaProperties.Input limits) {
    feed.events().subscribe(events::add);
    return new InputBroker(
        store, feed, log, meters, new InputRedactor(List.of("note")), limits, clock);
  }

  private InputRequest onlyOpen() {
    final List<InputRequest> open =
        inputBroker.listOpen(null, null).collectList().blockOptional().orElseThrow();
    assertThat(open).hasSize(1);
    return open.getFirst();
  }

  private static InputResponse answer(final InputRequest request, final String by) {
    return new InputResponse(
        request.id(),
        "go",
        "text from " + by,
        Map.of(),
        new Responder(Responder.Type.HUMAN, by),
        null,
        null,
        java.time.Instant.parse("2026-11-02T10:00:01Z"));
  }

  @Test
  void testPluginAsksAndIsCompletedByAnAnswer() {
    StepVerifier.create(inputBroker.ask(who("e1", "n1"), spec("k1")))
        .then(
            () -> {
              final InputRequest request = onlyOpen();
              assertThat(request.state()).isEqualTo(InputState.OPEN);
              inputBroker.answer(request.id(), null, answer(request, "ann")).block();
            })
        .assertNext(response -> assertThat(response.decision()).isEqualTo("go"))
        .verifyComplete();

    assertThat(inputBroker.listOpen(null, null).collectList().block()).isEmpty();
  }

  @Test
  void anEarlierAnswerForTheSameKeyIsReturnedAtOnceWithoutNewRequest() {
    StepVerifier.create(inputBroker.ask(who("e1", "n1"), spec("k1")))
        .then(
            () -> {
              final InputRequest request = onlyOpen();
              inputBroker.answer(request.id(), null, answer(request, "ann")).block();
            })
        .expectNextCount(1)
        .verifyComplete();

    // the run restarts and asks again, as another execution
    StepVerifier.create(inputBroker.ask(who("e2", "n1"), spec("k1")))
        .assertNext(response -> assertThat(response.responder().id()).isEqualTo("ann"))
        .verifyComplete();
    assertThat(inputBroker.listOpen(null, null).collectList().block()).isEmpty();
  }

  @Test
  void askersWithTheSameKeyShareOneRequestAndGetTheSameAnswer() {
    final List<InputResponse> first = new ArrayList<>();
    final List<InputResponse> second = new ArrayList<>();
    inputBroker.ask(who("e1", "n1"), spec("k1")).subscribe(first::add);
    inputBroker.ask(who("e1", "n1"), spec("k1")).subscribe(second::add);

    final InputRequest request = onlyOpen();
    inputBroker.answer(request.id(), null, answer(request, "ann")).block();

    assertThat(first).hasSize(1);
    assertThat(second).containsExactlyElementsOf(first);
    assertThat(events.stream().filter(ExecutionEvent.InputRequested.class::isInstance)).hasSize(1);
  }

  @Test
  void parallelAsksWithOneKeyCreateOneRequest() {
    Flux.range(0, 20)
        .parallel()
        .runOn(Schedulers.parallel())
        .flatMap(
            i -> inputBroker.ask(who("e1", "n1"), spec("same")).onErrorResume(e -> Mono.empty()))
        .sequential()
        .subscribe();

    org.awaitility.Awaitility.await()
        .atMost(Duration.ofSeconds(5))
        .untilAsserted(
            () -> assertThat(inputBroker.listOpen(null, null).collectList().block()).hasSize(1));
    final InputRequest request = onlyOpen();
    inputBroker.answer(request.id(), null, answer(request, "ann")).block();
    assertThat(inputBroker.listOpen(null, null).collectList().block()).isEmpty();
  }

  @Test
  void fiftyAnswersAtOnceProduceOneWinner() {
    inputBroker.ask(who("e1", "n1"), spec("k1")).subscribe();
    final InputRequest request = onlyOpen();

    final List<InputRequest> results =
        Flux.range(0, 50)
            .parallel()
            .runOn(Schedulers.parallel())
            .flatMap(i -> inputBroker.answer(request.id(), null, answer(request, "r" + i)))
            .sequential()
            .collectList()
            .blockOptional()
            .orElseThrow();

    assertThat(results).hasSize(50);
    assertThat(results).allMatch(r -> r.state() == InputState.ANSWERED);
    assertThat(results.stream().map(InputRequest::version).distinct()).hasSize(1);
    assertThat(events.stream().filter(ExecutionEvent.InputAnswered.class::isInstance)).hasSize(1);
    assertThat(
            meters
                .counter(
                    "yukta.input.requests", "kind", "TEXT", "risk", "LOW", "outcome", "answered")
                .count())
        .isEqualTo(1.0);
    final InputResponse stored = store.findResponse(request.id()).blockOptional().orElseThrow();
    final ExecutionEvent.InputAnswered event =
        (ExecutionEvent.InputAnswered)
            events.stream()
                .filter(ExecutionEvent.InputAnswered.class::isInstance)
                .findFirst()
                .get();
    assertThat(stored.responder()).isEqualTo(event.responder());
  }

  @Test
  void versionThatMovedLeavesTheRequestAsItIs() {
    inputBroker.ask(who("e1", "n1"), spec("k1")).subscribe();
    final InputRequest request = onlyOpen();

    final InputRequest result =
        inputBroker.answer(request.id(), request.version() + 7, answer(request, "ann")).block();

    assertThat(result.state()).isEqualTo(InputState.OPEN);
    assertThat(store.findResponse(request.id()).block()).isNull();
  }

  @Test
  void anAnswerForAnotherRequestIsRefused() {
    inputBroker.ask(who("e1", "n1"), spec("k1")).subscribe();
    final InputRequest request = onlyOpen();
    final InputResponse other =
        new InputResponse(
            "other", "go", null, Map.of(), Responder.SYSTEM, null, null, clock.instant());

    StepVerifier.create(inputBroker.answer(request.id(), null, other))
        .expectError(IllegalArgumentException.class)
        .verify();
  }

  @Test
  void runMayHaveOnlyFewOpenRequests() {
    for (int i = 0; i < 5; i++) {
      inputBroker.ask(who("e1", "n" + i), spec("k" + i)).subscribe();
    }

    StepVerifier.create(inputBroker.ask(who("e1", "n9"), spec("k9")))
        .expectError(InputLimitException.class)
        .verify();
    assertThat(meters.counter("yukta.input.denied", "rule", "open-per-execution").count())
        .isEqualTo(1.0);
    // another run of the same session is not held back
    inputBroker.ask(who("e2", "n1"), spec("other")).subscribe();
    assertThat(inputBroker.listOpen(null, null).collectList().block()).hasSize(6);
  }

  @Test
  void sessionMayHaveOnlyFewOpenRequests() {
    final InputBroker small =
        broker(
            new YuktaProperties.Input(
                Duration.ofHours(1),
                Duration.ofHours(24),
                5,
                2,
                100,
                Duration.ofSeconds(1),
                List.of()));
    small.ask(who("e1", "n1"), spec("k1")).subscribe();
    small.ask(who("e2", "n1"), spec("k2")).subscribe();

    StepVerifier.create(small.ask(who("e3", "n1"), spec("k3")))
        .expectError(InputLimitException.class)
        .verify();
    assertThat(meters.counter("yukta.input.denied", "rule", "open-per-session").count())
        .isEqualTo(1.0);
  }

  @Test
  void ttlAboveTheMaximumIsRefused() {
    StepVerifier.create(
            inputBroker.ask(
                who("e1", "n1"), spec("k1", Duration.ofHours(25), TimeoutAction.FAIL, null)))
        .expectError(InputLimitException.class)
        .verify();
    assertThat(meters.counter("yukta.input.denied", "rule", "ttl").count()).isEqualTo(1.0);
  }

  @Test
  void cancellingTheSubscriptionCancelsTheRequest() {
    final Disposable asker = inputBroker.ask(who("e1", "n1"), spec("k1")).subscribe();
    final InputRequest request = onlyOpen();

    asker.dispose();

    assertThat(store.findById(request.id()).blockOptional().orElseThrow().state())
        .isEqualTo(InputState.CANCELLED);
    assertThat(events.stream().filter(ExecutionEvent.InputCancelled.class::isInstance))
        .singleElement()
        .satisfies(
            e ->
                assertThat(((ExecutionEvent.InputCancelled) e).reason())
                    .isEqualTo("node-cancelled"));
  }

  @Test
  void theRequestSurvivesWhileAnotherCallerStillWaits() {
    final Disposable first = inputBroker.ask(who("e1", "n1"), spec("k1")).subscribe();
    inputBroker.ask(who("e1", "n1"), spec("k1")).subscribe();
    final InputRequest request = onlyOpen();

    first.dispose();

    assertThat(store.findById(request.id()).blockOptional().orElseThrow().state())
        .isEqualTo(InputState.OPEN);
  }

  @Test
  void theEndOfRunCancelsItsOpenRequestsAndTheAskerFails() {
    final List<Throwable> failures = new ArrayList<>();
    inputBroker.ask(who("e1", "n1"), spec("k1")).subscribe(r -> {}, failures::add);
    inputBroker.ask(who("e2", "n1"), spec("k2")).subscribe();

    inputBroker.cancelExecution("e1", "run-ended").block();

    assertThat(failures).singleElement().isInstanceOf(InputCancelledException.class);
    assertThat(inputBroker.listOpen(null, null).collectList().block())
        .singleElement()
        .satisfies(r -> assertThat(r.executionId()).isEqualTo("e2"));
    assertThat(events.stream().filter(ExecutionEvent.InputCancelled.class::isInstance)).hasSize(1);
  }

  @Test
  void requestPastItsDeadlineFailsTheAsker() {
    final List<Throwable> failures = new ArrayList<>();
    inputBroker
        .ask(who("e1", "n1"), spec("k1", Duration.ofSeconds(10), TimeoutAction.FAIL, null))
        .subscribe(r -> {}, failures::add);

    clock.advance(Duration.ofSeconds(9));
    inputBroker.sweep().block();
    assertThat(failures).isEmpty();

    clock.advance(Duration.ofSeconds(2));
    inputBroker.sweep().block();
    assertThat(failures).singleElement().isInstanceOf(InputExpiredException.class);
    assertThat(events.stream().filter(ExecutionEvent.InputExpired.class::isInstance)).hasSize(1);
  }

  @Test
  void requestWithDefaultIsAnsweredBySystemAtItsDeadline() {
    final List<InputResponse> answers = new ArrayList<>();
    inputBroker
        .ask(
            who("e1", "n1"),
            spec("k1", Duration.ofSeconds(10), TimeoutAction.USE_DEFAULT, "reject"))
        .subscribe(answers::add);

    clock.advance(Duration.ofSeconds(11));
    inputBroker.sweep().block();

    assertThat(answers)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.decision()).isEqualTo("reject");
              assertThat(r.responder()).isEqualTo(Responder.SYSTEM);
            });
    assertThat(
            meters
                .counter(
                    "yukta.input.requests",
                    "kind",
                    "APPROVAL",
                    "risk",
                    "LOW",
                    "outcome",
                    "defaulted")
                .count())
        .isEqualTo(1.0);
  }

  @Test
  void escalateOnTimeoutFailsLikeFailUntilRoutesExist() {
    final List<Throwable> failures = new ArrayList<>();
    inputBroker
        .ask(who("e1", "n1"), spec("k1", Duration.ofSeconds(10), TimeoutAction.ESCALATE, null))
        .subscribe(r -> {}, failures::add);

    clock.advance(Duration.ofSeconds(11));
    inputBroker.sweep().block();

    assertThat(failures).singleElement().isInstanceOf(InputExpiredException.class);
  }

  @Test
  void escalatingRecordsTheAdviceAndKeepsTheRequestAnswerable() {
    final List<InputResponse> answers = new ArrayList<>();
    inputBroker.ask(who("e1", "n1"), spec("k1")).subscribe(answers::add);
    final InputRequest request = onlyOpen();

    final InputRequest escalated =
        inputBroker.escalate(request.id(), "unsure", "check with token=abcd1234secret").block();

    assertThat(escalated.state()).isEqualTo(InputState.ESCALATED);
    assertThat(escalated.advice()).doesNotContain("abcd1234secret");
    assertThat(events.stream().filter(ExecutionEvent.InputEscalated.class::isInstance)).hasSize(1);
    // a second escalation changes nothing
    assertThat(
            inputBroker
                .escalate(request.id(), "again", null)
                .blockOptional()
                .orElseThrow()
                .version())
        .isEqualTo(escalated.version());

    inputBroker.answer(request.id(), null, answer(request, "ann")).block();
    assertThat(answers).hasSize(1);
  }

  @Test
  void everyStateAcceptsOnlyTheMovesTheStateMachineAllows() {
    // from OPEN: all four moves work; from a terminal state none does
    for (final InputState start : InputState.values()) {
      for (final String move : List.of("answer", "escalate", "cancel", "expire")) {
        final InputRequest request = reach(start, start + "-" + move);
        final long before = store.findById(request.id()).blockOptional().orElseThrow().version();

        apply(move, request);

        final InputRequest after = store.findById(request.id()).blockOptional().orElseThrow();
        final InputState expected = expected(start, move);
        assertThat(after.state()).as(start + " then " + move).isEqualTo(expected);
        assertThat(after.version() > before)
            .as(start + " then " + move + " moved")
            .isEqualTo(expected != start);
      }
    }
  }

  private static InputState expected(final InputState start, final String move) {
    if (start.isTerminal() || (start == InputState.ESCALATED && "escalate".equals(move))) {
      return start;
    }
    return switch (move) {
      case "answer" -> InputState.ANSWERED;
      case "escalate" -> InputState.ESCALATED;
      case "cancel" -> InputState.CANCELLED;
      default -> InputState.EXPIRED;
    };
  }

  private InputRequest reach(final InputState state, final String key) {
    inputBroker
        .ask(who("e-" + key, "n"), spec(key, Duration.ofSeconds(10), TimeoutAction.FAIL, null))
        .subscribe(r -> {}, e -> {});
    final InputRequest request =
        inputBroker
            .listOpen(null, "e-" + key)
            .collectList()
            .blockOptional()
            .orElseThrow()
            .getFirst();
    switch (state) {
      case ESCALATED -> inputBroker.escalate(request.id(), "r", null).block();
      case ANSWERED -> apply("answer", request);
      case CANCELLED -> apply("cancel", request);
      case EXPIRED -> apply("expire", request);
      default -> {
        // OPEN
      }
    }
    return request;
  }

  private void apply(final String move, final InputRequest request) {
    switch (move) {
      case "answer" -> inputBroker.answer(request.id(), null, answer(request, "ann")).block();
      case "escalate" -> inputBroker.escalate(request.id(), "r", null).block();
      case "cancel" -> inputBroker.cancel(request.id(), "test").block();
      default -> {
        final MutableClock later = clock;
        later.advance(Duration.ofSeconds(11));
        inputBroker.sweep().block();
      }
    }
  }

  @Test
  void secretsNeverReachTheStoreTheEventsOrTheLog() {
    final InputRequestSpec spec =
        new InputRequestSpec(
            "k1",
            InputKind.TEXT,
            "Use Bearer abcdefghijklmnop and ${env.DB_PASS} and password=hunter2 ok?",
            Map.of("note", "internal-only", "nested", Map.of("api_key", "sk-secretsecretsecret1")),
            List.of(),
            null,
            InputRisk.LOW,
            Audience.CURRENT_TIER,
            Duration.ofMinutes(5),
            TimeoutAction.FAIL,
            null,
            null,
            "TEST");
    inputBroker.ask(who("e1", "n1"), spec).subscribe();
    final InputRequest request = onlyOpen();
    inputBroker.answer(request.id(), null, answer(request, "ann")).block();

    final String everything =
        store.findById(request.id()).blockOptional().orElseThrow().toString()
            + events
            + log.readAll().stream().map(ExecutionLogEvent::toString).toList();
    assertThat(everything)
        .doesNotContain("abcdefghijklmnop", "DB_PASS", "hunter2", "internal-only", "sk-secret");
    assertThat(store.findById(request.id()).blockOptional().orElseThrow().spec().prompt())
        .contains("[REDACTED]");
  }

  @Test
  void theLogGetsOneInputEntryPerChangeWithoutTheQuestionOrTheAnswerText() {
    inputBroker.ask(who("e1", "n1"), spec("k1")).subscribe();
    final InputRequest request = onlyOpen();
    inputBroker.answer(request.id(), null, answer(request, "ann")).block();

    final List<ExecutionLogEvent> entries =
        log.readAll().stream().filter(e -> e.type() == ExecutionLogEvent.Type.INPUT).toList();
    assertThat(entries)
        .extracting(e -> Objects.requireNonNull(e.payload()).get(ExecutionLogEvent.INPUT_EVENT_KEY))
        .containsExactly("INPUT_REQUESTED", "INPUT_ANSWERED");
    assertThat(entries.toString()).doesNotContain("Which way?", "text from ann");
  }

  @Test
  void theOpenGaugeCountsOpenRequests() {
    inputBroker.ask(who("e1", "n1"), spec("k1")).subscribe();
    assertThat(meters.get("yukta.input.open").gauge().value()).isEqualTo(1.0);

    inputBroker.cancelExecution("e1", "run-ended").block();

    assertThat(meters.get("yukta.input.open").gauge().value()).isZero();
    assertThat(meters.get("yukta.input.wait").timer().count()).isEqualTo(1);
  }
}
