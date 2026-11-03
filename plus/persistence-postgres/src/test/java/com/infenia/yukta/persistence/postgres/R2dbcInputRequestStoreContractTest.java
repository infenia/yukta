// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.plus.testkit.InputRequestStoreContract;
import com.infenia.yukta.service.input.InputRequestStore;
import lombok.NoArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** The shared input request store contract, run against a real PostgreSQL. */
@NoArgsConstructor
@SuppressWarnings("PMD.TestClassWithoutTestCases") // the tests are inherited
@Testcontainers(disabledWithoutDocker = true)
class R2dbcInputRequestStoreContractTest extends InputRequestStoreContract {

  @Override
  protected InputRequestStore create() {
    final DatabaseClient db = PostgresTestSupport.prepared();
    for (final String table : new String[] {"input_delivery", "input_response", "input_request"}) {
      db.sql("DELETE FROM " + table).fetch().rowsUpdated().block();
    }
    return new R2dbcInputRequestStore(db, JsonMapper.builder().build());
  }
}
