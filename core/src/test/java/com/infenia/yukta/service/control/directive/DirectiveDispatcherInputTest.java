// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.directive;

import static com.infenia.yukta.service.supervisor.SupervisorTestSupport.providerOf;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ControlRequest;
import com.infenia.yukta.plugin.control.Decision;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.AnswerInputCommand;
import com.infenia.yukta.plugin.control.ExecutionSupervisor;
import com.infenia.yukta.plugin.control.SupervisorScope;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputForbiddenException;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.service.archive.ExecutionArchive;
import com.infenia.yukta.service.control.ControlBusService;
import com.infenia.yukta.service.control.ExecutionControl;
import com.infenia.yukta.service.control.processor.AnswerInputCommandProcessor;
import com.infenia.yukta.service.control.store.InMemoryExecutionControlStore;
import com.infenia.yukta.service.input.InputCommandGuard;
import com.infenia.yukta.service.input.InputFlow;
import com.infenia.yukta.service.input.InputTestSupport;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import com.infenia.yukta.service.supervisor.ControlRejectedException;
import com.infenia.yukta.service.supervisor.ControlReviewer;
import com.infenia.yukta.service.supervisor.ExecutionEventFeed;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.test.StepVerifier;

/** Answers travel the dispatcher: ordered, reviewed, authorised and audited like any command. */
@NoArgsConstructor
@SuppressWarnings({
  "PMD.ExcessiveImports",
  "PMD.AvoidDuplicateLiterals",
  "PMD.TooManyStaticImports"
})
class DirectiveDispatcherInputTest {

  private final InputFlow flow = new InputFlow();

  private final ExecutionSupervisor reviewer = mock(ExecutionSupervisor.class);

  private final InMemoryExecutionControlStore registry = new InMemoryExecutionControlStore();

  private final DirectiveDispatcher dispatcher = dispatcherWith(flow.guard);

  private DirectiveDispatcher dispatcherWith(final InputCommandGuard guard) {
    registry.save(
        new ExecutionControl(
            "session",
            "workflow",
            "exec",
            null,
            Map.of(),
            Sinks.one(),
            null,
            Map.of(),
            Map.of(),
            Map.of(),
            Map.of(),
            new AtomicBoolean()));
    when(reviewer.scope()).thenReturn(new SupervisorScope.Global());
    when(reviewer.id()).thenReturn("vetoer");
    when(reviewer.review(any())).thenReturn(Mono.just(Decision.ALLOW));
    return new DirectiveDispatcher(
        List.of(new AnswerInputCommandProcessor(guard, flow.broker)),
        registry,
        mock(ControlBusService.class),
        new ExecutionArchive(),
        flow.log,
        new ControlReviewer(providerOf(reviewer)),
        new ExecutionEventFeed());
  }

  private InputRequest ask(final InputRisk risk) {
    flow.ask(
        InputTestSupport.spec(
            "k", InputKind.APPROVAL, risk, Audience.CURRENT_TIER, List.of(), null, Map.of()));
    return flow.onlyOpen();
  }

  private static AnswerInputCommand answer(final String requestId, final String text) {
    return new AnswerInputCommand("exec", requestId, null, "approve", text, Map.of(), null, null);
  }

  private List<ExecutionLogEvent> controlEntries() {
    return flow.log.readAll().stream()
        .filter(
            entry ->
                entry.type() == ExecutionLogEvent.Type.CONTROL_APPLIED
                    || entry.type() == ExecutionLogEvent.Type.CONTROL_REJECTED)
        .toList();
  }

  @Test
  void supervisorThatMayAnswerAnswersMediumRequestThroughTheDispatcher() {
    flow.attach("guard", true, InputRisk.MEDIUM);
    final InputRequest request = ask(InputRisk.MEDIUM);

    StepVerifier.create(
            dispatcher
                .dispatchOrdered(answer(request.id(), null))
                .contextWrite(InputFlow.asSupervisor("guard")))
        .verifyComplete();

    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.ANSWERED);
    assertThat(controlEntries())
        .singleElement()
        .satisfies(
            entry -> assertThat(entry.type()).isEqualTo(ExecutionLogEvent.Type.CONTROL_APPLIED));
  }

  @Test
  void supervisorCannotAnswerHighRequestButAnOperatorCan() {
    flow.attach("guard", true, InputRisk.HIGH);
    final InputRequest request = ask(InputRisk.HIGH);

    StepVerifier.create(
            dispatcher
                .dispatchOrdered(answer(request.id(), null))
                .contextWrite(InputFlow.asSupervisor("guard")))
        .expectError(InputForbiddenException.class)
        .verify();
    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.OPEN);

    StepVerifier.create(
            dispatcher
                .dispatchOrdered(answer(request.id(), null))
                .contextWrite(InputFlow.asKey("op", ApiRole.OPERATOR)))
        .verifyComplete();
    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.ANSWERED);
  }

  @Test
  void reviewerThatDeniesTheAnswerStopsItAndTheRequestStaysOpen() {
    when(reviewer.review(any()))
        .thenAnswer(
            call -> {
              final ControlRequest request = call.getArgument(0);
              return Mono.just(
                  request.command() instanceof AnswerInputCommand
                      ? Decision.deny("answers are frozen")
                      : Decision.ALLOW);
            });
    final InputRequest request = ask(InputRisk.LOW);

    StepVerifier.create(
            dispatcher
                .dispatchOrdered(answer(request.id(), null))
                .contextWrite(InputFlow.asKey("op", ApiRole.OPERATOR)))
        .expectError(ControlRejectedException.class)
        .verify();

    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.OPEN);
    assertThat(controlEntries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.type()).isEqualTo(ExecutionLogEvent.Type.CONTROL_REJECTED);
              assertThat(entry.status()).contains("answers are frozen");
            });
  }

  @Test
  void deniedAnswerIsAuditedWithTheRuleAndNeverTheAnswerText() {
    flow.attach("guard", true, InputRisk.HIGH);
    final InputRequest request = ask(InputRisk.HIGH);

    StepVerifier.create(
            dispatcher
                .dispatchOrdered(answer(request.id(), "my-private-answer"))
                .contextWrite(InputFlow.asSupervisor("guard")))
        .expectError(InputForbiddenException.class)
        .verify();

    assertThat(controlEntries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.type()).isEqualTo(ExecutionLogEvent.Type.CONTROL_REJECTED);
              assertThat(entry.status())
                  .contains("Rule 'risk'")
                  .doesNotContain("my-private-answer");
              assertThat(String.valueOf(entry.payload())).doesNotContain("my-private-answer");
            });
  }

  @Test
  void anAnswerThatIsCancelledBeforeItFinishesIsRecordedAsCancelled() {
    final InputCommandGuard stuck =
        new InputCommandGuard(flow.leader, flow.broker, flow.actors, context -> Mono.never());
    final DirectiveDispatcher stuckDispatcher = dispatcherWith(stuck);
    final InputRequest request = ask(InputRisk.LOW);

    stuckDispatcher
        .dispatchOrdered(answer(request.id(), null))
        .contextWrite(InputFlow.asKey("op", ApiRole.OPERATOR))
        .subscribe()
        .dispose();

    assertThat(controlEntries())
        .singleElement()
        .satisfies(
            entry -> {
              assertThat(entry.type()).isEqualTo(ExecutionLogEvent.Type.CONTROL_REJECTED);
              assertThat(entry.status()).containsIgnoringCase("cancel");
            });
    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.OPEN);
  }
}
