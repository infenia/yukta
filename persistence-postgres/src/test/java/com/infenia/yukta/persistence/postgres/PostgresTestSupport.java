// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real PostgreSQL with the Flyway schema, shared by the tests of a class. Tests are skipped when
 * Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({
  "PMD.AbstractClassWithoutAbstractMethod",
  "PMD.ShortVariable",
  "PMD.MutableStaticState"
})
abstract class PostgresTestSupport {

  /** The database. */
  @Container
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  /** Runs statements against {@link #POSTGRES}. */
  protected static DatabaseClient db;

  /** Creates the support. */
  protected PostgresTestSupport() {
    super();
  }

  @BeforeAll
  static void migrate() {
    Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .load()
        .migrate();
    final ConnectionFactory connectionFactory =
        ConnectionFactories.get(
            "r2dbc:postgresql://"
                + POSTGRES.getUsername()
                + ":"
                + POSTGRES.getPassword()
                + "@"
                + POSTGRES.getHost()
                + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                + "/"
                + POSTGRES.getDatabaseName());
    db = DatabaseClient.create(connectionFactory);
  }

  @BeforeEach
  void emptyTables() {
    for (final String table :
        new String[] {
          "workflow_definition_revision",
          "workflow_definition",
          "session_config",
          "execution_log",
          "node_checkpoint",
          "node_checkpoint_state"
        }) {
      db.sql("DELETE FROM " + table).fetch().rowsUpdated().block();
    }
  }
}
