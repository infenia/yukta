// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.service.input;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.input.InputRequest;
import com.infenia.yukta.plugin.input.InputState;
import java.time.Instant;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** The bound of the in-memory store; what every store does is in the shared contract (plus). */
@SuppressWarnings({"PMD.TooManyMethods", "PMD.AvoidDuplicateLiterals"})
@NoArgsConstructor
class InMemoryInputRequestStoreTest {

  private static final Instant T0 = Instant.parse("2026-11-02T10:00:00Z");

  private static InputRequest request(final String id, final InputState state) {
    return request(id, state, 0);
  }

  private static InputRequest request(final String id, final InputState state, final long version) {
    return new InputRequest(
        id,
        "key-" + id,
        "s",
        "w",
        "e",
        "n",
        InputTestSupport.spec("key-" + id),
        state,
        version,
        "default",
        List.of(),
        null,
        null,
        T0,
        T0);
  }

  @Test
  void theOldestFinishedRequestsAreEvictedBeyondTheBound() {
    final InMemoryInputRequestStore store = new InMemoryInputRequestStore(2);
    for (final String id : List.of("a", "b", "c")) {
      store.createIfAbsent(request(id, InputState.OPEN)).block();
      store.compareAndSet(id, 0, request(id, InputState.CANCELLED, 1)).block();
    }

    assertThat(store.findById("a").block()).isNull();
    assertThat(store.findById("b").block()).isNotNull();
    assertThat(store.findById("c").block()).isNotNull();
  }

  @Test
  void requestThatJustFinishedIsNotEvictedBeforeOlderFinishedOnes() {
    final InMemoryInputRequestStore store = new InMemoryInputRequestStore(2);
    for (final String id : List.of("a", "b", "c")) {
      store.createIfAbsent(request(id, InputState.OPEN)).block();
    }
    // "a" was created first but finishes last
    for (final String id : List.of("b", "c", "a")) {
      store.compareAndSet(id, 0, request(id, InputState.CANCELLED, 1)).block();
    }

    assertThat(store.findById("b").block()).isNull();
    assertThat(store.findById("c").block()).isNotNull();
    assertThat(store.findById("a").block()).isNotNull();
  }

  @Test
  void openRequestsAreNeverEvicted() {
    final InMemoryInputRequestStore store = new InMemoryInputRequestStore(1);
    store.createIfAbsent(request("open", InputState.OPEN)).block();
    for (final String id : List.of("a", "b")) {
      store.createIfAbsent(request(id, InputState.OPEN)).block();
      store.compareAndSet(id, 0, request(id, InputState.CANCELLED, 1)).block();
    }

    assertThat(store.findById("open").block()).isNotNull();
    assertThat(store.findById("a").block()).isNull();
  }

  @Test
  void boundBelowOneIsRefused() {
    assertThatThrownBy(() -> new InMemoryInputRequestStore(0))
        .isInstanceOf(IllegalArgumentException.class);
  }

  private static InputRequest full(
      final String id,
      final String session,
      final String execution,
      final InputState state,
      final Instant deadline,
      final Instant updatedAt) {
    return new InputRequest(
        id,
        "key-" + id,
        session,
        "w",
        execution,
        "n",
        InputTestSupport.spec("key-" + id),
        state,
        0,
        "default",
        List.of(),
        null,
        deadline,
        T0,
        updatedAt);
  }

  @Test
  void compareAndSetRefusesUnknownIdFinishedRequestAndChangedId() {
    final InMemoryInputRequestStore store = new InMemoryInputRequestStore(5);
    store.createIfAbsent(request("a", InputState.OPEN)).block();
    store.createIfAbsent(request("done", InputState.OPEN)).block();
    store.compareAndSet("done", 0, request("done", InputState.CANCELLED, 1)).block();

    assertThat(store.compareAndSet("none", 0, request("none", InputState.OPEN, 1)).block())
        .isFalse();
    assertThat(store.compareAndSet("done", 1, request("done", InputState.OPEN, 2)).block())
        .isFalse();
    assertThat(store.compareAndSet("a", 0, request("other", InputState.OPEN, 1)).block()).isFalse();
    assertThat(store.compareAndSet("a", 9, request("a", InputState.OPEN, 10)).block()).isFalse();
    assertThat(store.compareAndSet("a", 0, request("a", InputState.OPEN, 1)).block()).isTrue();
  }

  @Test
  void listOpenFiltersBySessionAndExecutionAndSkipsFinishedRequests() {
    final InMemoryInputRequestStore store = new InMemoryInputRequestStore(5);
    store.createIfAbsent(full("a", "s1", "e1", InputState.OPEN, null, T0)).block();
    store.createIfAbsent(full("b", "s1", "e2", InputState.OPEN, null, T0)).block();
    store.createIfAbsent(full("c", "s2", "e3", InputState.OPEN, null, T0)).block();
    store.createIfAbsent(full("d", "s1", "e1", InputState.OPEN, null, T0)).block();
    store.compareAndSet("d", 0, full("d", "s1", "e1", InputState.CANCELLED, null, T0)).block();

    assertThat(store.listOpen(null, null).map(InputRequest::id).collectList().block())
        .containsExactly("a", "b", "c");
    assertThat(store.listOpen("s1", null).map(InputRequest::id).collectList().block())
        .containsExactly("a", "b");
    assertThat(store.listOpen(null, "e3").map(InputRequest::id).collectList().block())
        .containsExactly("c");
    assertThat(store.listOpen("s1", "e2").map(InputRequest::id).collectList().block())
        .containsExactly("b");
    assertThat(store.listOpen("s2", "e1").collectList().block()).isEmpty();
  }

  @Test
  void dueBeforeReturnsOnlyOpenRequestsWhoseDeadlineHasPassed() {
    final InMemoryInputRequestStore store = new InMemoryInputRequestStore(5);
    final Instant now = T0.plusSeconds(60);
    store.createIfAbsent(full("late", "s", "e", InputState.OPEN, T0.plusSeconds(10), T0)).block();
    store.createIfAbsent(full("edge", "s", "e", InputState.OPEN, now, T0)).block();
    store.createIfAbsent(full("later", "s", "e", InputState.OPEN, now.plusSeconds(1), T0)).block();
    store.createIfAbsent(full("never", "s", "e", InputState.OPEN, null, T0)).block();
    store.createIfAbsent(full("over", "s", "e", InputState.OPEN, T0.plusSeconds(5), T0)).block();
    store
        .compareAndSet(
            "over", 0, full("over", "s", "e", InputState.CANCELLED, T0.plusSeconds(5), T0))
        .block();

    assertThat(store.dueBefore(now).map(InputRequest::id).collectList().block())
        .containsExactly("late", "edge");
  }

  @Test
  void purgeRemovesOnlyFinishedRequestsOlderThanTheCutoff() {
    final InMemoryInputRequestStore store = new InMemoryInputRequestStore(5);
    final Instant cutoff = T0.plusSeconds(100);
    store.createIfAbsent(full("old", "s", "e", InputState.OPEN, null, T0)).block();
    store.createIfAbsent(full("fresh", "s", "e", InputState.OPEN, null, T0)).block();
    store.createIfAbsent(full("open", "s", "e", InputState.OPEN, null, T0)).block();
    store.compareAndSet("old", 0, full("old", "s", "e", InputState.CANCELLED, null, T0)).block();
    store
        .compareAndSet(
            "fresh", 0, full("fresh", "s", "e", InputState.CANCELLED, null, cutoff.plusSeconds(1)))
        .block();

    assertThat(store.purgeTerminalOlderThan(cutoff).block()).isEqualTo(1L);

    assertThat(store.findById("old").block()).isNull();
    assertThat(store.findById("fresh").block()).isNotNull();
    assertThat(store.findById("open").block()).isNotNull();
  }

  @Test
  void secondCreateWithTheSameOpenKeyReturnsTheFirstRequest() {
    final InMemoryInputRequestStore store = new InMemoryInputRequestStore(5);
    final InputRequest first = request("a", InputState.OPEN);

    assertThat(store.createIfAbsent(first).block()).isEqualTo(first);
    assertThat(store.createIfAbsent(first).block()).isEqualTo(first);

    assertThat(store.listOpen(null, null).collectList().block()).hasSize(1);
  }
}
