// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.plugin.notify.Notification;
import com.infenia.yukta.plugin.notify.NotificationKind;
import com.infenia.yukta.plugin.notify.NotificationSeverity;
import com.infenia.yukta.plus.testkit.NotificationStoreContract;
import com.infenia.yukta.service.notify.NotificationStore;
import java.time.Instant;
import java.util.List;
import lombok.NoArgsConstructor;
import lombok.SneakyThrows;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** The shared notification store contract, run against a real PostgreSQL. */
@NoArgsConstructor
@Testcontainers(disabledWithoutDocker = true)
class R2dbcNotificationStoreContractTest extends NotificationStoreContract {

  private DatabaseClient db;

  @Override
  protected NotificationStore create() {
    db = PostgresTestSupport.prepared();
    for (final String table :
        new String[] {
          "notification_deliveries",
          "notification_receipts",
          "notification_preferences",
          "notifications"
        }) {
      db.sql("DELETE FROM " + table).fetch().rowsUpdated().block();
    }
    return new R2dbcNotificationStore(db, JsonMapper.builder().build());
  }

  @Override
  protected NotificationStore restarted(final NotificationStore first) {
    return new R2dbcNotificationStore(db, JsonMapper.builder().build());
  }

  @Test
  void upsertGivesUpWhenAnotherWriterChangesTheKeyOnEveryTry() {
    final NotificationStore store = create();
    final Instant t = Instant.parse("2026-11-15T10:00:00Z");
    final Notification first =
        new Notification(
            "",
            "k",
            NotificationKind.INFO,
            NotificationSeverity.INFO,
            "workflow",
            "T",
            null,
            null,
            null,
            null,
            null,
            null,
            List.of("role:VIEWER"),
            Notification.State.ACTIVE,
            null,
            1,
            0,
            t,
            t);
    store.upsert("k", current -> current.orElse(first)).block();

    // each try reads the key, another writer moves it, so the write of this try finds a new seq
    assertThatThrownBy(() ->
            store
                .upsert(
                    "k",
                    current -> {
                      moveTheKey();
                      return retitled(current.orElseThrow(), "mine");
                    })
                .block())
        .hasMessageContaining("kept changing");
  }

  /** Another writer: takes a new seq for the key over its own JDBC connection. */
  @SneakyThrows
  private static void moveTheKey() {
    try (java.sql.Connection c = PostgresTestSupport.jdbc();
        java.sql.Statement st = c.createStatement()) {
      st.executeUpdate("UPDATE notifications SET seq = nextval('notification_seq')");
    }
  }

  private static Notification retitled(final Notification n, final String title) {
    return new Notification(
        n.id(),
        n.key(),
        n.kind(),
        n.severity(),
        n.category(),
        title,
        n.body(),
        n.sessionId(),
        n.workflowId(),
        n.executionId(),
        n.nodeId(),
        n.inputRequestId(),
        n.audience(),
        n.state(),
        n.resolution(),
        n.count(),
        n.seq(),
        n.createdAt(),
        n.updatedAt());
  }
}
