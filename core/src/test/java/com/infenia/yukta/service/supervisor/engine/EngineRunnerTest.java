// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.engine;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.SupervisorCapabilities;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.control.engine.Cause;
import com.infenia.yukta.plugin.control.engine.Directive;
import com.infenia.yukta.plugin.control.engine.Outcome;
import com.infenia.yukta.plugin.control.engine.Signal;
import com.infenia.yukta.plugin.control.engine.SupervisorEngine;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.service.supervisor.engine.EngineRunner.Result;
import com.infenia.yukta.service.supervisor.engine.EngineRunner.Target;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** An engine runs behind the gate: commands are checked, applied once per key, and recorded. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class EngineRunnerTest {

  private final List<Result> results = new ArrayList<>();
  private final List<Directive> others = new ArrayList<>();
  private final AtomicInteger applied = new AtomicInteger();

  private static Directive.Command pause(final String key, final String run) {
    return new Directive.Command(
        key, new ExecutionControlCommand.PauseWorkflowCommand(run), Cause.event("e1"), null, null);
  }

  private EngineRunner runner(
      final SupervisorEngine engine, final boolean dry, final String owned) {
    return new EngineRunner(
        "sup",
        engine,
        new DirectiveGate(),
        new SupervisorCapabilities(null, false, InputRisk.LOW),
        new SupervisorScope.Workflow("w", "s"),
        dry,
        command -> Mono.just(new Target("s", owned, null)),
        command -> {
          applied.incrementAndGet();
          return Mono.just(Outcome.APPLIED);
        },
        results::add,
        others::add);
  }

  private static Flux<Signal> oneSignal() {
    return Flux.just(new Signal.Tick(Instant.EPOCH));
  }

  @Test
  void anAcceptedCommandIsAppliedAndItsOutcomeRecorded() {
    final SupervisorEngine engine = signals -> signals.map(_ -> pause("k1", "e"));

    StepVerifier.create(runner(engine, false, "w").run(oneSignal())).verifyComplete();

    assertThat(applied).hasValue(1);
    assertThat(results).containsExactly(new Result("k1", Outcome.APPLIED, null, false));
  }

  @Test
  void repeatedKeyIsAppliedOnceAndGetsTheFirstResult() {
    final SupervisorEngine engine =
        signals -> signals.flatMap(_ -> Flux.just(pause("same", "e"), pause("same", "e")));

    StepVerifier.create(runner(engine, false, "w").run(oneSignal())).verifyComplete();

    assertThat(applied).hasValue(1);
    assertThat(results).hasSize(2).allMatch(r -> r.outcome() == Outcome.APPLIED);
  }

  @Test
  void commandForUnitTheSupervisorDoesNotOwnIsRefusedAndNotApplied() {
    final SupervisorEngine engine = signals -> signals.map(_ -> pause("k2", "e"));

    StepVerifier.create(runner(engine, false, "other-workflow").run(oneSignal())).verifyComplete();

    assertThat(applied).hasValue(0);
    assertThat(results)
        .singleElement()
        .satisfies(
            r -> {
              assertThat(r.outcome()).isEqualTo(Outcome.NOT_OWNED);
              assertThat(r.rejection()).isEqualTo(DirectiveGate.Rejection.NOT_OWNED);
            });
  }

  @Test
  void dryRunRecordsTheCommandAndAppliesNothing() {
    final SupervisorEngine engine = signals -> signals.map(_ -> pause("k3", "e"));

    StepVerifier.create(runner(engine, true, "w").run(oneSignal())).verifyComplete();

    assertThat(applied).hasValue(0);
    assertThat(results).singleElement().satisfies(r -> assertThat(r.recordOnly()).isTrue());
  }

  @Test
  void directivesThatAreNotCommandsGoToTheOtherConsumer() {
    final SupervisorEngine engine = signals -> signals.map(_ -> new Directive.NoOpinion("r1"));

    StepVerifier.create(runner(engine, false, "w").run(oneSignal())).verifyComplete();

    assertThat(others).containsExactly(new Directive.NoOpinion("r1"));
    assertThat(results).isEmpty();
  }

  @Test
  void commandThatCannotBeAppliedIsReportedNotFoundAndTheRunnerCarriesOn() {
    final SupervisorEngine engine =
        signals -> signals.flatMap(_ -> Flux.just(pause("a", "e"), pause("b", "e")));
    final EngineRunner failing =
        new EngineRunner(
            "sup",
            engine,
            new DirectiveGate(),
            new SupervisorCapabilities(null, false, InputRisk.LOW),
            new SupervisorScope.Workflow("w", "s"),
            false,
            command -> Mono.just(new Target("s", "w", null)),
            command ->
                "a".equals("a") && applied.incrementAndGet() == 1
                    ? Mono.error(new IllegalStateException("gone"))
                    : Mono.just(Outcome.APPLIED),
            results::add,
            others::add);

    StepVerifier.create(failing.run(oneSignal())).verifyComplete();

    assertThat(results)
        .extracting(Result::outcome)
        .containsExactly(Outcome.NOT_FOUND, Outcome.APPLIED);
  }

  @Test
  void anUnreadableTargetCountsAsNotOwned() {
    final SupervisorEngine engine = signals -> signals.map(_ -> pause("k4", "e"));
    final EngineRunner unreadable =
        new EngineRunner(
            "sup",
            engine,
            new DirectiveGate(),
            new SupervisorCapabilities(null, false, InputRisk.LOW),
            new SupervisorScope.Workflow("w", "s"),
            false,
            command -> Mono.error(new IllegalStateException("no lookup")),
            command -> Mono.just(Outcome.APPLIED),
            results::add,
            others::add);

    StepVerifier.create(unreadable.run(oneSignal())).verifyComplete();

    assertThat(results)
        .singleElement()
        .satisfies(r -> assertThat(r.outcome()).isEqualTo(Outcome.NOT_OWNED));
  }

  @Test
  void closingTheRunnerClosesTheEngine() {
    final AtomicInteger closed = new AtomicInteger();
    final SupervisorEngine engine =
        new SupervisorEngine() {
          @Override
          public Flux<Directive> run(final Flux<Signal> signals) {
            return Flux.empty();
          }

          @Override
          public void close() {
            closed.incrementAndGet();
          }
        };

    runner(engine, false, "w").close();

    assertThat(closed).hasValue(1);
    assertThat(Set.of(1)).isNotEmpty();
  }
}
