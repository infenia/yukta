// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.plus.testkit.NotificationStoreContract;
import com.infenia.yukta.service.notify.NotificationStore;
import lombok.NoArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** The shared notification store contract, run against a real PostgreSQL. */
@NoArgsConstructor
@SuppressWarnings("PMD.TestClassWithoutTestCases") // the tests are inherited
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
}
