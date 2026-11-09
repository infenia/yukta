// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.supervisor.rules;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.engine.Cause;
import com.infenia.yukta.plugin.control.engine.Directive;
import com.infenia.yukta.plugin.control.engine.EngineReads;
import com.infenia.yukta.plugin.input.InputRequest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Flux;

/** Rules with an {@code every} look at the owned runs on a timer. */
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.LooseCoupling"})
class TimedRulesTest {

  private static final String TIMED =
      """
      {"schemaVersion": 1, "rules": [
        {"id": "too-many", "when": {"every": "1s", "status": "RUNNING",
           "if": "read.RUNNING > 1"},
         "then": {"command": "PauseWorkflow", "reason": "more than one run"}}]}
      """;

  private static EngineReads runs(final String... statuses) {
    return new EngineReads() {
      @Override
      public Flux<RunView> executions(final @Nullable String status, final int limit) {
        final java.util.concurrent.atomic.AtomicInteger n =
            new java.util.concurrent.atomic.AtomicInteger();
        return Flux.fromArray(statuses)
            .map(s -> new RunView("s", "run" + n.incrementAndGet(), "w", s, Instant.EPOCH));
      }

      @Override
      public Flux<InputRequest> openInputs(final int limit) {
        return Flux.empty();
      }
    };
  }

  private static RuleSupervisor supervisor(final String json) {
    return new RuleSupervisor(RuleSupervisor.read(json), Clock.systemUTC());
  }

  @Test
  void timedRuleActsOnEveryRunItMatchesWhenTheReadConditionHolds() {
    final RuleSupervisor supervisor = supervisor(TIMED);
    supervisor.readsFrom(runs("RUNNING", "RUNNING", "SUCCESS"));

    final List<Directive.Command> commands =
        supervisor.directives(Flux.never()).take(2).collectList().block(Duration.ofSeconds(10));

    assertThat(commands)
        .extracting(Directive.Command::command)
        .containsExactly(new PauseWorkflowCommand("run1"), new PauseWorkflowCommand("run2"));
    assertThat(commands)
        .allSatisfy(
            command -> {
              assertThat(command.cause()).isEqualTo(Cause.tick());
              assertThat(command.reason()).isEqualTo("more than one run");
              assertThat(command.ifStatus()).isEqualTo("RUNNING");
            });
    assertThat(commands.get(0).key()).isNotEqualTo(commands.get(1).key());
  }

  @Test
  void timedRuleStaysQuietWhenTheConditionDoesNotHold() {
    final RuleSupervisor supervisor = supervisor(TIMED);
    supervisor.readsFrom(runs("RUNNING", "SUCCESS"));

    assertThat(
            supervisor
                .directives(Flux.never())
                .take(Duration.ofMillis(2500))
                .collectList()
                .block(Duration.ofSeconds(10)))
        .isEmpty();
  }

  @Test
  void reactionCarriesTheEventItAnswers() {
    final RuleSupervisor supervisor =
        supervisor(
            """
            {"rules": [{"id": "p", "when": {"event": "WorkflowStatusChanged", "status": "FAILURE"},
              "then": {"command": "PauseWorkflow"}}]}
            """);
    final ExecutionEvent failed =
        new ExecutionEvent.WorkflowStatusChanged("x", "s", "w", "FAILURE", Instant.EPOCH);

    final Directive.Command command =
        supervisor.directives(Flux.just(failed)).blockFirst(Duration.ofSeconds(5));

    assertThat(command.command()).isEqualTo(new PauseWorkflowCommand("x"));
    assertThat(command.cause().kind()).isEqualTo(Cause.Kind.EVENT);
    assertThat(supervisor.supervise(Flux.just(failed)).blockFirst(Duration.ofSeconds(5)))
        .isEqualTo(new PauseWorkflowCommand("x"));
  }

  @Test
  void timedRuleNeedsCommandReasonAndPeriod() {
    assertThatThrownBy(
            () ->
                supervisor(
                    """
                    {"rules": [{"id": "a", "when": {"every": "1s"},
                      "then": {"command": "PauseWorkflow"}}]}
                    """))
        .hasMessageContaining("needs a reason");
    assertThatThrownBy(
            () ->
                supervisor(
                    """
                    {"rules": [{"id": "a", "when": {"every": "1s", "event": "NodeStatusChanged"},
                      "then": {"command": "PauseWorkflow", "reason": "r"}}]}
                    """))
        .hasMessageContaining("no event");
    assertThatThrownBy(
            () ->
                supervisor(
                    """
                    {"rules": [{"id": "a", "when": {"every": "soon"},
                      "then": {"command": "PauseWorkflow", "reason": "r"}}]}
                    """))
        .hasMessageContaining("use 30s, 5m or 2h");
    assertThatThrownBy(
            () ->
                supervisor(
                    """
                    {"rules": [{"id": "a", "when": {"every": "0s"},
                      "then": {"command": "PauseWorkflow", "reason": "r"}}]}
                    """))
        .hasMessageContaining("under one second");
  }

  @Test
  void periodsAreReadInSecondsMinutesAndHours() {
    assertThat(RuleSupervisor.period("r", "30s")).isEqualTo(Duration.ofSeconds(30));
    assertThat(RuleSupervisor.period("r", "5m")).isEqualTo(Duration.ofMinutes(5));
    assertThat(RuleSupervisor.period("r", " 2h ")).isEqualTo(Duration.ofHours(2));
  }

  @Test
  void theReadCountsAnswerZeroForStatusNobodyHas() {
    final ReadCounts counts = new ReadCounts(List.of());
    assertThat(counts.get("FAILURE")).isEqualTo(0L);
    assertThat(counts.containsKey("FAILURE")).isTrue();
  }

  @Test
  void idleSecondsCountsFromTheNewestEventOfTheRunOrItsStart() {
    final RuleSupervisor supervisor =
        supervisor(
            """
            {"schemaVersion": 1, "rules": [
              {"id": "stalled", "when": {"every": "1s", "status": "RUNNING",
                 "if": "idleSeconds > 60"},
               "then": {"command": "PauseWorkflow", "reason": "stalled"}}]}
            """);
    supervisor.readsFrom(runs("RUNNING", "RUNNING"));
    // run1 just did something; run2 started at the epoch and nothing was seen since
    final Flux<ExecutionEvent> events =
        Flux.<ExecutionEvent>just(
                new ExecutionEvent.WorkflowStatusChanged(
                    "run1", "s", "w", "RUNNING", Instant.now()))
            .concatWith(Flux.never());

    final List<Directive.Command> commands =
        supervisor.directives(events).take(1).collectList().block(Duration.ofSeconds(10));

    assertThat(commands)
        .extracting(Directive.Command::command)
        .containsExactly(new PauseWorkflowCommand("run2"));
  }
}
