// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plus.testkit;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.plugin.input.Audience;
import com.infenia.yukta.plugin.input.InputKind;
import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputRequestSpec;
import com.infenia.yukta.plugin.input.InputResponse;
import com.infenia.yukta.plugin.input.InputRisk;
import com.infenia.yukta.plugin.input.InputState;
import com.infenia.yukta.plugin.input.Responder;
import com.infenia.yukta.plugin.input.TimeoutAction;
import com.infenia.yukta.service.input.InputRequestStore;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.jspecify.annotations.Nullable;
import org.junit.jupiter.api.Test;

/**
 * What every {@link InputRequestStore} must do. Extend it in the test of an implementation and
 * return a store that starts empty from {@link #create()}.
 */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
public abstract class InputRequestStoreContract {

  private static final Instant T0 = Instant.parse("2026-11-02T10:00:00Z");

  /** Creates the contract. */
  protected InputRequestStoreContract() {
    super();
  }

  /**
   * A new, empty store.
   *
   * @return the store under test
   */
  protected abstract InputRequestStore create();

  private static InputRequest request(
      final String id, final String key, final String session, final String execution) {
    return request(id, key, session, execution, InputState.OPEN, 0, null, T0);
  }

  private static InputRequest request(
      final String id,
      final String key,
      final String session,
      final String execution,
      final InputState state,
      final long version,
      final @Nullable Instant deadline,
      final Instant updatedAt) {
    final InputRequestSpec spec =
        new InputRequestSpec(
            key,
            InputKind.TEXT,
            "Which way?",
            Map.of(),
            List.of(),
            null,
            InputRisk.LOW,
            Audience.CURRENT_TIER,
            Duration.ofMinutes(10),
            TimeoutAction.FAIL,
            null,
            null,
            "TEST");
    return new InputRequest(
        id, key, session, "w", execution, "n", spec, state, version, "default", List.of(), null,
        deadline, T0, updatedAt);
  }

  private static InputRequest next(final InputRequest from, final InputState state) {
    return request(
        from.id(),
        from.key(),
        from.sessionId(),
        from.executionId(),
        state,
        from.version() + 1,
        from.deadline(),
        T0.plusSeconds(5));
  }

  private static InputResponse response(final String requestId, final String by) {
    return new InputResponse(
        requestId, "go", null, Map.of(), new Responder(Responder.Type.HUMAN, by), null, null, T0);
  }

  @Test
  void requestCanBeReadBack() {
    final InputRequestStore store = create();
    final InputRequest request = request("r1", "k1", "s", "e");

    assertThat(store.createIfAbsent(request).block()).isEqualTo(request);

    assertThat(store.findById("r1").block()).isEqualTo(request);
    assertThat(store.findById("missing").block()).isNull();
  }

  @Test
  void keyIsUniqueAmongNonTerminalRequests() {
    final InputRequestStore store = create();
    final InputRequest first = request("r1", "k1", "s", "e");
    store.createIfAbsent(first).block();

    final InputRequest second = store.createIfAbsent(request("r2", "k1", "s", "e")).block();

    assertThat(second).isEqualTo(first);
    assertThat(store.findById("r2").block()).isNull();
  }

  @Test
  void keyCanBeUsedAgainOnceItsRequestIsTerminal() {
    final InputRequestStore store = create();
    final InputRequest first = request("r1", "k1", "s", "e");
    store.createIfAbsent(first).block();
    store.compareAndSet("r1", 0, next(first, InputState.CANCELLED)).block();

    final InputRequest again = request("r2", "k1", "s", "e");

    assertThat(store.createIfAbsent(again).block()).isEqualTo(again);
  }

  @Test
  void compareAndSetSucceedsOnlyAtTheExpectedVersion() {
    final InputRequestStore store = create();
    final InputRequest first = request("r1", "k1", "s", "e");
    store.createIfAbsent(first).block();
    final InputRequest second = next(first, InputState.ESCALATED);

    assertThat(store.compareAndSet("r1", 5, second).block()).isFalse();
    assertThat(store.compareAndSet("r1", 0, second).block()).isTrue();
    assertThat(store.compareAndSet("r1", 0, next(first, InputState.ANSWERED)).block()).isFalse();

    assertThat(store.findById("r1").block()).isEqualTo(second);
    assertThat(store.compareAndSet("missing", 0, request("missing", "k", "s", "e")).block())
        .isFalse();
  }

  @Test
  void terminalRequestNeverChanges() {
    final InputRequestStore store = create();
    final InputRequest first = request("r1", "k1", "s", "e");
    store.createIfAbsent(first).block();
    final InputRequest cancelled = next(first, InputState.CANCELLED);
    store.compareAndSet("r1", 0, cancelled).block();

    assertThat(store.compareAndSet("r1", 1, next(cancelled, InputState.ANSWERED)).block())
        .isFalse();
    assertThat(store.findById("r1").block().state()).isEqualTo(InputState.CANCELLED);
  }

  @Test
  void theNewestAnsweredRequestOfKeyIsFoundForResume() {
    final InputRequestStore store = create();
    assertThat(store.findAnsweredByKey("k1").block()).isNull();

    final InputRequest first = request("r1", "k1", "s", "e");
    store.createIfAbsent(first).block();
    assertThat(store.findAnsweredByKey("k1").block()).isNull();

    store.compareAndSet("r1", 0, next(first, InputState.ANSWERED)).block();
    assertThat(store.findAnsweredByKey("k1").block().id()).isEqualTo("r1");
    assertThat(store.findAnsweredByKey("other").block()).isNull();
  }

  @Test
  void theFirstResponseOfRequestIsTheOneThatStays() {
    final InputRequestStore store = create();
    final InputResponse first = response("r1", "ann");

    assertThat(store.saveResponse(first).block()).isEqualTo(first);
    assertThat(store.saveResponse(response("r1", "bob")).block()).isEqualTo(first);

    assertThat(store.findResponse("r1").block()).isEqualTo(first);
    assertThat(store.findResponse("none").block()).isNull();
  }

  @Test
  void openRequestsAreListedByRunAndSession() {
    final InputRequestStore store = create();
    store.createIfAbsent(request("r1", "k1", "s1", "e1")).block();
    store.createIfAbsent(request("r2", "k2", "s1", "e2")).block();
    store.createIfAbsent(request("r3", "k3", "s2", "e3")).block();
    final InputRequest done = request("r4", "k4", "s1", "e1");
    store.createIfAbsent(done).block();
    store.compareAndSet("r4", 0, next(done, InputState.CANCELLED)).block();

    assertThat(ids(store.listOpen(null, null).collectList().block()))
        .containsExactlyInAnyOrder("r1", "r2", "r3");
    assertThat(ids(store.listOpen("s1", null).collectList().block()))
        .containsExactlyInAnyOrder("r1", "r2");
    assertThat(ids(store.listOpen(null, "e1").collectList().block())).containsExactly("r1");
    assertThat(ids(store.listOpen("s2", "e1").collectList().block())).isEmpty();
  }

  @Test
  void dueRequestsAreThoseNonTerminalAtOrPastTheirDeadline() {
    final InputRequestStore store = create();
    store.createIfAbsent(request("past", "k1", "s", "e", InputState.OPEN, 0, T0, T0)).block();
    store
        .createIfAbsent(
            request("later", "k2", "s", "e", InputState.OPEN, 0, T0.plusSeconds(60), T0))
        .block();
    store.createIfAbsent(request("none", "k3", "s", "e")).block();

    assertThat(ids(store.dueBefore(T0).collectList().block())).containsExactly("past");
    assertThat(ids(store.dueBefore(T0.plusSeconds(61)).collectList().block()))
        .containsExactlyInAnyOrder("past", "later");
  }

  @Test
  void purgeRemovesOnlyOldFinishedRequestsAndTheirAnswers() {
    final InputRequestStore store = create();
    final InputRequest old = request("old", "k1", "s", "e");
    store.createIfAbsent(old).block();
    store.compareAndSet("old", 0, next(old, InputState.ANSWERED)).block();
    store.saveResponse(response("old", "ann")).block();
    store.createIfAbsent(request("open", "k2", "s", "e")).block();

    assertThat(store.purgeTerminalOlderThan(T0.plusSeconds(1)).block()).isZero();
    assertThat(store.purgeTerminalOlderThan(T0.plusSeconds(10)).block()).isEqualTo(1L);

    assertThat(store.findById("old").block()).isNull();
    assertThat(store.findResponse("old").block()).isNull();
    assertThat(store.findById("open").block()).isNotNull();
  }

  private static List<String> ids(final @Nullable List<InputRequest> requests) {
    return requests == null ? List.of() : requests.stream().map(InputRequest::id).toList();
  }
}
