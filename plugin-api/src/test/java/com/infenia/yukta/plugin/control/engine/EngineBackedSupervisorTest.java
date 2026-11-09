// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.DelegationKind;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorScope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeoutException;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

@SuppressWarnings({
  "PMD.TooManyMethods",
  "PMD.CloseResource",
  "PMD.CouplingBetweenObjects",
  "PMD.AvoidDuplicateLiterals"
})
class EngineBackedSupervisorTest {

  private static final ExecutionControlCommand STOP =
      new ExecutionControlCommand.StopWorkflowCommand("x", "why");
  private static final ControlRequest REQUEST = new ControlRequest(STOP, "a", "s", "w");

  private static SupervisorEngine engine(final Function<Flux<Signal>, Flux<Directive>> body) {
    return body::apply;
  }

  private static EngineBackedSupervisor supervisor(
      final SupervisorEngine engine, final boolean reviews) {
    return new EngineBackedSupervisor(
        "s1", new SupervisorScope.Global(), engine, reviews, Duration.ofMillis(200), reads -> {});
  }

  @Test
  void commandsComeOutAndOtherDirectivesAreIgnored() {
    final var sup =
        supervisor(
            engine(
                signals ->
                    signals.flatMap(
                        s ->
                            s instanceof Signal.Event
                                ? Flux.just(
                                    new Directive.Command(
                                        "k", STOP, Cause.initiative(), null, null),
                                    new Directive.NoOpinion("nothing"),
                                    new Directive.ReviewVerdict(
                                        "nothing", Directive.Verdict.ALLOW, null),
                                    new Directive.Notify("n", Cause.initiative(), null))
                                : Flux.empty())),
            true);
    assertThat(sup.id()).isEqualTo("s1");
    assertThat(sup.scope()).isInstanceOf(SupervisorScope.Global.class);
    final ExecutionEvent event =
        new ExecutionEvent.WorkflowStatusChanged("x", "s", "w", "RUNNING", Instant.EPOCH);
    StepVerifier.create(sup.supervise(Flux.just(event)).take(1)).expectNext(STOP).verifyComplete();
  }

  @Test
  @SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
  void reviewWaitsForTheVerdict() {
    for (final Directive.Verdict verdict : Directive.Verdict.values()) {
      final var sup =
          supervisor(
              engine(
                  signals ->
                      signals
                          .ofType(Signal.Review.class)
                          .map(
                              r -> (Directive) new Directive.ReviewVerdict(r.id(), verdict, null))),
              true);
      sup.supervise(Flux.never()).subscribe();
      final Decision decision = sup.review(REQUEST).block(Duration.ofSeconds(5));
      assertThat(decision.allowed()).isEqualTo(verdict == Directive.Verdict.ALLOW);
      assertThat(decision.approval() != null)
          .isEqualTo(verdict == Directive.Verdict.REQUIRE_APPROVAL);
      sup.close();
    }
  }

  @Test
  void noOpinionAllowsAndSilenceTimesOutForTheReviewer() {
    final var asked =
        supervisor(
            engine(
                signals ->
                    signals
                        .ofType(Signal.Review.class)
                        .map(r -> (Directive) new Directive.NoOpinion(r.id()))),
            true);
    asked.supervise(Flux.never()).subscribe();
    assertThat(asked.review(REQUEST).block(Duration.ofSeconds(5))).isEqualTo(Decision.ALLOW);
    final var silent =
        supervisor(engine(signals -> signals.thenMany(Flux.<Directive>never())), true);
    silent.supervise(Flux.never()).subscribe();
    // the reviewer turns the timeout into a refusal (a stop is still let through)
    StepVerifier.create(silent.review(REQUEST)).expectError(TimeoutException.class).verify();
  }

  @Test
  void anEngineThatDoesNotReviewIsNotAsked() {
    final var sup = supervisor(engine(signals -> Flux.never()), false);
    assertThat(sup.review(REQUEST).block()).isEqualTo(Decision.ALLOW);
  }

  @Test
  void anEngineReadsWhatItWasGivenAfterTheSupervisorIsAttached() {
    final EngineReads[] given = {null};
    final var sup =
        new EngineBackedSupervisor(
            "s1",
            new SupervisorScope.Global(),
            engine(signals -> Flux.never()),
            false,
            Duration.ofMillis(10),
            reads -> given[0] = reads);
    sup.readsFrom(EngineReads.NONE);
    assertThat(given[0]).isSameAs(EngineReads.NONE);
  }

  @Test
  void theFactoryMakesTheSupervisorFromTheEngine() {
    final boolean[] closed = {false};
    final SupervisorEngineFactory engines =
        new SupervisorEngineFactory() {
          @Override
          public String type() {
            return "t";
          }

          @Override
          public EngineDescriptor describe() {
            return new EngineDescriptor("T", "d", java.util.Set.of(Signal.Kind.REVIEW), false);
          }

          @Override
          public List<com.infenia.yukta.plugin.core.ConfigField> getConfigSchema() {
            return List.of();
          }

          @Override
          public void validate(final EngineConfig config) {
            if (config.settings().containsKey("bad")) {
              throw new IllegalArgumentException("bad");
            }
          }

          @Override
          public SupervisorEngine create(final EngineConfig config, final EngineContext ctx) {
            return new SupervisorEngine() {
              @Override
              public Flux<Directive> run(final Flux<Signal> signals) {
                return ctx.ticks(Duration.ofMillis(10))
                    .map(t -> new Directive.Command("k", STOP, Cause.tick(), null, null));
              }

              @Override
              public void close() {
                closed[0] = true;
                ctx.state().put("closed", true);
              }
            };
          }
        };
    final var factory = new EngineSupervisorFactory(engines);
    assertThat(factory.type()).isEqualTo("t");
    assertThat(factory.getConfigSchema()).isEmpty();
    assertThat(factory.describe().displayName()).isEqualTo("T");
    factory.validate(new Supervisor("a", "t", Map.of(), false));
    final var sup = factory.create(new Supervisor("a", "t", Map.of(), false));
    StepVerifier.create(sup.supervise(Flux.never()).take(1)).expectNext(STOP).verifyComplete();
    assertThat(factory.create(new Supervisor("b", "t", Map.of(), false)))
        .isInstanceOf(ReadsAware.class);
    ((EngineBackedSupervisor) sup).close();
    assertThat(closed[0]).isTrue();
    final Sinks.Many<String> unused = Sinks.many().unicast().onBackpressureBuffer();
    assertThat(unused).isNotNull();
  }

  @Test
  void reviewsBeyondTheCapFailSoTheReviewerRefusesThem() {
    final var sup =
        new EngineBackedSupervisor(
            "s1",
            new SupervisorScope.Global(),
            engine(signals -> signals.thenMany(Flux.<Directive>never())),
            true,
            Duration.ofSeconds(30),
            reads -> {});
    sup.supervise(Flux.never()).subscribe();
    for (int i = 0; i < EngineBackedSupervisor.MAX_OPEN_REVIEWS; i++) {
      sup.review(REQUEST).subscribe();
    }
    // the next one is not even asked, and never let through unreviewed
    StepVerifier.create(sup.review(REQUEST))
        .expectErrorMessage("too many open reviews")
        .verify(Duration.ofSeconds(2));
  }

  @Test
  void anEngineThatDisconnectsMidReviewLeavesTheReviewToTimeOut() {
    final var sup =
        supervisor(
            engine(
                signals ->
                    signals
                        .ofType(Signal.Review.class)
                        .take(1)
                        .flatMap(r -> Flux.<Directive>error(new IllegalStateException("gone")))),
            true);
    sup.supervise(Flux.never()).subscribe(d -> {}, e -> {});
    // the engine failed after it saw the review; nobody answers, and the review times out
    StepVerifier.create(sup.review(REQUEST))
        .expectError(TimeoutException.class)
        .verify(Duration.ofSeconds(5));
  }

  @Test
  void reviewsReachAnEngineThatWasRestarted() {
    final var runs = new java.util.concurrent.atomic.AtomicInteger();
    final var sup =
        supervisor(
            engine(
                signals ->
                    runs.getAndIncrement() == 0
                        ? Flux.<Directive>error(new IllegalStateException("first run fails"))
                        : signals
                            .ofType(Signal.Review.class)
                            .map(
                                r ->
                                    (Directive)
                                        new Directive.ReviewVerdict(
                                            r.id(), Directive.Verdict.DENY, "no"))),
            true);
    sup.directives(Flux.never()).subscribe(d -> {}, e -> {});
    // the host resubscribes after a failure; the review signals must still arrive
    sup.directives(Flux.never()).subscribe();
    assertThat(sup.review(REQUEST).block(Duration.ofSeconds(5))).isEqualTo(Decision.deny("no"));
  }

  @Test
  void answersToNoOpenReviewAndOtherDirectivesChangeNothing() {
    final var sup =
        new EngineBackedSupervisor(
            "s1",
            new SupervisorScope.Global(),
            engine(
                signals ->
                    Flux.<Directive>just(
                        new Directive.ReviewVerdict("gone", Directive.Verdict.DENY, null),
                        new Directive.NoOpinion("gone"),
                        new Directive.Notify("hello", Cause.tick(), "why"),
                        new Directive.Command("k", STOP, Cause.tick(), "why", null))),
            false);
    StepVerifier.create(sup.directives(Flux.never()).take(1))
        .assertNext(command -> assertThat(command.command()).isEqualTo(STOP))
        .verifyComplete();
    assertThat(sup.id()).isEqualTo("s1");
  }

  @Test
  void startedCarriesWhatTheSupervisorOwnsHoldsAndTheCursorThenSaysResubscribe() {
    final List<Signal.Started> starts = new java.util.concurrent.CopyOnWriteArrayList<>();
    final var sup =
        supervisor(
            engine(
                signals ->
                    signals
                        .ofType(Signal.Started.class)
                        .doOnNext(starts::add)
                        .thenMany(Flux.<Directive>never())),
            false);
    sup.readsFrom(
        new EngineReads() {
          @Override
          public Flux<RunView> executions(final String status, final int limit) {
            return Flux.just(new RunView("s", "run1", "w", "RUNNING", null));
          }

          @Override
          public Flux<com.infenia.yukta.plugin.input.InputRequest> openInputs(final int limit) {
            return Flux.error(new IllegalStateException("store down"));
          }

          @Override
          public Flux<String> held() {
            return Flux.just("run1");
          }
        });
    sup.directives(Flux.never()).subscribe();
    sup.directives(Flux.never()).subscribe();

    assertThat(starts).hasSize(2);
    assertThat(starts.get(0).reason()).isEqualTo("first");
    assertThat(starts.get(0).executions()).containsExactly("run1");
    assertThat(starts.get(0).held()).containsExactly("run1");
    // a read that fails leaves its part empty instead of failing the start
    assertThat(starts.get(0).openInputs()).isEmpty();
    assertThat(starts.get(0).cursor()).isEqualTo("e0");
    assertThat(starts.get(1).reason()).isEqualTo("resubscribe");
  }

  @Test
  void theDefaultReadsHoldNothing() {
    StepVerifier.create(EngineReads.NONE.held()).verifyComplete();
  }

  @Test
  void aParentEngineIsAskedAndAnswersAConsultation() {
    final var parent =
        supervisor(
            engine(
                signals ->
                    signals
                        .ofType(Signal.Delegated.class)
                        .map(d -> new Directive.DelegationAnswer(d.id(), Map.of("ok", true)))),
            true);
    final var directives = parent.directives(Flux.never()).subscribe();
    try {
      StepVerifier.create(
              parent.consulted(
                  "child", DelegationKind.CONSULT, Map.of("q", 1), Duration.ofSeconds(2)))
          .expectNext(Map.of("ok", true))
          .verifyComplete();
    } finally {
      directives.dispose();
    }
  }

  @Test
  void aParentThatDeclinesOrIsSlowGivesNoAnswer() {
    final var declines =
        supervisor(
            engine(
                signals ->
                    signals
                        .ofType(Signal.Delegated.class)
                        .map(d -> (Directive) new Directive.NoOpinion(d.id()))),
            true);
    final var slow = supervisor(engine(signals -> signals.thenMany(Flux.never())), true);
    final var first = declines.directives(Flux.never()).subscribe();
    final var second = slow.directives(Flux.never()).subscribe();
    try {
      StepVerifier.create(
              declines.consulted("c", DelegationKind.CONSULT, Map.of(), Duration.ofSeconds(2)))
          .verifyComplete();
      StepVerifier.create(
              slow.consulted("c", DelegationKind.CONSULT, Map.of(), Duration.ofMillis(100)))
          .verifyComplete();
    } finally {
      first.dispose();
      second.dispose();
    }
  }

  @Test
  void aQuestionOfTheEngineComesBackAsConsultedWithItsKey() {
    final Sinks.Many<Signal> seen = Sinks.many().replay().all();
    final var child =
        supervisor(
            engine(
                signals ->
                    Flux.merge(
                        signals.doOnNext(seen::tryEmitNext).then(Mono.<Directive>empty()),
                        Flux.just(
                            new Directive.Delegate(
                                DelegationKind.CONSULT, Map.of("q", "x"), "q1")))),
            true);
    child.consultWith((kind, payload) -> Mono.just(Map.of("boss", Map.of("a", 1))));
    final var run = child.directives(Flux.never()).subscribe();
    try {
      StepVerifier.create(seen.asFlux().ofType(Signal.Consulted.class).take(1))
          .assertNext(
              c -> {
                assertThat(c.id()).isEqualTo("q1");
                assertThat(c.answers()).isEqualTo(Map.of("boss", Map.of("a", 1)));
                assertThat(c.kind()).isEqualTo(Signal.Kind.CONSULTED);
              })
          .verifyComplete();
    } finally {
      run.dispose();
    }
  }

  @Test
  void aQuestionWithNoWayToAskIsDropped() {
    final var child =
        supervisor(
            engine(
                signals ->
                    Flux.<Directive>just(new Directive.Delegate(DelegationKind.CONSULT, Map.of()))
                        .concatWith(Flux.never())),
            true);
    StepVerifier.create(child.directives(Flux.never()).take(Duration.ofMillis(200)))
        .verifyComplete();
  }

  @Test
  void anEngineThatDiesWhileAReviewIsOpenLeavesTheReviewToTimeOutAndNeverAllowsIt() {
    final var sup =
        supervisor(
            engine(
                signals ->
                    signals
                        .ofType(Signal.Review.class)
                        .<Directive>flatMap(
                            r -> Flux.error(new IllegalStateException("connection lost")))),
            true);
    final var run = sup.directives(Flux.never()).subscribe(d -> {}, e -> {});
    try {
      StepVerifier.create(sup.review(REQUEST)).expectError(TimeoutException.class).verify();
    } finally {
      run.dispose();
    }
  }
}
