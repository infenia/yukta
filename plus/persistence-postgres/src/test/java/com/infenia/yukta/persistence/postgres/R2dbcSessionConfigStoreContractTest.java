// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import com.infenia.yukta.plus.testkit.SessionConfigStoreContract;
import com.infenia.yukta.service.sessionstore.SessionConfigStore;
import lombok.NoArgsConstructor;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

/** The shared session store contract, run against a real PostgreSQL. */
@NoArgsConstructor
@SuppressWarnings("PMD.TestClassWithoutTestCases") // the tests are inherited
@Testcontainers(disabledWithoutDocker = true)
class R2dbcSessionConfigStoreContractTest extends SessionConfigStoreContract {

  @Override
  protected SessionConfigStore create() {
    final DatabaseClient db = PostgresTestSupport.prepared();
    for (final String table :
        new String[] {"session_config", "workflow_definition_revision", "workflow_definition"}) {
      db.sql("DELETE FROM " + table).fetch().rowsUpdated().block();
    }
    final JsonMapper mapper = JsonMapper.builder().build();
    return new R2dbcSessionConfigStore(db, mapper, new R2dbcWorkflowDefinitionStore(db, mapper));
  }
}
