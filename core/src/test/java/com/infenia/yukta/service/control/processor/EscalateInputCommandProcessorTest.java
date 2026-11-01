// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.control.processor;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.control.ExecutionControlCommand.EscalateInputCommand;
import com.infenia.yukta.plugin.control.ExecutionControlCommand.PauseWorkflowCommand;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputForbiddenException;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.security.ApiRole;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import com.infenia.yukta.service.input.InputFlow;
import com.infenia.yukta.service.input.InputTestSupport;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;
import reactor.util.context.Context;

/** Anyone eligible may hand a request up, even one who may not answer it. */
@NoArgsConstructor
class EscalateInputCommandProcessorTest {

  private final InputFlow flow = new InputFlow();

  private final EscalateInputCommandProcessor processor =
      new EscalateInputCommandProcessor(flow.guard, flow.broker);

  private InputRequest ask(final InputRisk risk) {
    flow.ask(
        InputTestSupport.spec(
            "k", InputKind.APPROVAL, risk, Audience.CURRENT_TIER, List.of(), null, Map.of()));
    return flow.onlyOpen();
  }

  private static EscalateInputCommand escalate(final String requestId) {
    return new EscalateInputCommand("exec", requestId, "needs a person", "looks risky");
  }

  private StepVerifier.FirstStep<Void> run(final EscalateInputCommand command, final Context as) {
    return StepVerifier.create(processor.process(command).contextWrite(as).then());
  }

  @Test
  void onlyEscalateCommandsAreProcessed() {
    assertThat(processor.canProcess(escalate("r"))).isTrue();
    assertThat(processor.canProcess(new PauseWorkflowCommand("exec"))).isFalse();
  }

  @Test
  void supervisorThatMayNotAnswerCanHandHighRequestUp() {
    flow.attach("guard", false, InputRisk.LOW);
    final InputRequest request = ask(InputRisk.HIGH);

    run(escalate(request.id()), InputFlow.asSupervisor("guard")).verifyComplete();

    final InputRequest now = flow.current(request.id());
    assertThat(now.state()).isEqualTo(InputState.ESCALATED);
    assertThat(now.advice()).isEqualTo("looks risky");
    final var entry =
        flow.log.readAll().stream()
            .filter(e -> "INPUT_ESCALATED".equals(e.payload().get("event")))
            .findFirst()
            .orElseThrow();
    assertThat(entry.payload())
        .containsEntry("responderType", "SUPERVISOR")
        .containsEntry("responderId", "guard")
        .containsEntry("risk", "HIGH")
        .containsEntry("reason", "needs a person");
  }

  @Test
  void escalatingTwiceIsIdempotent() {
    final InputRequest request = ask(InputRisk.LOW);

    run(escalate(request.id()), InputFlow.asKey("op", ApiRole.OPERATOR)).verifyComplete();
    run(escalate(request.id()), InputFlow.asKey("op", ApiRole.OPERATOR)).verifyComplete();

    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.ESCALATED);
  }

  @Test
  void viewerCannotHandRequestUp() {
    final InputRequest request = ask(InputRisk.LOW);

    run(escalate(request.id()), InputFlow.asKey("v", ApiRole.VIEWER))
        .expectError(InputForbiddenException.class)
        .verify();

    assertThat(flow.current(request.id()).state()).isEqualTo(InputState.OPEN);
  }

  @Test
  void requestThatHasEndedCannotBeHandedUp() {
    final InputRequest request = ask(InputRisk.LOW);
    flow.broker
        .answer(
            request.id(),
            null,
            new InputResponse(
                request.id(),
                "go",
                null,
                Map.of(),
                new Responder(Responder.Type.HUMAN, "op"),
                null,
                null,
                Instant.now()))
        .block();

    run(escalate(request.id()), InputFlow.asKey("op", ApiRole.OPERATOR))
        .expectErrorSatisfies(
            error ->
                assertThat(error)
                    .isInstanceOf(ConflictException.class)
                    .hasMessageContaining("ANSWERED"))
        .verify();
  }

  @Test
  void anUnknownRequestIsNotFound() {
    run(escalate("nope"), InputFlow.asKey("op", ApiRole.OPERATOR))
        .expectError(NotFoundException.class)
        .verify();
  }
}
