// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.springframework.r2dbc.core.DatabaseClient;

/**
 * The tables the size gauges name exist. The size query answers 0 for a name that is not a table,
 * so a misspelled one would otherwise show as an empty table for ever.
 */
@NoArgsConstructor
class TableSizesSchemaTest extends PostgresTestSupport {

  @Test
  void everyTableOfTheGaugesExists() {
    final DatabaseClient client = db;

    for (final String table : TableSizes.TABLES) {
      assertThat(
              client
                  .sql("SELECT to_regclass(:t)::text AS found")
                  .bind("t", table)
                  .map(row -> row.get("found", String.class))
                  .one()
                  .block())
          .as(table)
          .isEqualTo(table);
    }
  }

  @Test
  void tableOfTheGaugesHasSize() {
    final TableSizes sizes = new TableSizes(db, TableSizes.TABLES);

    final long bytes =
        org.awaitility.Awaitility.await()
            .atMost(java.time.Duration.ofSeconds(10))
            .until(() -> sizes.bytes("agent_event"), size -> size > 0);
    assertThat(bytes).isPositive();
  }
}
