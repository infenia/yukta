// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.supervisor.workflow;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ControlDecision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionEvent;
import com.infenia.yukta.plugin.control.SupervisorProfile;
import com.infenia.yukta.service.input.InputTestSupport;
import com.infenia.yukta.service.redact.InputRedactor;
import com.infenia.yukta.service.session.InternalSessions;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

@SuppressWarnings({
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyMethods",
  "PMD.CouplingBetweenObjects"
})
class WorkflowSupervisorTest {

  private final SupervisorWorkflowRunner runner = mock(SupervisorWorkflowRunner.class);

  private final InternalSessions internal = mock(InternalSessions.class);

  private final InputRedactor redactor = new InputRedactor(List.of("token"));

  private static final WorkflowSettings SETTINGS =
      new WorkflowSettings("sys", "wf", Set.of("InputRequested"), 1, Duration.ofSeconds(5));

  private static ExecutionEvent.InputRequested requested(final String id) {
    return new ExecutionEvent.InputRequested(
        InputTestSupport.request(id, InputTestSupport.spec("k" + id)), Instant.EPOCH);
  }

  private WorkflowSupervisor supervisor(final Set<String> allowed) {
    when(internal.checkInternal("sys")).thenReturn(Mono.just(true));
    return new WorkflowSupervisor("p", SETTINGS, runner, internal, redactor, allowed);
  }

  private void ends(final RunResult... results) {
    final var stub =
        when(runner.run(anyString(), anyString(), anyString(), any(), anyString(), any()));
    if (results.length == 1) {
      stub.thenReturn(Mono.just(results[0]));
    } else {
      stub.thenReturn(Mono.just(results[0]), Mono.just(results[1]));
    }
  }

  private static RunResult success(final Object... entries) {
    return new RunResult("SUCCESS", "SUCCESS", null, List.of(Map.of("commands", List.of(entries))));
  }

  @Test
  void anEventStartsTheWorkflowAndItsCommandsAreMapped() {
    final WorkflowSupervisor supervisor = supervisor(Set.of());
    supervisor.bindTo("b1");
    ends(success(Map.of("type", "AnswerInput", "requestId", "r1", "decision", "approve")));

    StepVerifier.create(supervisor.decide(requested("r1")))
        .assertNext(
            decision -> {
              assertThat(decision.commands()).hasSize(1);
              assertThat(decision.commands().getFirst())
                  .isInstanceOf(ExecutionControlCommand.AnswerInputCommand.class);
            })
        .verifyComplete();

    @SuppressWarnings("unchecked")
    final ArgumentCaptor<Map<String, Object>> payload = ArgumentCaptor.forClass(Map.class);
    verify(runner)
        .run(
            org.mockito.ArgumentMatchers.eq("b1"),
            anyString(),
            anyString(),
            payload.capture(),
            anyString(),
            any());
    assertThat(payload.getValue())
        .containsKeys("event", "request")
        .containsEntry("bindingId", "b1");
    assertThat(supervisor.id()).isEqualTo("p");
    assertThat(supervisor.decisionTimeout()).isEqualTo(Duration.ofSeconds(10));
  }

  @Test
  void otherEventsAndRedeliveredEventsStartNothing() {
    final WorkflowSupervisor supervisor = supervisor(Set.of());
    ends(success());

    StepVerifier.create(
            supervisor.decide(
                new ExecutionEvent.WorkflowStatusChanged("e", "s", "w", "RUNNING", Instant.EPOCH)))
        .verifyComplete();
    StepVerifier.create(supervisor.decide(requested("r1"))).verifyComplete();
    StepVerifier.create(supervisor.decide(requested("r1"))).verifyComplete();

    verify(runner, times(1)).run(anyString(), anyString(), anyString(), any(), anyString(), any());
  }

  @Test
  void aSessionThatIsNotInternalGivesNoOpinion() {
    when(internal.checkInternal("sys")).thenReturn(Mono.just(false));
    final WorkflowSupervisor supervisor =
        new WorkflowSupervisor("p", SETTINGS, runner, internal, redactor, Set.of());

    StepVerifier.create(supervisor.decide(requested("r1"))).verifyComplete();
    verify(runner, times(0)).run(anyString(), anyString(), anyString(), any(), anyString(), any());
  }

  @Test
  void onlyMaxConcurrentRunsGoAtOnce() {
    final WorkflowSupervisor supervisor = supervisor(Set.of());
    when(runner.run(anyString(), anyString(), anyString(), any(), anyString(), any()))
        .thenReturn(Mono.never());
    final reactor.core.Disposable first = supervisor.decide(requested("r1")).subscribe();

    StepVerifier.create(supervisor.decide(requested("r2"))).verifyComplete();
    first.dispose();
    verify(runner, times(1)).run(anyString(), anyString(), anyString(), any(), anyString(), any());
  }

  @Test
  void escalateBecomesAnEscalationOfTheRequestInTheEvent() {
    final WorkflowSupervisor supervisor = supervisor(Set.of());
    ends(new RunResult("SUCCESS", "ESCALATE", "too risky", List.of()));
    StepVerifier.create(supervisor.decide(requested("r1")))
        .assertNext(
            d ->
                assertThat(d.commands())
                    .containsExactly(
                        new ExecutionControlCommand.EscalateInputCommand(
                            "exec", "r1", "workflow-escalated", "too risky")))
        .verifyComplete();

    ends(new RunResult("SUCCESS", "ESCALATE", null, List.of()));
    StepVerifier.create(supervisor.decide(requested("r2")))
        .assertNext(d -> assertThat(d.commands()).hasSize(1))
        .verifyComplete();
  }

  @Test
  void escalateOnAnEventWithoutARequestIsDropped() {
    final WorkflowSupervisor supervisor =
        new WorkflowSupervisor(
            "p",
            new WorkflowSettings(
                "sys",
                "wf",
                Set.of("WorkflowStatusChanged", "InputEscalated"),
                1,
                Duration.ofSeconds(5)),
            runner,
            internal,
            redactor,
            Set.of());
    when(internal.checkInternal("sys")).thenReturn(Mono.just(true));
    ends(new RunResult("SUCCESS", "ESCALATE", "x", List.of()));

    StepVerifier.create(
            supervisor.decide(
                new ExecutionEvent.WorkflowStatusChanged("e", "s", "w", "RUNNING", Instant.EPOCH)))
        .verifyComplete();
    StepVerifier.create(
            supervisor.decide(
                new ExecutionEvent.InputEscalated(
                    InputTestSupport.request("r9", InputTestSupport.spec("k9")),
                    "why",
                    Instant.EPOCH)))
        .assertNext(d -> assertThat(d.commands()).hasSize(1))
        .verifyComplete();
  }

  @Test
  void retryRunsOnceMoreAndOnlyOnce() {
    final WorkflowSupervisor supervisor = supervisor(Set.of());
    ends(
        new RunResult("SUCCESS", "RETRY", null, List.of()),
        success(Map.of("type", "PauseWorkflow")));
    StepVerifier.create(supervisor.decide(requested("r1")))
        .assertNext(d -> assertThat(d.commands()).hasSize(1))
        .verifyComplete();
    verify(runner, times(2)).run(anyString(), anyString(), anyString(), any(), anyString(), any());

    ends(
        new RunResult("SUCCESS", "RETRY", null, List.of()),
        new RunResult("SUCCESS", "RETRY", null, List.of()));
    StepVerifier.create(supervisor.decide(requested("r2"))).verifyComplete();
  }

  @Test
  void failuresTheOtherOutcomesAndEmptyResultsGiveNoOpinion() {
    final WorkflowSupervisor supervisor = supervisor(Set.of());
    ends(RunResult.none("FAILED"));
    StepVerifier.create(supervisor.decide(requested("r1"))).verifyComplete();
    ends(new RunResult("SUCCESS", "FAILED", "no", List.of()));
    StepVerifier.create(supervisor.decide(requested("r2"))).verifyComplete();
    ends(success());
    StepVerifier.create(supervisor.decide(requested("r3"))).verifyComplete();
    ends(
        new RunResult(
            "SUCCESS", null, null, List.of(Map.of("commands", List.of(Map.of("type", "Nope"))))));
    StepVerifier.create(supervisor.decide(requested("r4"))).verifyComplete();
  }

  @Test
  void onlyTheNewestThousandEventsAreRemembered() {
    final WorkflowSupervisor supervisor =
        new WorkflowSupervisor(
            "p",
            new WorkflowSettings(
                "sys", "wf", Set.of("WorkflowStatusChanged"), 1, Duration.ofSeconds(5)),
            runner,
            internal,
            redactor,
            Set.of());
    when(internal.checkInternal("sys")).thenReturn(Mono.just(true));
    ends(RunResult.none("FAILED"));

    for (int i = 0; i < 1002; i++) {
      supervisor
          .decide(
              new ExecutionEvent.WorkflowStatusChanged("e" + i, "s", "w", "RUNNING", Instant.EPOCH))
          .block();
    }
    // the oldest was forgotten, so the same event runs again
    supervisor
        .decide(new ExecutionEvent.WorkflowStatusChanged("e0", "s", "w", "RUNNING", Instant.EPOCH))
        .block();

    verify(runner, times(1003))
        .run(anyString(), anyString(), anyString(), any(), anyString(), any());
  }

  @Test
  void theFactoryBuildsItWithNoCommandsGrantedByDefault() {
    when(runner.reservedSlots()).thenReturn(4);
    final WorkflowSupervisorFactory factory =
        new WorkflowSupervisorFactory(runner, internal, redactor);
    final SupervisorProfile profile =
        new SupervisorProfile(
            "p",
            "workflow",
            Map.of("sessionId", "sys", "workflowId", "wf", "events", List.of("InputRequested")),
            false);

    assertThat(factory.type()).isEqualTo("workflow");
    assertThat(factory.defaultAllowedCommands()).isEmpty();
    assertThat(factory.create(profile)).isInstanceOf(WorkflowSupervisor.class);
    assertThat(
            ((ControlDecision) new ControlDecision(List.of(), 1, ControlDecision.Source.RULE))
                .commands())
        .isEmpty();
  }
}
