// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.infenia.yukta.testsupport.ExpectedErrorLog;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import lombok.NoArgsConstructor;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

/** The schema: a migration that fails halfway leaves it as it was, and a fresh one is complete. */
@NoArgsConstructor
@ExpectedErrorLog("ERROR org.flywaydb.core.internal.command.DbMigrate - ")
class MigrationUpgradeTest extends PostgresTestSupport {

  /** Points a session at the schema under test. */
  private static final String SET_PATH = "SET search_path TO ";

  /** A stored workflow definition. */
  private static final String DEFINITION =
      "{\"workflowId\":\"w1\",\"description\":\"d\",\"nodes\":[],\"edges\":[]}";

  private static String scalar(final Statement statement, final String sql) throws SQLException {
    try (ResultSet rows = statement.executeQuery(sql)) {
      return rows.next() ? rows.getString(1) : null;
    }
  }

  private static String regclass(final Connection connection, final String table)
      throws SQLException {
    try (java.sql.PreparedStatement exists =
        connection.prepareStatement("SELECT to_regclass(?)::text"); ) {
      exists.setString(1, table);
      try (ResultSet rows = exists.executeQuery()) {
        return rows.next() ? rows.getString(1) : null;
      }
    }
  }

  /** PostgreSQL runs each migration in one transaction, so a bad one rolls itself back. */
  @Test
  void failedMigrationRollsBackCleanly() throws SQLException {
    final String schema = "rollback_test";
    try (Connection connection = jdbc();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
    flywayFor(schema).load().migrate();
    try (Connection connection = jdbc();
        Statement statement = connection.createStatement()) {
      statement.execute(SET_PATH + schema);
      statement.execute(
          "INSERT INTO workflow_definition (session_id, workflow_id, definition)"
              + " VALUES ('s1', 'w1', '"
              + DEFINITION
              + "')");
    }

    final var bad = flywayFor(schema).locations("classpath:db/migration", "classpath:db/bad");
    assertThatThrownBy(() -> bad.load().migrate()).isInstanceOf(FlywayException.class);

    try (Connection connection = jdbc();
        Statement statement = connection.createStatement()) {
      statement.execute(SET_PATH + schema);
      assertThat(scalar(statement, "SELECT to_regclass('half_done')::text")).isNull();
      assertThat(scalar(statement, "SELECT count(*) FROM workflow_definition")).isEqualTo("1");
    }
    assertThat(flywayFor(schema).load().info().current().getVersion().getVersion()).isEqualTo("1");
  }

  /**
   * The squashed schema creates every table, seeds the two epochs and starts both change sequences
   * at 1. The alpha schema was squashed into V1 again (2026-11-24), so there is no upgrade to test.
   */
  @Test
  void freshSchemaHasEveryTableAndItsSeeds() throws SQLException {
    final String schema = "fresh_schema_test";
    try (Connection connection = jdbc();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
    flywayFor(schema).load().migrate();

    try (Connection connection = jdbc();
        Statement statement = connection.createStatement()) {
      statement.execute(SET_PATH + schema);
      for (final String table :
          new String[] {
            "workflow_definition",
            "workflow_definition_revision",
            "session_config",
            "execution_log",
            "node_checkpoint",
            "node_checkpoint_state",
            "leader_lease",
            "supervisor",
            "supervisor_attachment",
            "session_supervisor",
            "supervisor_version",
            "agent_credential",
            "input_request",
            "input_response",
            "notifications",
            "notification_receipts",
            "notification_preferences",
            "notification_deliveries",
            "notification_epoch",
            "agent_conversation",
            "agent_event",
            "agent_epoch"
          }) {
        assertThat(regclass(connection, table)).isEqualTo(table);
      }
      for (final String gone : new String[] {"kv_entry", "session_history", "input_delivery"}) {
        assertThat(regclass(connection, gone)).isNull();
      }
      assertThat(scalar(statement, "SELECT length(epoch) > 0 FROM notification_epoch"))
          .isEqualTo("t");
      assertThat(scalar(statement, "SELECT length(epoch) > 0 FROM agent_epoch")).isEqualTo("t");
      assertThat(scalar(statement, "SELECT nextval('notification_seq')")).isEqualTo("1");
      assertThat(scalar(statement, "SELECT nextval('agent_seq')")).isEqualTo("1");
    }
    assertThat(flywayFor(schema).load().info().current().getVersion().getVersion()).isEqualTo("1");
  }
}
