// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control.engine;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.control.DelegationKind;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;

/** The causes, signals, directives and descriptor an engine and its supervisor agree on. */
@NoArgsConstructor
class EngineTypesTest {

  @Test
  void causesReadAsTheContractWritesThem() {
    assertThat(Cause.event("e1").text()).isEqualTo("event:e1");
    assertThat(Cause.answers("r7").text()).isEqualTo("answers:r7");
    assertThat(Cause.tick().text()).isEqualTo("tick");
    assertThat(Cause.initiative().text()).isEqualTo("initiative");
    assertThat(Cause.tick().unprompted()).isTrue();
    assertThat(Cause.initiative().unprompted()).isTrue();
    assertThat(Cause.event("e").unprompted()).isFalse();
    assertThat(Cause.answers("r").unprompted()).isFalse();
    assertThatThrownBy(() -> new Cause(Cause.Kind.EVENT, " "))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Cause(Cause.Kind.ANSWERS, null))
        .isInstanceOf(IllegalArgumentException.class);
    assertThatThrownBy(() -> new Cause(null, "x")).isInstanceOf(NullPointerException.class);
    assertThat(new Cause(Cause.Kind.TICK, null).text()).isEqualTo("tick");
  }

  @Test
  void signalsAndDirectivesKeepUnmodifiableCopiesAndNameTheirKind() {
    assertThat(new Signal.Tick(Instant.EPOCH).kind()).isEqualTo(Signal.Kind.TICK);
    final var started =
        new Signal.Started("first", "c1", List.of("x1:RUNNING"), List.of(), List.of("x1"));
    assertThat(started.kind()).isEqualTo(Signal.Kind.STARTED);
    assertThat(started.held()).containsExactly("x1");
    final var delegated =
        new Signal.Delegated("d1", DelegationKind.CONSULT, "child", Map.of("question", "retry?"));
    assertThat(delegated.kind()).isEqualTo(Signal.Kind.DELEGATED);
    assertThat(delegated.payload()).containsEntry("question", "retry?");
    final var consulted =
        new Signal.Consulted(
            "q1", DelegationKind.CONSULT, Map.of("boss", Map.of("choice", "retry")));
    assertThat(consulted.kind()).isEqualTo(Signal.Kind.CONSULTED);
    assertThat(consulted.answers()).containsKey("boss");
    assertThat(new Directive.Delegate(DelegationKind.CONSULT, Map.of("q", 1), "q1").key())
        .isEqualTo("q1");
    assertThat(new Directive.DelegationAnswer("d1", Map.of("choice", "retry")).answer())
        .containsEntry("choice", "retry");
    assertThat(new Directive.Delegate(DelegationKind.APPROVAL, Map.of()).kind())
        .isEqualTo(DelegationKind.APPROVAL);
    assertThat(
            new Directive.Command(
                    "k",
                    new ExecutionControlCommand.PauseWorkflowCommand("e"),
                    Cause.tick(),
                    "why",
                    null)
                .reason())
        .isEqualTo("why");
  }

  @Test
  void everySignalAndDirectiveIsBuiltAndNamesItsKind() {
    final var request =
        new com.infenia.yukta.plugin.control.ControlRequest(
            new ExecutionControlCommand.PauseWorkflowCommand("e"), "key:ci", "s", "w");
    assertThat(new Signal.Review("r1", request, java.time.Duration.ofSeconds(5)).kind())
        .isEqualTo(Signal.Kind.REVIEW);
    assertThat(
            new Signal.Event(
                    "ev1",
                    "c1",
                    new com.infenia.yukta.plugin.control.ExecutionEvent.ControlOutcome(
                        "e", "s", "w", "PauseWorkflowCommand", null, true, null, Instant.EPOCH))
                .kind())
        .isEqualTo(Signal.Kind.EVENT);
    final var verdict = new Directive.ReviewVerdict("r1", Directive.Verdict.DENY, "closed");
    assertThat(verdict.verdict()).isEqualTo(Directive.Verdict.DENY);
    assertThat(verdict.reason()).isEqualTo("closed");
    assertThat(new Directive.Notify("queue is long", Cause.tick(), "depth 40").message())
        .isEqualTo("queue is long");
    assertThat(Signal.Kind.values()).hasSize(6);
    assertThat(Cause.Kind.values()).hasSize(4);
  }

  @Test
  @SuppressWarnings("PMD.CloseResource")
  void anEnginePassesSignalsToDirectivesAndClosesWithoutFuss() {
    final SupervisorEngine engine = signals -> signals.map(s -> new Directive.NoOpinion("r"));
    StepVerifier.create(engine.run(Flux.just(new Signal.Tick(Instant.EPOCH))))
        .expectNext(new Directive.NoOpinion("r"))
        .verifyComplete();
    engine.close();
  }

  @Test
  void descriptorsAndConfigsKeepCopies() {
    final var descriptor =
        new EngineDescriptor("Rules", "Rules.", Set.of(Signal.Kind.EVENT, Signal.Kind.TICK), true);
    assertThat(descriptor.drivers()).contains(Signal.Kind.TICK);
    assertThat(new EngineConfig(null).settings()).isEmpty();
    assertThat(new EngineConfig(Map.of("k", "v")).settings()).containsEntry("k", "v");
    assertThat(Outcome.valueOf("NOT_OWNED")).isEqualTo(Outcome.NOT_OWNED);
    assertThat(Directive.Verdict.values()).hasSize(3);
  }
}
