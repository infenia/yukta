// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.service.ConflictException;
import com.infenia.yukta.service.NotFoundException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import reactor.test.StepVerifier;

/** Tests for {@link InputRequestQueries}. */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class InputRequestQueriesTest {

  private static final Instant T0 = Instant.parse("2026-11-02T10:00:00Z");

  /** The store behind the queries. */
  private final InMemoryInputRequestStore store = new InMemoryInputRequestStore(10);

  /** The queries under test. */
  private final InputRequestQueries queries = new InputRequestQueries(store);

  private static InputRequest request(
      final String id,
      final String session,
      final String workflow,
      final String execution,
      final InputState state,
      final long version,
      final long secondsAfterStart) {
    return new InputRequest(
        id,
        "key-" + id,
        session,
        workflow,
        execution,
        "n",
        InputTestSupport.spec("key-" + id),
        state,
        version,
        "default",
        List.of(),
        null,
        null,
        T0.plusSeconds(secondsAfterStart),
        T0.plusSeconds(secondsAfterStart));
  }

  private void add(final String id, final String workflow, final long at) {
    store.createIfAbsent(request(id, "s1", workflow, "e1", InputState.OPEN, 0, at)).block();
  }

  private void finish(final String id, final InputState state) {
    final InputRequest current = store.findById(id).blockOptional().orElseThrow();
    store
        .compareAndSet(
            id,
            current.version(),
            request(
                id,
                current.sessionId(),
                current.workflowId(),
                current.executionId(),
                state,
                current.version() + 1,
                current.createdAt().getEpochSecond() - T0.getEpochSecond()))
        .block();
  }

  private static InputResponse response(final String requestId) {
    return new InputResponse(
        requestId,
        "approve",
        "fine",
        Map.of(),
        new Responder(Responder.Type.HUMAN, "key:alice"),
        null,
        null,
        T0);
  }

  @Test
  void statesDefaultToTheWaitingOnes_andAreReadCaseAndBlankInsensitively() {
    assertThat(InputRequestQueries.states(null)).isEqualTo(InputRequestQueries.WAITING);
    assertThat(InputRequestQueries.states(" ")).isEqualTo(InputRequestQueries.WAITING);
    assertThat(InputRequestQueries.states(" , ")).isEqualTo(InputRequestQueries.WAITING);
    assertThat(InputRequestQueries.states(" answered, Open,"))
        .containsExactlyInAnyOrder(InputState.ANSWERED, InputState.OPEN);
  }

  @Test
  void unknownStateIsRefusedWithTheValidNames() {
    assertThatThrownBy(() -> InputRequestQueries.states("OPEN,DONE"))
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("DONE")
        .hasMessageContaining("OPEN, ESCALATED, ANSWERED, EXPIRED, CANCELLED");
  }

  @Test
  void listIsNewestFirstAndFiltersByStateAndWorkflow() {
    add("a", "w1", 1);
    add("b", "w2", 2);
    add("c", "w1", 3);
    add("d", "w1", 3);
    finish("c", InputState.ANSWERED);

    StepVerifier.create(
            queries.list("s1", null, null, InputRequestQueries.WAITING).map(InputRequest::id))
        .expectNext("d", "b", "a")
        .verifyComplete();
    StepVerifier.create(
            queries
                .list(null, "w1", "e1", Set.of(InputState.ANSWERED, InputState.OPEN))
                .map(InputRequest::id))
        .expectNext("d", "c", "a")
        .verifyComplete();
    StepVerifier.create(queries.list("s1", null, null, Set.of(InputState.EXPIRED)))
        .verifyComplete();
  }

  @Test
  void requireFindsOnlyTheRequestsOfTheNamedRun() {
    add("a", "w1", 1);

    StepVerifier.create(queries.require("e1", "a").map(InputRequest::id))
        .expectNext("a")
        .verifyComplete();
    StepVerifier.create(queries.require("e2", "a"))
        .expectErrorMatches(
            e -> e instanceof NotFoundException && e.getMessage().contains("not found: a"))
        .verify();
    StepVerifier.create(queries.require("e1", "missing"))
        .expectError(NotFoundException.class)
        .verify();
  }

  @Test
  void answerIsReadOnlyForAnsweredRequests() {
    add("a", "w1", 1);
    add("b", "w1", 2);
    store.saveResponse(response("a")).block();
    store.saveResponse(response("b")).block();
    finish("a", InputState.ANSWERED);

    StepVerifier.create(
            queries
                .answer(store.findById("a").blockOptional().orElseThrow())
                .map(InputResponse::decision))
        .expectNext("approve")
        .verifyComplete();
    StepVerifier.create(queries.answer(store.findById("b").blockOptional().orElseThrow()))
        .verifyComplete();
  }

  @Test
  void requireWaitingPassesOpenRequests_andRefusesFinishedOnes() {
    add("open", "w1", 1);
    add("answered", "w1", 2);
    add("forgotten", "w1", 3);
    add("expired", "w1", 4);
    store.saveResponse(response("answered")).block();
    finish("answered", InputState.ANSWERED);
    finish("forgotten", InputState.ANSWERED);
    finish("expired", InputState.EXPIRED);

    StepVerifier.create(queries.requireWaiting("e1", "open").map(InputRequest::id))
        .expectNext("open")
        .verifyComplete();
    StepVerifier.create(queries.requireWaiting("e1", "answered"))
        .expectErrorSatisfies(
            e -> {
              assertThat(e).isInstanceOf(InputAnsweredException.class);
              final InputAnsweredException answered = (InputAnsweredException) e;
              assertThat(answered.requestId()).isEqualTo("answered");
              assertThat(answered.decision()).isEqualTo("approve");
              assertThat(answered.responder()).isEqualTo("HUMAN:key:alice");
              assertThat(answered.getMessage())
                  .isEqualTo(
                      "Input request answered is already answered: approve by HUMAN:key:alice");
            })
        .verify();
    StepVerifier.create(queries.requireWaiting("e1", "forgotten"))
        .expectErrorSatisfies(
            e -> {
              assertThat(e).isInstanceOf(InputAnsweredException.class);
              assertThat(((InputAnsweredException) e).decision()).isNull();
              assertThat(e.getMessage()).isEqualTo("Input request forgotten is already answered");
            })
        .verify();
    StepVerifier.create(queries.requireWaiting("e1", "expired"))
        .expectErrorMatches(
            e ->
                e instanceof ConflictException
                    && !(e instanceof InputAnsweredException)
                    && e.getMessage().endsWith("is EXPIRED"))
        .verify();
  }
}
