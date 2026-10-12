// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.model.workflow.WorkflowDefinition;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Edge;
import com.infenia.yukta.model.workflow.WorkflowDefinition.Node;
import com.infenia.yukta.service.control.gateway.ControlBusGateway;
import com.infenia.yukta.service.log.ExecutionLogStore;
import com.infenia.yukta.service.orchestrator.tracker.TaskTrackerService;
import com.infenia.yukta.service.workflow.store.WorkflowDefinitionStore;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.aot.DisabledInAotMode;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The real application with its sessions, workflow definitions and execution log in PostgreSQL.
 * Runs only in a build made with {@code -Pyukta.persistence=postgres}, and needs Docker.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.MOCK)
@ActiveProfiles("postgres")
@DisabledInAotMode
@EnabledIfSystemProperty(named = "yukta.persistence", matches = "postgres")
@Testcontainers(disabledWithoutDocker = true)
@SuppressWarnings({"PMD.CommentRequired", "PMD.AvoidDuplicateLiterals", "PMD.LawOfDemeter"})
@NoArgsConstructor
class PostgresPersistenceAcceptanceTest {

  @Container
  private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer("postgres:18-alpine");

  @DynamicPropertySource
  static void database(final DynamicPropertyRegistry registry) {
    registry.add(
        "spring.r2dbc.url",
        () ->
            "r2dbc:postgresql://"
                + POSTGRES.getHost()
                + ":"
                + POSTGRES.getMappedPort(PostgreSQLContainer.POSTGRESQL_PORT)
                + "/"
                + POSTGRES.getDatabaseName());
    registry.add("spring.r2dbc.username", POSTGRES::getUsername);
    registry.add("spring.r2dbc.password", POSTGRES::getPassword);
    registry.add("spring.flyway.url", POSTGRES::getJdbcUrl);
    registry.add("spring.flyway.user", POSTGRES::getUsername);
    registry.add("spring.flyway.password", POSTGRES::getPassword);
  }

  @Autowired private WorkflowDefinitionStore definitions;
  @Autowired private ControlBusGateway gateway;
  @Autowired private TaskTrackerService tracker;
  @Autowired private ExecutionLogStore executionLog;

  @Test
  void aRun_isStoredInPostgres_withItsDefinitionAndVersions() throws SQLException {
    final String session = "pg-" + UUID.randomUUID();
    definitions
        .save(
            session,
            new WorkflowDefinition(
                "wf",
                "postgres acceptance",
                List.of(
                    new Node("t", "CONSTANT_SOURCE", Map.of()),
                    new Node("end", "CONSOLE_TERMINAL", Map.of())),
                List.of(new Edge("t", "end", null))))
        .block();

    final String executionId = gateway.startWorkflow(session, "wf").block();
    await()
        .atMost(Duration.ofSeconds(30))
        .until(
            () -> {
              final var progress = tracker.getProgressByExecutionId(executionId);
              return progress != null && "SUCCESS".equals(progress.status());
            });

    assertThat(definitions.find(session, "wf").block()).isNotNull();
    assertThat(definitions.revisions(session, "wf").collectList().block()).hasSize(1);
    await()
        .atMost(Duration.ofSeconds(10))
        .untilAsserted(
            () ->
                assertThat(executionLog.readAll())
                    .filteredOn(event -> event.executionId().equals(executionId))
                    .extracting(event -> event.type().name())
                    .contains("RUN_STARTED", "RUN_FINISHED"));
    try (Connection connection =
            DriverManager.getConnection(
                POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        PreparedStatement count =
            connection.prepareStatement(
                "SELECT count(*) FROM execution_log WHERE execution_id = ?")) {
      count.setString(1, executionId);
      try (ResultSet rows = count.executeQuery()) {
        assertThat(rows.next()).isTrue();
        assertThat(rows.getLong(1)).isGreaterThanOrEqualTo(2);
      }
    }
  }
}
