// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import lombok.NoArgsConstructor;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

/**
 * The hot queries can use their index. Sequential scans are turned off, so the planner takes the
 * index whenever the query's predicate matches it: a query rewritten so that it no longer matches
 * (an expression on the column, a partial-index predicate not repeated) shows a Seq Scan here.
 * Literal values stand in for the bound parameters, as in a custom plan.
 */
@NoArgsConstructor
class QueryPlanTest extends PostgresTestSupport {

  private static String plan(final String sql) throws SQLException {
    try (Connection connection = jdbc();
        Statement statement = connection.createStatement()) {
      statement.execute("SET enable_seqscan = off");
      final StringBuilder plan = new StringBuilder();
      try (ResultSet rows = statement.executeQuery("EXPLAIN " + sql)) {
        while (rows.next()) {
          plan.append(rows.getString(1)).append('\n');
        }
      }
      return plan.toString();
    }
  }

  @ParameterizedTest(name = "{0}")
  @CsvSource(
      delimiter = '|',
      value = {
        "forExecution | execution_log_run | SELECT event FROM execution_log"
            + " WHERE execution_id = 'r1' ORDER BY seq",
        "ofType | execution_log_type | SELECT event FROM execution_log"
            + " WHERE type = 'IDEMPOTENCY_KEY' AND ts > now() ORDER BY seq",
        "newest across runs | execution_log_type | SELECT event FROM execution_log"
            + " WHERE type IN ('CONTROL_APPLIED', 'CONTROL_REJECTED') ORDER BY seq DESC LIMIT 5",
        "log trim | execution_log_run_edge | SELECT execution_id FROM execution_log"
            + " WHERE type IN ('RUN_FINISHED', 'RUN_ABORTED') GROUP BY execution_id",
        "dueBefore | input_request_due | SELECT id FROM input_request"
            + " WHERE state IN ('OPEN', 'ESCALATED') AND LEAST(deadline, tier_deadline) <= now()",
        "findAnsweredByKey | input_request_answered | SELECT id FROM input_request"
            + " WHERE req_key = 'k' AND state = 'ANSWERED' ORDER BY updated_at DESC LIMIT 1",
        "purge | input_request_purge | SELECT id FROM input_request"
            + " WHERE NOT (state IN ('OPEN', 'ESCALATED')) AND updated_at < now()",
        "checkpoint replay | node_checkpoint_node | SELECT message FROM node_checkpoint"
            + " WHERE execution_id = 'r1' AND node_id = 'n1' ORDER BY seq"
      })
  void theQueryUsesItsIndex(final String query, final String index, final String sql)
      throws SQLException {
    assertThat(plan(sql)).as(query).contains(index).doesNotContain("Seq Scan");
  }
}
