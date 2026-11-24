// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.plus.testkit.ExecutionLogStoreContract;
import com.infenia.yukta.service.log.ExecutionLogStore;
import java.util.ArrayList;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterEach;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** The shared execution log contract, run against a real PostgreSQL. */
@NoArgsConstructor
@SuppressWarnings("PMD.TestClassWithoutTestCases") // the tests are inherited
@Testcontainers(disabledWithoutDocker = true)
class R2dbcExecutionLogStoreContractTest extends ExecutionLogStoreContract {

  /** The stores a test made, stopped after it so their pipelines end. */
  private final List<R2dbcExecutionLogStore> created = new ArrayList<>();

  @Override
  protected ExecutionLogStore create() {
    final DatabaseClient db = PostgresTestSupport.prepared();
    db.sql("DELETE FROM execution_log").fetch().rowsUpdated().block();
    final R2dbcExecutionLogStore store =
        new R2dbcExecutionLogStore(db, JsonMapper.builder().build(), 500, 10_000, 5_000);
    created.add(store);
    return store;
  }

  @AfterEach
  void stopStores() {
    created.forEach(R2dbcExecutionLogStore::stop);
  }
}
