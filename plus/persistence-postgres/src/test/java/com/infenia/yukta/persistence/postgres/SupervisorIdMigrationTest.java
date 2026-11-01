// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;

/** V8 renames stored supervisor ids that break the naming rule and keeps the mapping. */
@NoArgsConstructor
class SupervisorIdMigrationTest extends PostgresTestSupport {

  private static final String SCHEMA = "supervisor_ids_test";

  private static List<String> column(final Statement statement, final String sql)
      throws SQLException {
    final List<String> values = new ArrayList<>();
    try (ResultSet rows = statement.executeQuery(sql)) {
      while (rows.next()) {
        values.add(rows.getString(1));
      }
    }
    return values;
  }

  private static void profile(final Statement statement, final String id) throws SQLException {
    statement.execute(
        "INSERT INTO supervisor_profile (id, type, settings, dry_run)"
            + " VALUES ('"
            + id
            + "', 'rule-supervisor', '{}', false)");
  }

  private static void binding(final Statement statement, final String id, final String profile)
      throws SQLException {
    statement.execute(
        "INSERT INTO supervisor_binding (id, profile_id, scope, enabled)"
            + " VALUES ('"
            + id
            + "', '"
            + profile
            + "', 'global', true)");
  }

  @Test
  void badIdsAreRenamedWithoutCollisionsAndTheMappingIsKept() throws SQLException {
    try (Connection connection = jdbc();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP SCHEMA IF EXISTS " + SCHEMA + " CASCADE");
    }
    flywayFor(SCHEMA).target("7").load().migrate();

    final String longId = "x".repeat(100);
    try (Connection connection = jdbc();
        Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO " + SCHEMA);
      profile(statement, "ok");
      profile(statement, "a:b");
      profile(statement, "a_b");
      profile(statement, "key:admin");
      profile(statement, longId);
      binding(statement, "key:admin", "key:admin");
      binding(statement, "fine", "a:b");
      binding(statement, "two words", "ok");
    }

    assertThat(flywayFor(SCHEMA).load().migrate().success).isTrue();

    try (Connection connection = jdbc();
        Statement statement = connection.createStatement()) {
      statement.execute("SET search_path TO " + SCHEMA);
      // a:b collides with the existing a_b
      assertThat(column(statement, "SELECT id FROM supervisor_profile ORDER BY id"))
          .containsExactly("a_b", "a_b-2", "key_admin", "ok", "x".repeat(64));
      assertThat(
              column(
                  statement,
                  "SELECT new_id FROM supervisor_id_migration"
                      + " WHERE kind = 'profile' AND old_id = 'a:b'"))
          .containsExactly("a_b-2");
      assertThat(
              column(
                  statement,
                  "SELECT new_id FROM supervisor_id_migration"
                      + " WHERE kind = 'profile' AND old_id = '"
                      + longId
                      + "'"))
          .containsExactly("x".repeat(64));
      // bindings follow their renamed profile, and keep a valid shape themselves
      assertThat(
              column(
                  statement, "SELECT id || '>' || profile_id FROM supervisor_binding ORDER BY id"))
          .containsExactly("fine>a_b-2", "key_admin>key_admin", "two_words>ok");
      assertThat(
              column(
                  statement,
                  "SELECT count(*)::text FROM supervisor_id_migration WHERE kind = 'binding'"))
          .containsExactly("2");
      // the foreign key is back under its old name and holds
      assertThat(
              column(
                  statement,
                  "SELECT conname FROM pg_constraint WHERE conrelid ="
                      + " 'supervisor_binding'::regclass AND contype = 'f'"))
          .containsExactly("supervisor_binding_profile_id_fkey");
    }
    // nothing more to do on a second run
    assertThat(flywayFor(SCHEMA).load().migrate().migrationsExecuted).isZero();
  }
}
