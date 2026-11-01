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
}
