// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.support;

import java.util.List;
import lombok.experimental.UtilityClass;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The PostgreSQL every in-JVM server uses when the suite runs with {@code
 * -Pyukta.persistence=postgres}: one container per test JVM, shared by all servers, so a second
 * server on it sees what the first wrote. Needs Docker.
 */
@UtilityClass
public class PostgresDatabase {

  /** The system property the Gradle property {@code yukta.persistence} is passed in. */
  public static final String PROPERTY = "yukta.persistence";

  /**
   * Whether the suite runs on PostgreSQL.
   *
   * @return true when {@code yukta.persistence=postgres}
   */
  public static boolean enabled() {
    return "postgres".equals(System.getProperty(PROPERTY));
  }

  /**
   * The server arguments that put every store on the database (the {@code postgres} profile sets
   * the sessions and agents; the restart and input stores have their own switches).
   *
   * @return {@code --name=value} arguments; starts the container on first use
   */
  @SuppressWarnings("PMD.CloseResource") // stopped by Testcontainers when the JVM exits
  public static List<String> args() {
    final PostgreSQLContainer db = Holder.CONTAINER;
    return List.of(
        "--spring.profiles.active=postgres",
        "--yukta.restart.store-type=postgres",
        "--yukta.input.store-type=postgres",
        "--spring.r2dbc.url=r2dbc:postgresql://"
            + db.getHost()
            + ":"
            + db.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
            + "/"
            + db.getDatabaseName(),
        "--spring.r2dbc.username=" + db.getUsername(),
        "--spring.r2dbc.password=" + db.getPassword(),
        "--spring.flyway.url=" + db.getJdbcUrl(),
        "--spring.flyway.user=" + db.getUsername(),
        "--spring.flyway.password=" + db.getPassword());
  }

  /**
   * Who holds the leader lease, read straight from the database.
   *
   * @return {@code holder:fencing}, for example {@code e2e-first:2}; empty before any server led
   */
  public static String lease() {
    try (java.sql.Connection connection = Holder.CONTAINER.createConnection("");
        java.sql.Statement statement = connection.createStatement();
        java.sql.ResultSet rows =
            statement.executeQuery(
                "SELECT holder, fencing FROM leader_lease WHERE name = 'leader'")) {
      return rows.next() ? rows.getString(1) + ":" + rows.getLong(2) : "";
    } catch (java.sql.SQLException e) {
      throw new IllegalStateException("Could not read the leader lease", e);
    }
  }

  private static final class Holder {
    // stopped by Testcontainers' Ryuk when the JVM exits
    private static final PostgreSQLContainer CONTAINER = start();

    private static PostgreSQLContainer start() {
      final PostgreSQLContainer db = new PostgreSQLContainer("postgres:18-alpine");
      db.start();
      return db;
    }
  }
}
