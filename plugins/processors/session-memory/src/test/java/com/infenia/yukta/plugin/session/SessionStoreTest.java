// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.file.Path;
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

  @Test
  void withDirectory_historySurvivesRestart(@org.junit.jupiter.api.io.TempDir final Path dir) {
    final SessionStore first = new SessionStore(dir.toString());
    first.append("s", "user", "q1", 10);
    first.append("s", "assistant", "a1", 10);

    final SessionStore restarted = new SessionStore(dir.toString());

    assertThat(restarted.history("s", 10))
        .containsExactly(
            Map.of("role", "user", "content", "q1"), Map.of("role", "assistant", "content", "a1"));
    restarted.append("s", "user", "q2", 2);
    assertThat(new SessionStore(dir.toString()).history("s", 10))
        .containsExactly(
            Map.of("role", "assistant", "content", "a1"), Map.of("role", "user", "content", "q2"));
  }

  @Test
  void withDirectory_sessionIdCannotEscape_andUnknownSessionsAreEmpty(
      @org.junit.jupiter.api.io.TempDir final Path dir) throws java.io.IOException {
    final SessionStore store = new SessionStore(dir.toString());
    store.append("../../evil", "user", "x", 5);

    assertThat(dir.getParent().resolve("evil.json")).doesNotExist();
    try (var files = java.nio.file.Files.list(dir)) {
      assertThat(files.map(f -> dir.relativize(f).toString()).toList())
          .containsExactly("..%2F..%2Fevil.json");
    }
    assertThat(store.history("other", 5)).isEmpty();
  }

  @Test
  void withDirectory_unreadableFileMeansEmptyHistory_andUnwritableKeepsMemory(
      @org.junit.jupiter.api.io.TempDir final Path dir) throws java.io.IOException {
    java.nio.file.Files.writeString(dir.resolve("broken.json"), "not json");
    final SessionStore store = new SessionStore(dir.toString());
    assertThat(store.history("broken", 5)).isEmpty();

    // the "directory" is a regular file, so nothing can be written beneath it
    final Path blocker = java.nio.file.Files.createFile(dir.resolve("blocker"));
    final SessionStore unwritable = new SessionStore(blocker.toString());
    unwritable.append("s", "user", "kept", 5);
    assertThat(unwritable.history("s", 5))
        .containsExactly(Map.of("role", "user", "content", "kept"));
  }
}
