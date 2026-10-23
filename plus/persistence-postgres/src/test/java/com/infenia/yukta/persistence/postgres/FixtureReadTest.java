// SPDX-License-Identifier: AGPL-3.0-only
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.persistence.postgres;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.message.Message;
import com.infenia.yukta.service.log.ExecutionLogEvent;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.ObjectMapper;

/**
 * Rows written by an earlier build must still be read by this one. Every directory under {@code
 * src/test/resources/fixtures} holds the rows a past build stored; the test loads each into the
 * current schema and reads them back through the stores, so a renamed field of a stored document
 * fails here instead of in production.
 */
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidInstantiatingObjectsInLoops")
class FixtureReadTest extends PostgresTestSupport {

  /** Where the fixture directories live. */
  private static final Path FIXTURE_DIR = Path.of("src/test/resources/fixtures");

  /** Statements in a fixture end with a semicolon at the end of a line. */
  private static final java.util.regex.Pattern SPLIT = java.util.regex.Pattern.compile(";\\s*\\n");

  private static List<Path> fixtures() throws IOException {
    try (Stream<Path> dirs = Files.list(FIXTURE_DIR)) {
      return dirs.sorted().toList();
    }
  }

  private static void load(final Path rows) throws IOException, SQLException {
    try (Connection connection = jdbc();
        Statement statement = connection.createStatement()) {
      for (final String sql : SPLIT.split(Files.readString(rows))) {
        if (!sql.isBlank()) {
          statement.execute(sql);
        }
      }
    }
  }

  @Test
  void rowsOfEveryPastBuildAreReadByTheCurrentStores() throws IOException, SQLException {
    final List<Path> fixtures = fixtures();
    assertThat(fixtures).as("at least one fixture directory").isNotEmpty();
    final ObjectMapper mapper = new ObjectMapper();
    for (final Path fixture : fixtures) {
      emptyTables();
      load(fixture.resolve("rows.sql"));

      final var definitions = new R2dbcWorkflowDefinitionStore(db, mapper);
      final var definition = definitions.find("s1", "w1").block();
      assertThat(definition.nodes())
          .extracting("nodeId", "type")
          .containsExactly(org.assertj.core.groups.Tuple.tuple("n1", "CONSTANT_SOURCE"));
      assertThat(definitions.findRevision("s1", "w1", "r1").block()).isEqualTo(definition);

      final var events = new R2dbcExecutionLogStore(db, mapper, 2, 1_000, 5_000);
      assertThat(events.readAll())
          .extracting(ExecutionLogEvent::type)
          .containsExactly(ExecutionLogEvent.Type.RUN_STARTED, ExecutionLogEvent.Type.RUN_FINISHED);
      events.close();

      final Message<?> checkpoint =
          new R2dbcNodeCheckpointStore(db, mapper).get("r1", "n1").block();
      assertThat(checkpoint.payload()).isEqualTo(Map.of("k", 1));

      assertThat(new R2dbcKeyValueStore(db, mapper).get("w1:count").block()).isEqualTo(3);
      assertThat(new R2dbcSessionHistoryStore(db, mapper, null).load("s1"))
          .contains(List.of(Map.of("role", "user", "text", "hi")));
    }
  }
}
