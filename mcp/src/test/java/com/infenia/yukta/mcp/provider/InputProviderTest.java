// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.mcp.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.infenia.yukta.mcp.dto.ControlActionResult;
import com.infenia.yukta.mcp.dto.PendingInputs;
import com.infenia.yukta.model.execution.TaskStatus;
import com.infenia.yukta.model.execution.WorkflowProgress;
import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputForbiddenException;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputOption;
import com.infenia.yukta.plugin.input.InputRejectedException;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.plugin.input.TimeoutAction;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.input.InMemoryInputRequestStore;
import com.infenia.yukta.service.input.InputAnsweredException;
import com.infenia.yukta.service.input.InputRequestQueries;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;

/** Tests for {@link InputProvider}. */
@NoArgsConstructor
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
class InputProviderTest {

  private static final Instant T0 = Instant.parse("2026-11-02T10:00:00Z");

  /** Where the requests are kept. */
  private final InMemoryInputRequestStore store = new InMemoryInputRequestStore(100);

  /** The gateway the answers go through. */
  private final ControlBusGateway controlBus = mock(ControlBusGateway.class);

  /** The provider under test. */
  private final InputProvider provider =
      new InputProvider(new InputRequestQueries(store), controlBus);

  private static InputRequestSpec spec(
      final String id, final InputKind kind, final String prompt, final String schema) {
    return new InputRequestSpec(
        "key-" + id,
        kind,
        prompt,
        Map.of("branch", "main"),
        kind == InputKind.CHOICE
            ? List.of(new InputOption("ok", "Go"), new InputOption("no"))
            : List.of(),
        schema,
        InputRisk.LOW,
        Audience.CURRENT_TIER,
        Duration.ofMinutes(10),
        TimeoutAction.FAIL,
        null,
        null,
        "TEST");
  }

  private static InputRequest request(
      final String id,
      final String session,
      final InputRequestSpec spec,
      final InputState state,
      final String advice,
      final long second) {
    return new InputRequest(
        id,
        "key-" + id,
        session,
        "wf",
        "e1",
        "gate",
        spec,
        state,
        state == InputState.OPEN ? 0 : 1,
        "default",
        List.of(),
        advice,
        null,
        T0.plusSeconds(second),
        T0.plusSeconds(second));
  }

  private void put(final InputRequest request) {
    if (request.state() == InputState.OPEN) {
      store.createIfAbsent(request).block();
      return;
    }
    store
        .createIfAbsent(
            request(request.id(), request.sessionId(), request.spec(), InputState.OPEN, null, 0))
        .block();
    store.compareAndSet(request.id(), 0, request).block();
  }

  @BeforeEach
  void setUp() {
    when(controlBus.getProgress("s1", "e1"))
        .thenReturn(
            new WorkflowProgress(
                "e1", "s1", "wf", TaskStatus.RUNNING, List.of(), Instant.now(), null));
    when(controlBus.answerInput(
            anyString(), anyString(), any(), anyString(), any(), any(), any(), any()))
        .thenReturn(Mono.empty());
    when(controlBus.escalateInput(anyString(), anyString(), anyString(), any()))
        .thenReturn(Mono.empty());
    put(
        request(
            "r1",
            "s1",
            spec("r1", InputKind.CHOICE, "Which way?", null),
            InputState.OPEN,
            null,
            1));
  }

  @Test
  void listShowsTheWaitingRequestsWithTheirOptions() {
    put(
        request(
            "r2",
            "s1",
            spec("r2", InputKind.STRUCTURED, "Fill in", "{\"type\":\"object\"}"),
            InputState.ESCALATED,
            "a".repeat(1500),
            2));

    final PendingInputs inputs = provider.list("s1", null).block();

    assertThat(inputs.sessionId()).isEqualTo("s1");
    assertThat(inputs.states()).containsExactly("OPEN", "ESCALATED");
    assertThat(inputs.truncated()).isFalse();
    assertThat(inputs.inputs())
        .extracting(PendingInputs.PendingInput::requestId)
        .containsExactly("r2", "r1");
    final PendingInputs.PendingInput escalated = inputs.inputs().get(0);
    assertThat(escalated.advice()).hasSize(InputProvider.MAX_ADVICE + 1).endsWith("…");
    assertThat(escalated.responseSchema()).isEqualTo("{\"type\":\"object\"}");
    final PendingInputs.PendingInput open = inputs.inputs().get(1);
    assertThat(open.options()).containsExactly("ok (Go)", "no");
    assertThat(open.context()).isEqualTo("{\"branch\":\"main\"}");
    assertThat(open.advice()).isNull();
    assertThat(open.responseSchema()).isNull();
    assertThat(open.decision()).isNull();
    assertThat(open.kind()).isEqualTo("CHOICE");
    assertThat(open.risk()).isEqualTo("LOW");
    assertThat(open.prompt()).isEqualTo("Which way?");
  }

  @Test
  void listOfAllSessionsWithFinishedStatesShowsTheDecision() {
    put(
        request(
            "r3", "s2", spec("r3", InputKind.TEXT, "Why?", null), InputState.ANSWERED, null, 3));
    put(
        request(
            "r4", "s2", spec("r4", InputKind.TEXT, "Why?", null), InputState.ANSWERED, null, 4));
    store
        .saveResponse(
            new InputResponse(
                "r3",
                "ok",
                "t",
                Map.of(),
                new Responder(Responder.Type.HUMAN, "ann"),
                null,
                null,
                T0))
        .block();

    final PendingInputs inputs = provider.list(" ", "answered").block();

    assertThat(inputs.sessionId()).isNull();
    assertThat(inputs.inputs())
        .extracting(PendingInputs.PendingInput::requestId, PendingInputs.PendingInput::decision)
        .containsExactly(
            org.assertj.core.groups.Tuple.tuple("r4", null),
            org.assertj.core.groups.Tuple.tuple("r3", "ok"));
    assertThat(provider.list(null, null).block().inputs()).hasSize(1);
  }

  @Test
  void listIsBoundedAndCutsLongPrompts() {
    for (int i = 0; i < InputProvider.MAX_INPUTS + 5; i++) {
      put(
          request(
              "m" + i,
              "s3",
              spec("m" + i, InputKind.TEXT, "p".repeat(3000), null),
              InputState.OPEN,
              null,
              10 + i));
    }

    final PendingInputs inputs = provider.list("s3", "OPEN").block();

    assertThat(inputs.truncated()).isTrue();
    assertThat(inputs.inputs()).hasSize(InputProvider.MAX_INPUTS);
    assertThat(inputs.inputs().get(0).prompt()).hasSize(InputProvider.MAX_TEXT + 1);
  }

  @Test
  void listRefusesAnUnknownState() {
    StepVerifier.create(provider.list("s1", "DONE"))
        .expectErrorMatches(
            e -> e instanceof IllegalArgumentException && e.getMessage().contains("DONE"))
        .verify();
  }

  @Test
  void answerSendsTheCommandWithItsData() {
    final ControlActionResult result =
        provider.answer("s1", "e1", "r1", "ok", "fine", "{\"n\":1}", "why").block();

    assertThat(result.action()).isEqualTo("ANSWER_INPUT");
    assertThat(result.nodeId()).isEqualTo("gate");
    verify(controlBus).answerInput("e1", "r1", null, "ok", "fine", Map.of("n", 1), "why", null);
  }

  @Test
  void answerWithoutDataSendsNone() {
    provider.answer("s1", "e1", "r1", "ok", null, " ", null).block();
    provider.answer("s1", "e1", "r1", "no", null, null, null).block();

    verify(controlBus).answerInput("e1", "r1", null, "ok", null, Map.of(), null, null);
    verify(controlBus).answerInput("e1", "r1", null, "no", null, Map.of(), null, null);
  }

  @Test
  void answerRefusesMissingArgumentsAndBadData() {
    assertRefused(provider.answer("s1", null, "r1", "ok", null, null, null), "requestId");
    assertRefused(provider.answer("s1", " ", "r1", "ok", null, null, null), "requestId");
    assertRefused(provider.answer("s1", "e1", null, "ok", null, null, null), "requestId");
    assertRefused(provider.answer("s1", "e1", " ", "ok", null, null, null), "requestId");
    assertRefused(provider.answer("s1", "e1", "r1", null, null, null, null), "decision");
    assertRefused(provider.answer("s1", "e1", "r1", " ", null, null, null), "decision");
    assertRefused(provider.answer("s1", "e1", "r1", "ok", null, "[1]", null), "JSON object");
    assertRefused(provider.answer("s2", "e1", "r1", "ok", null, null, null), "Execution not found");
    verify(controlBus, never())
        .answerInput(anyString(), anyString(), any(), anyString(), any(), any(), any(), any());
  }

  private static void assertRefused(final Mono<?> call, final String text) {
    StepVerifier.create(call)
        .expectErrorMatches(
            e -> e instanceof IllegalArgumentException && e.getMessage().contains(text))
        .verify();
  }

  @Test
  void answerOfAnAnsweredRequestFails() {
    put(
        request(
            "r5", "s1", spec("r5", InputKind.TEXT, "Why?", null), InputState.ANSWERED, null, 5));

    StepVerifier.create(provider.answer("s1", "e1", "r5", "ok", null, null, null))
        .expectError(InputAnsweredException.class)
        .verify();
  }

  @Test
  void refusalByTheRulesNamesTheRule_otherFailuresPassThrough() {
    when(controlBus.answerInput(
            anyString(), anyString(), any(), anyString(), any(), any(), any(), any()))
        .thenReturn(Mono.error(new InputForbiddenException("r1", "risk", "HIGH needs a person")))
        .thenReturn(Mono.error(new InputRejectedException("r1", "Pick an option")));

    assertRefused(
        provider.answer("s1", "e1", "r1", "ok", null, null, null), "(rule risk): HIGH needs");
    StepVerifier.create(provider.answer("s1", "e1", "r1", "ok", null, null, null))
        .expectError(InputRejectedException.class)
        .verify();
  }

  @Test
  void escalateSendsTheCommand() {
    final ControlActionResult result =
        provider.escalate("s1", "e1", "r1", "not mine", "ask finance").block();

    assertThat(result.action()).isEqualTo("ESCALATE_INPUT");
    verify(controlBus).escalateInput("e1", "r1", "not mine", "ask finance");
  }

  @Test
  void escalateRefusesMissingArguments() {
    assertRefused(provider.escalate("s1", "e1", null, "x", null), "requestId");
    assertRefused(provider.escalate("s1", "e1", "r1", null, null), "reason");
    assertRefused(provider.escalate("s1", "e1", "r1", " ", null), "reason");
    verify(controlBus, never()).escalateInput(anyString(), anyString(), anyString(), any());
  }
}
