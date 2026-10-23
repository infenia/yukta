// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import lombok.NoArgsConstructor;
import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.FlywayException;
import org.junit.jupiter.api.Test;

/**
 * A database that already holds rows written by an older schema must upgrade to the newest one
 * without losing them. The test builds the first schema version, fills it, then runs the rest of
 * the migrations on top.
 */
@NoArgsConstructor
@SuppressWarnings("PMD.LawOfDemeter")
class MigrationUpgradeTest extends PostgresTestSupport {

  /** Points a session at the schema under test. */
  private static final String SET_PATH = "SET search_path TO ";

  /** The schema this test migrates on its own. */
  private static final String SCHEMA = "upgrade_test";

  /** A stored workflow definition. */
  private static final String DEFINITION =
      "{\"workflowId\":\"w1\",\"description\":\"d\",\"nodes\":[],\"edges\":[]}";

  private static String scalar(final Statement statement, final String sql) throws SQLException {
    try (ResultSet rows = statement.executeQuery(sql)) {
      return rows.next() ? rows.getString(1) : null;
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
    assertThat(flywayFor(schema).load().info().current().getVersion().getVersion()).isEqualTo("6");
  }

  @Test
  void rowsOfTheFirstSchemaSurviveEveryLaterMigration() throws SQLException {
    try (Connection connection = jdbc();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }
    flywayFor(SCHEMA).target("1").load().migrate();

    try (Connection connection = jdbc();
        Statement statement = connection.createStatement()) {
      statement.execute(SET_PATH + SCHEMA);
      statement.execute(
          "INSERT INTO session_config (session_id, description, tags)"
              + " VALUES ('s1', 'kept', '{\"team\":\"core\"}')");
      statement.execute(
          "INSERT INTO workflow_definition (session_id, workflow_id, definition)"
              + " VALUES ('s1', 'w1', '"
              + DEFINITION
              + "')");
      statement.execute(
          "INSERT INTO workflow_definition_revision"
              + " (session_id, workflow_id, revision_id, definition, saved_at)"
              + " VALUES ('s1', 'w1', 'r1', '"
              + DEFINITION
              + "', now())");
      statement.execute(
          "INSERT INTO execution_log (execution_id, event)"
              + " VALUES ('e1', '{\"type\":\"RUN_STARTED\"}')");
    }

    final Flyway flyway = flywayFor(SCHEMA).load();
    final var result = flyway.migrate();
    flyway.validate();

    assertThat(result.success).isTrue();
    assertThat(result.migrationsExecuted).isEqualTo(5);
    assertThat(flyway.info().current().getVersion().getVersion()).isEqualTo("6");
    try (Connection connection = jdbc();
        Statement statement = connection.createStatement()) {
      statement.execute(SET_PATH + SCHEMA);
      assertThat(scalar(statement, "SELECT description FROM session_config WHERE session_id='s1'"))
          .isEqualTo("kept");
      assertThat(scalar(statement, "SELECT tags ->> 'team' FROM session_config")).isEqualTo("core");
      assertThat(scalar(statement, "SELECT definition ->> 'workflowId' FROM workflow_definition"))
          .isEqualTo("w1");
      // V2 adds the note column: old versions have none
      assertThat(
              scalar(
                  statement,
                  "SELECT coalesce(note, 'none') FROM workflow_definition_revision"
                      + " WHERE revision_id='r1'"))
          .isEqualTo("none");
      assertThat(scalar(statement, "SELECT event ->> 'type' FROM execution_log"))
          .isEqualTo("RUN_STARTED");
      // the tables the later migrations add exist and are empty
      for (final String table :
          new String[] {
            "node_checkpoint",
            "node_checkpoint_state",
            "leader_lease",
            "kv_entry",
            "session_history"
          }) {
        assertThat(scalar(statement, "SELECT count(*) FROM " + table)).isEqualTo("0");
      }
    }
    assertThat(flywayFor(SCHEMA).load().migrate().migrationsExecuted).isZero();
  }
}
