// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.plugin.session;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.github.benmanes.caffeine.cache.Ticker;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
@NoArgsConstructor
class SessionStoreTest {

  private final SessionStore store = new SessionStore();

  @Test
  void inMemoryStoreKeepsAtMostMaxSessions() {
    final SessionStore bounded = new SessionStore(3, null, Ticker.systemTicker());

    for (int i = 0; i < 10; i++) {
      bounded.append("s" + i, "user", "q", 5);
    }

    assertThat(bounded.sessionCount()).isLessThanOrEqualTo(3L);
  }

  @Test
  void idleSessionsLeaveMemoryAfterTheTtl() {
    final AtomicLong now = new AtomicLong();
    final SessionStore expiring = new SessionStore(100, Duration.ofMinutes(10), now::get);
    expiring.append("s", "user", "q", 5);

    now.addAndGet(Duration.ofMinutes(9).toNanos());
    assertThat(expiring.history("s", 5)).as("used 9 minutes ago").hasSize(1);
    now.addAndGet(Duration.ofMinutes(9).toNanos());
    assertThat(expiring.history("s", 5)).as("the read 9 minutes ago kept it").hasSize(1);
    now.addAndGet(Duration.ofMinutes(11).toNanos());
    assertThat(expiring.history("s", 5)).as("idle for 11 minutes").isEmpty();
  }

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

  /** A database that keeps the turns in a map. */
  private static class FakeDatabase implements com.infenia.yukta.plugin.store.SessionHistoryStore {
    private final Map<String, List<Map<String, String>>> rows =
        new java.util.concurrent.ConcurrentHashMap<>();

    @Override
    public java.util.Optional<List<Map<String, String>>> load(final String sessionId) {
      return java.util.Optional.ofNullable(rows.get(sessionId));
    }

    @Override
    public void save(final String sessionId, final List<Map<String, String>> turns) {
      rows.put(sessionId, turns);
    }

    @Override
    public void delete(final String sessionId) {
      rows.remove(sessionId);
    }
  }

  @Test
  void database_keepsHistoryForFreshStore() {
    final FakeDatabase database = new FakeDatabase();
    new SessionStore(database).append("s1", "user", "hello", 10);

    final SessionStore restarted = new SessionStore(database);

    assertThat(restarted.history("s1", 10))
        .extracting(turn -> turn.get("content"))
        .containsExactly("hello");
    assertThat(database.rows).containsKey("s1");
  }

  @Test
  void delete_forgetsMemoryAndTellsTheDatabase() {
    final List<String> deleted = new java.util.ArrayList<>();
    final FakeDatabase database =
        new FakeDatabase() {
          @Override
          public void delete(final String sessionId) {
            deleted.add(sessionId);
          }
        };
    final SessionStore store = new SessionStore(database);
    store.append("s1", "user", "hello", 10);

    store.delete("s1");

    assertThat(deleted).containsExactly("s1");
    assertThat(store.sessionCount()).isZero();
  }

  @Test
  void delete_removesTheStoredRows() {
    final FakeDatabase database = new FakeDatabase();
    final SessionStore store = new SessionStore(database);
    store.append("s1", "user", "hello", 10);

    store.delete("s1");
    new SessionStore().delete("unknown");

    assertThat(store.sessionCount()).isZero();
    assertThat(database.rows).doesNotContainKey("s1");
  }

  @Test
  void appendOfSeveralTurnsKeepsOrderAndWindow() {
    final SessionStore store = new SessionStore();
    store.append(
        "s1",
        List.of(
            Map.of("role", "user", "content", "a"), Map.of("role", "assistant", "content", "b")),
        1);

    assertThat(store.history("s1", 10)).extracting(t -> t.get("content")).containsExactly("b");
  }

  @Test
  void springConstructor_prefersDatabaseThenMemory() {
    final FakeDatabase database = new FakeDatabase();
    final org.springframework.beans.factory.support.StaticListableBeanFactory withDatabase =
        new org.springframework.beans.factory.support.StaticListableBeanFactory();
    withDatabase.addBean("db", database);
    final org.springframework.beans.factory.support.StaticListableBeanFactory none =
        new org.springframework.beans.factory.support.StaticListableBeanFactory();

    new SessionStore(
            withDatabase.getBeanProvider(com.infenia.yukta.plugin.store.SessionHistoryStore.class),
            100,
            null)
        .append("a", "user", "x", 5);
    assertThat(database.rows).containsOnlyKeys("a");

    final SessionStore memory =
        new SessionStore(
            none.getBeanProvider(com.infenia.yukta.plugin.store.SessionHistoryStore.class),
            100,
            null);
    memory.append("c", "user", "z", 5);
    assertThat(memory.history("c", 5)).hasSize(1);
  }
}
