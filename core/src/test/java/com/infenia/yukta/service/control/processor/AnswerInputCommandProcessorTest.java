// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.infenia.yukta.plugin.control.ExecutionControlCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.AnswerInputCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputForbiddenException;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputOption;
import com.infenia.yukta.plugin.input.InputRejectedException;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.input.InputBroker;
import com.infenia.yukta.service.input.InputCommandGuard;
import com.infenia.yukta.service.input.InputFlow;
import com.infenia.yukta.service.input.InputTestSupport;
import com.infenia.yukta.service.leader.NotLeaderException;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** Answers go through the authoriser and the shape check, and a refused answer leaves it open. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals", "PMD.ExcessiveImports"})
class AnswerInputCommandProcessorTest {

  private final InputFlow flow = new InputFlow();

  private final AnswerInputCommandProcessor processor =
      new AnswerInputCommandProcessor(flow.guard, flow.broker);

  private static final Context OPERATOR = InputFlow.asKey("op", ApiRole.OPERATOR);

  private InputRequest ask(
      final InputKind kind,
      final InputRisk risk,
      final List<InputOption> options,
      final String schema) {
    flow.ask(
        InputTestSupport.spec("k", kind, risk, Audience.CURRENT_TIER, options, schema, Map.of()));
    return flow.onlyOpen();
  }

  private static AnswerInputCommand answer(final String requestId, final String decision) {
    return new AnswerInputCommand(
        "exec", requestId, null, decision, null, Map.of(), "because", 0.9);
  }

  private StepVerifier.FirstStep<Void> run(
      final ExecutionControlCommand command, final Context as) {
    return StepVerifier.create(processor.process(command).contextWrite(as).then());
  }

  @Test
  void onlyAnswerCommandsAreProcessed() {
    assertThat(processor.canProcess(answer("r", "go"))).isTrue();
    assertThat(processor.canProcess(new PauseWorkflowCommand("exec"))).isFalse();
  }

  @Test
  void anOperatorAnswersAndTheWaitingNodeGetsTheAnswer() {
    final var waiting =
        flow.ask(
            InputTestSupport.spec(
                "k",
                InputKind.APPROVAL,
                InputRisk.HIGH,
                Audience.CURRENT_TIER,
                List.of(),
                null,
                Map.of()));
    final InputRequest request = flow.onlyOpen();

    run(answer(request.id(), "approve"), OPERATOR).verifyComplete();

    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.ANSWERED);
    assertThat(waiting.get().decision()).isEqualTo("approve");
    assertThat(waiting.get().responder()).isEqualTo(new Responder(Responder.Type.HUMAN, "op"));
    assertThat(waiting.get().rationale()).isEqualTo("because");
  }

  @Test
  void supervisorThatMayAnswerAnswersMediumRequest() {
    flow.attach("guard", true, InputRisk.MEDIUM);
    final InputRequest request = ask(InputKind.APPROVAL, InputRisk.MEDIUM, List.of(), null);

    run(answer(request.id(), "approve"), InputFlow.asSupervisor("guard")).verifyComplete();

    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.ANSWERED);
  }

  @Test
  void supervisorCannotAnswerHighRequestAndItStaysOpen() {
    flow.attach("guard", true, InputRisk.HIGH);
    final InputRequest request = ask(InputKind.APPROVAL, InputRisk.HIGH, List.of(), null);

    run(answer(request.id(), "approve"), InputFlow.asSupervisor("guard"))
        .expectErrorSatisfies(
            error ->
                assertThat(error)
                    .isInstanceOf(InputForbiddenException.class)
                    .hasMessageContaining("Rule 'risk'"))
        .verify();

    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.OPEN);
    assertThat(flow.meters.counter("yukta.input.denied", "rule", "risk").count()).isEqualTo(1.0);
  }

  @Test
  void choiceNeedsAnOptionId() {
    final InputRequest request =
        ask(
            InputKind.CHOICE,
            InputRisk.LOW,
            List.of(new InputOption("a"), new InputOption("b")),
            null);

    run(answer(request.id(), "c"), OPERATOR)
        .expectErrorSatisfies(
            error ->
                assertThat(error)
                    .isInstanceOf(InputRejectedException.class)
                    .hasMessageContaining("[a, b]"))
        .verify();
    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.OPEN);

    run(answer(request.id(), "b"), OPERATOR).verifyComplete();
    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.ANSWERED);
  }

  @Test
  void structuredAnswerMustFitTheSchema() {
    final InputRequest request =
        ask(
            InputKind.STRUCTURED,
            InputRisk.LOW,
            List.of(),
            "{\"type\":\"object\",\"required\":[\"n\"]}");

    run(answer(request.id(), "done"), OPERATOR)
        .expectErrorSatisfies(error -> assertThat(error).isInstanceOf(InputRejectedException.class))
        .verify();

    final AnswerInputCommand good =
        new AnswerInputCommand(
            "exec", request.id(), null, "done", null, Map.of("n", 1), null, null);
    run(good, OPERATOR).verifyComplete();
  }

  @Test
  void textRequestNeedsText() {
    final InputRequest request = ask(InputKind.TEXT, InputRisk.LOW, List.of(), null);

    run(answer(request.id(), "ok"), OPERATOR).expectError(InputRejectedException.class).verify();

    final AnswerInputCommand blank =
        new AnswerInputCommand("exec", request.id(), null, "ok", "  ", Map.of(), null, null);
    run(blank, OPERATOR).expectError(InputRejectedException.class).verify();

    final AnswerInputCommand text =
        new AnswerInputCommand("exec", request.id(), null, "ok", "hello", Map.of(), null, null);
    run(text, OPERATOR).verifyComplete();
  }

  @Test
  void anUnknownRequestIsNotFound() {
    run(answer("nope", "go"), OPERATOR).expectError(NotFoundException.class).verify();
  }

  @Test
  void requestOfAnotherRunIsConflict() {
    final InputRequest request = ask(InputKind.APPROVAL, InputRisk.LOW, List.of(), null);
    final AnswerInputCommand other =
        new AnswerInputCommand("other-run", request.id(), null, "go", null, Map.of(), null, null);

    run(other, OPERATOR).expectError(ConflictException.class).verify();

    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.OPEN);
  }

  @Test
  void staleVersionIsRefusedAndTheRequestStaysOpen() {
    final InputRequest request = ask(InputKind.APPROVAL, InputRisk.LOW, List.of(), null);
    final AnswerInputCommand stale =
        new AnswerInputCommand("exec", request.id(), 7L, "go", null, Map.of(), null, null);

    run(stale, OPERATOR)
        .expectErrorSatisfies(
            error ->
                assertThat(error)
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("OPEN"))
        .verify();

    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.OPEN);
  }

  @Test
  void anAnswerToAnEndedRequestIsConflict() {
    final InputRequest request = ask(InputKind.APPROVAL, InputRisk.LOW, List.of(), null);
    flow.broker.cancel(request.id(), "run-ended").block();

    run(answer(request.id(), "go"), OPERATOR)
        .expectErrorSatisfies(
            error ->
                assertThat(error)
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("CANCELLED"))
        .verify();
  }

  @Test
  void secondAnswerIsIdempotent() {
    final InputRequest request = ask(InputKind.APPROVAL, InputRisk.LOW, List.of(), null);

    run(answer(request.id(), "go"), OPERATOR).verifyComplete();
    run(answer(request.id(), "go"), OPERATOR).verifyComplete();

    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.ANSWERED);
  }

  @Test
  void standbyRefusesToAnswer() {
    final InputRequest request = ask(InputKind.APPROVAL, InputRisk.LOW, List.of(), null);
    when(flow.leader.isLeader()).thenReturn(false);

    run(answer(request.id(), "go"), OPERATOR).expectError(NotLeaderException.class).verify();

    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.OPEN);
  }

  @Test
  void senderWithoutIdentityIsRefused() {
    final InputRequest request = ask(InputKind.APPROVAL, InputRisk.LOW, List.of(), null);

    run(answer(request.id(), "go"), Context.empty())
        .expectErrorSatisfies(
            error ->
                assertThat(error)
                    .isInstanceOf(InputForbiddenException.class)
                    .hasMessageContaining("not identified"))
        .verify();
  }

  @Test
  void theAnswerTextIsNeverInTheRefusal() {
    final InputRequest request = ask(InputKind.TEXT, InputRisk.HIGH, List.of(), null);
    flow.attach("guard", true, InputRisk.HIGH);
    final AnswerInputCommand secret =
        new AnswerInputCommand(
            "exec", request.id(), null, "ok", "the-secret-answer", Map.of(), null, null);

    run(secret, InputFlow.asSupervisor("guard"))
        .expectErrorSatisfies(
            error -> assertThat(error.getMessage()).doesNotContain("the-secret-answer"))
        .verify();
  }

  @Test
  void theLogGainsTheResponderRiskAndTruncatedRationale() {
    final InputRequest request = ask(InputKind.APPROVAL, InputRisk.MEDIUM, List.of(), null);
    final AnswerInputCommand wordy =
        new AnswerInputCommand(
            "exec", request.id(), null, "go", null, Map.of(), "r".repeat(900), null);

    run(wordy, OPERATOR).verifyComplete();

    final var entry = flow.logged("INPUT_ANSWERED");
    assertThat(entry.payload())
        .containsEntry("responderType", "HUMAN")
        .containsEntry("responderId", "op")
        .containsEntry("risk", "MEDIUM")
        .containsEntry("requestId", request.id());
    assertThat(entry.payload()).extractingByKey("rationale").asString().hasSize(500);
  }

  @Test
  void requestThatVanishesAfterClearingIsNotFound() {
    final InputCommandGuard guard = mock(InputCommandGuard.class);
    final InputBroker broker = mock(InputBroker.class);
    final InputRequest request = InputTestSupport.request("r1", InputTestSupport.spec("k"));
    when(guard.clear("exec", "r1", false))
        .thenReturn(
            Mono.just(
                new InputCommandGuard.Cleared(request, new Responder(Responder.Type.HUMAN, "op"))));
    when(broker.answer(any(), any(), any())).thenReturn(Mono.empty());
    final AnswerInputCommand withText =
        new AnswerInputCommand("exec", "r1", null, "ok", "text", Map.of(), null, null);

    StepVerifier.create(new AnswerInputCommandProcessor(guard, broker).process(withText))
        .expectError(NotFoundException.class)
        .verify();
  }
}
