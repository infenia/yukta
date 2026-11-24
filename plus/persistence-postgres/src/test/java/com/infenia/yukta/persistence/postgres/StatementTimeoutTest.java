// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactoryOptions;
import io.r2dbc.spi.Option;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * The postgres profile sets {@code spring.r2dbc.properties.statementTimeout: PT30S}; Boot hands
 * such a property to the driver as a string option. The driver must turn it into the session's own
 * {@code statement_timeout}, so the server stops a statement the stores no longer wait for.
 */
@NoArgsConstructor
class StatementTimeoutTest extends PostgresTestSupport {

  @Test
  void theStatementTimeoutOptionBecomesTheSessionsStatementTimeout() {
    final ConnectionFactoryOptions options =
        ConnectionFactoryOptions.parse(r2dbcUrl())
            .mutate()
            .option(Option.valueOf("statementTimeout"), "PT30S")
            .build();
    final DatabaseClient client = DatabaseClient.create(ConnectionFactories.get(options));

    assertThat(
            client.sql("SHOW statement_timeout").map(row -> row.get(0, String.class)).one().block())
        .isEqualTo("30s");
  }
}
