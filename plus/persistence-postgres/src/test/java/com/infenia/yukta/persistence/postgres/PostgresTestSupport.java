// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import io.r2dbc.spi.ConnectionFactories;
import io.r2dbc.spi.ConnectionFactory;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.r2dbc.core.DatabaseClient;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * A real PostgreSQL with the Flyway schema, shared by every test class in the JVM (started once,
 * stopped by Testcontainers' Ryuk at exit). Tests are skipped when Docker is not available.
 */
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"PMD.AbstractClassWithoutAbstractMethod", "PMD.MutableStaticState"})
abstract class PostgresTestSupport {

  /** The database. */
  private static final PostgreSQLContainer POSTGRES = startPostgres();

  /** Runs statements against {@link #POSTGRES}. */
  protected static DatabaseClient db;

  /** Creates the support. */
  protected PostgresTestSupport() {
    super();
  }

  @SuppressWarnings("resource") // stopped by Ryuk at JVM exit
  private static PostgreSQLContainer startPostgres() {
    PostgreSQLContainer container = null;
    if (DockerClientFactory.instance().isDockerAvailable()) {
      container = new PostgreSQLContainer("postgres:18-alpine");
      container.start();
    }
    return container;
  }

  /**
   * Flyway for a schema of its own, so a test can migrate step by step without touching the schema
   * the other tests share.
   *
   * @param schema the schema to create and migrate
   * @return the configured Flyway
   */
  protected static org.flywaydb.core.api.configuration.FluentConfiguration flywayFor(
      final String schema) {
    return Flyway.configure()
        .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
        .schemas(schema)
        .defaultSchema(schema);
  }

  /**
   * A JDBC connection to the test database.
   *
   * @return a new connection; the caller closes it
   * @throws java.sql.SQLException if the database cannot be reached
   */
  protected static java.sql.Connection jdbc() throws java.sql.SQLException {
    return java.sql.DriverManager.getConnection(
        POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
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
          "node_checkpoint_state",
          "leader_lease",
          "kv_entry",
          "session_history"
        }) {
      db.sql("DELETE FROM " + table).fetch().rowsUpdated().block();
    }
  }
}
