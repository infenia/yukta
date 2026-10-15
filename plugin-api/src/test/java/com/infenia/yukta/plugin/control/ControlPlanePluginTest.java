// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.control;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings({"PMD.AtLeastOneConstructor", "PMD.CommentRequired", "PMD.ShortMethodName"})
class ControlPlanePluginTest {

  private static final ExecutionControlCommand PAUSE =
      new ExecutionControlCommand.PauseWorkflowCommand("e1");

  private static ExecutionEvent event(final String execution) {
    return new ExecutionEvent.WorkflowStatusChanged(execution, "wf", "RUNNING", Instant.now());
  }

  private static ControlPlanePlugin plane(
      final Function<ExecutionEvent, Mono<ControlDecision>> decide) {
    return new ControlPlanePlugin() {
      @Override
      public String id() {
        return "test";
      }

      @Override
      public Mono<ControlDecision> decide(final ExecutionEvent event) {
        return decide.apply(event);
      }
    };
  }

  @Test
  void supervise_emitsTheCommandsOfEveryDecisionInOrder() {
    final ControlPlanePlugin plane =
        plane(
            event ->
                Mono.just(
                    new ControlDecision(
                        List.of(
                            new ExecutionControlCommand.PauseWorkflowCommand(event.executionId())),
                        1,
                        ControlDecision.Source.RULE)));

    StepVerifier.create(plane.supervise(Flux.just(event("e1"), event("e2"))))
        .expectNext(PAUSE, new ExecutionControlCommand.PauseWorkflowCommand("e2"))
        .verifyComplete();
  }

  @Test
  void supervise_failingDecisionIsNoOpinionAndLaterEventsStillCount() {
    final ControlPlanePlugin plane =
        plane(
            event ->
                "e1".equals(event.executionId())
                    ? Mono.error(new IllegalStateException("down"))
                    : Mono.just(
                        new ControlDecision(List.of(PAUSE), 1, ControlDecision.Source.MODEL)));

    StepVerifier.create(plane.supervise(Flux.just(event("e1"), event("e2"))))
        .expectNext(PAUSE)
        .verifyComplete();
  }

  @Test
  void controlDecision_keepsAnImmutableCopyAndNoneAbstains() {
    final List<ExecutionControlCommand> commands = new ArrayList<>(List.of(PAUSE));

    final ControlDecision decision =
        new ControlDecision(commands, 0.7, ControlDecision.Source.MODEL);
    commands.clear();

    assertThat(decision.commands()).containsExactly(PAUSE);
    assertThatThrownBy(() -> decision.commands().add(PAUSE))
        .isInstanceOf(UnsupportedOperationException.class);
    final ControlDecision none = ControlDecision.none(ControlDecision.Source.RULE);
    assertThat(none.commands()).isEmpty();
    assertThat(none.confidence()).isZero();
    assertThat(none.source()).isEqualTo(ControlDecision.Source.RULE);
  }

  @Test
  void source_hasTheTwoDeciders() {
    assertThat(ControlDecision.Source.values())
        .containsExactly(ControlDecision.Source.RULE, ControlDecision.Source.MODEL);
  }
}
