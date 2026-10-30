// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

@NoArgsConstructor
@ExpectedErrorLog("ERROR com.infenia.yukta.persistence.postgres.R2dbcSessionHistoryStore - ")
class R2dbcSessionHistoryStoreTest extends PostgresTestSupport {

  private static final List<Map<String, String>> TURNS =
      List.of(
          Map.of("role", "user", "content", "hi"), Map.of("role", "assistant", "content", "yo"));

  @Test
  void historyIsStoredAndReplaced() {
    final R2dbcSessionHistoryStore store =
        new R2dbcSessionHistoryStore(db, new ObjectMapper(), null);
    assertThat(store.load("s1")).isEmpty();

    store.save("s1", TURNS);
    assertThat(store.load("s1")).contains(TURNS);

    store.save("s1", TURNS.subList(1, 2));
    assertThat(store.load("s1")).contains(TURNS.subList(1, 2));
  }

  @Test
  void sessionPastTtl_isDeletedWhenAnotherIsWritten() {
    final R2dbcSessionHistoryStore store =
        new R2dbcSessionHistoryStore(db, new ObjectMapper(), Duration.ofHours(1));
    store.save("old", TURNS);
    db.sql("UPDATE session_history SET updated_at = now() - interval '2 hours'")
        .fetch()
        .rowsUpdated()
        .block();

    store.save("new", TURNS);

    assertThat(store.load("old")).isEmpty();
    assertThat(store.load("new")).isPresent();
  }

  @Test
  void deletedHistory_isGone() {
    final R2dbcSessionHistoryStore store =
        new R2dbcSessionHistoryStore(db, new ObjectMapper(), null);
    store.save("s1", TURNS);
    store.save("s2", TURNS);

    store.delete("s1");

    assertThat(store.load("s1")).isEmpty();
    assertThat(store.load("s2")).contains(TURNS);
  }

  @Test
  void anUnreachableDatabase_meansEmptyHistory_andNoFailure() {
    final R2dbcSessionHistoryStore store =
        new R2dbcSessionHistoryStore(db, new ObjectMapper(), null);
    db.sql("ALTER TABLE session_history RENAME TO session_history_gone")
        .fetch()
        .rowsUpdated()
        .block();
    try {
      store.save("s1", TURNS);
      store.delete("s1");
      assertThat(store.load("s1")).isEmpty();
    } finally {
      db.sql("ALTER TABLE session_history_gone RENAME TO session_history")
          .fetch()
          .rowsUpdated()
          .block();
    }
  }

  @Test
  void interruptedCallers_getEmptyHistory_andKeepTheFlag() {
    final R2dbcSessionHistoryStore store =
        new R2dbcSessionHistoryStore(db, new ObjectMapper(), null);
    Thread.currentThread().interrupt();
    try {
      assertThat(store.load("s1")).isEmpty();
      assertThat(Thread.interrupted()).isTrue();
      Thread.currentThread().interrupt();
      store.save("s1", TURNS);
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
      Thread.interrupted();
      Thread.currentThread().interrupt();
      store.delete("s1");
      assertThat(Thread.currentThread().isInterrupted()).isTrue();
    } finally {
      Thread.interrupted();
    }
  }
}
