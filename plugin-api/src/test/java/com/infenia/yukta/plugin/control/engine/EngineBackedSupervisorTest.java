// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.Supervisor;
import com.infenia.yukta.plugin.control.SupervisorScope;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

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
        "s1", new SupervisorScope.Global(), engine, reviews, Duration.ofMillis(200));
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
  void aReviewWaitsForTheVerdict() {
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
  void noOpinionAndSilenceAllow() {
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
    assertThat(silent.review(REQUEST).block(Duration.ofSeconds(5))).isEqualTo(Decision.ALLOW);
  }

  @Test
  void anEngineThatDoesNotReviewIsNotAsked() {
    final var sup = supervisor(engine(signals -> Flux.never()), false);
    assertThat(sup.review(REQUEST).block()).isEqualTo(Decision.ALLOW);
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
    ((EngineBackedSupervisor) sup).close();
    assertThat(closed[0]).isTrue();
    final Sinks.Many<String> unused = Sinks.many().unicast().onBackpressureBuffer();
    assertThat(unused).isNotNull();
  }
}
