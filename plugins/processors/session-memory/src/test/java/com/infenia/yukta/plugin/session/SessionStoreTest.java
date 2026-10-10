// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals"})
@NoArgsConstructor
class SessionStoreTest {

  private final SessionStore store = new SessionStore();

  @Test
  void unknownSessionIsEmpty() {
    assertThat(store.history("nope", 5)).isEmpty();
  }

  @Test
  void keepsOrderAndWindowsHistory() {
    store.append("s", "user", "q1", 10);
    store.append("s", "assistant", "a1", 10);
    store.append("s", "user", "q2", 10);

    assertThat(store.history("s", 10))
        .containsExactly(
            Map.of("role", "user", "content", "q1"),
            Map.of("role", "assistant", "content", "a1"),
            Map.of("role", "user", "content", "q2"));
    assertThat(store.history("s", 2))
        .containsExactly(
            Map.of("role", "assistant", "content", "a1"), Map.of("role", "user", "content", "q2"));
    assertThat(store.history("s", 0)).isEmpty();
  }

  @Test
  void trimsOldestBeyondMaxTurns() {
    store.append("s", "user", "q1", 2);
    store.append("s", "assistant", "a1", 2);
    store.append("s", "user", "q2", 2);

    assertThat(store.history("s", 10))
        .containsExactly(
            Map.of("role", "assistant", "content", "a1"), Map.of("role", "user", "content", "q2"));
  }

  @Test
  void sessionsAreIsolatedAndHistoryIsImmutable() {
    store.append("a", "user", "x", 5);
    store.append("b", "user", "y", 5);

    final List<Map<String, String>> history = store.history("a", 5);

    assertThat(history).hasSize(1);
    assertThat(store.history("b", 5)).containsExactly(Map.of("role", "user", "content", "y"));
    assertThatThrownBy(() -> history.add(Map.of()))
        .isInstanceOf(UnsupportedOperationException.class);
  }
}
