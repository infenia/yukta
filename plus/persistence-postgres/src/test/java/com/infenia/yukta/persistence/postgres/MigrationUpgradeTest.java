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

/** A migration that fails halfway leaves the schema as it was. */
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
    assertThat(flywayFor(schema).load().info().current().getVersion().getVersion()).isEqualTo("2");
  }

  /**
   * A database on the alpha.4 schema, with a queued delivery, takes V2 and keeps everything else.
   */
  @Test
  void notificationsMigrationDropsTheOldOutboxAndKeepsTheRest() throws SQLException {
    final String schema = "upgrade_v2_test";
    try (Connection connection = jdbc();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + schema + " CASCADE");
    }
    flywayFor(schema).target("1").load().migrate();
    try (Connection connection = jdbc();
        Statement statement = connection.createStatement()) {
      statement.execute(SET_PATH + schema);
      statement.execute(
          "INSERT INTO workflow_definition (session_id, workflow_id, definition)"
              + " VALUES ('s1', 'w1', '"
              + DEFINITION
              + "')");
      statement.execute(
          "INSERT INTO input_request (id, req_key, session_id, workflow_id, execution_id,"
              + " node_id, kind, risk, state, version, tier, targets, spec, votes, created_at,"
              + " updated_at) VALUES ('r1', 'k', 's1', 'w1', 'e1', 'n1', 'TEXT', 'LOW', 'OPEN',"
              + " 0, 'default', '[]', '{}', '[]', now(), now())");
      statement.execute(
          "INSERT INTO input_delivery (id, request_id, tier, responder, state, attempts,"
              + " next_attempt) VALUES ('d1', 'r1', 'default', 'external:webhook:ops', 'PENDING',"
              + " 0, now())");
    }

    flywayFor(schema).load().migrate();

    try (Connection connection = jdbc();
        Statement statement = connection.createStatement()) {
      statement.execute(SET_PATH + schema);
      assertThat(scalar(statement, "SELECT to_regclass('input_delivery')::text")).isNull();
      assertThat(scalar(statement, "SELECT count(*) FROM input_request")).isEqualTo("1");
      assertThat(scalar(statement, "SELECT count(*) FROM workflow_definition")).isEqualTo("1");
      for (final String table :
          new String[] {
            "notifications",
            "notification_receipts",
            "notification_preferences",
            "notification_deliveries",
            "notification_epoch"
          }) {
        assertThat(regclass(connection, table)).isEqualTo(table);
      }
      assertThat(scalar(statement, "SELECT length(epoch) > 0 FROM notification_epoch"))
          .isEqualTo("t");
      assertThat(scalar(statement, "SELECT nextval('notification_seq')")).isEqualTo("1");
    }
    assertThat(flywayFor(schema).load().info().current().getVersion().getVersion()).isEqualTo("2");
  }
}
