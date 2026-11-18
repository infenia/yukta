// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.mcp;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Mcp;
import com.infenia.yukta.e2e.support.Scenarios;
import com.infenia.yukta.e2e.support.Workflows;
import com.infenia.yukta.security.ApiRole;
import java.time.Duration;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * An agent manages supervisors: save, read, rules, versions, rollback, replay, assignments, and the
 * read-only views of supervision.
 */
@E2eTest
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals"})
class McpSupervisorE2eTest {

  private static JsonNode call(final Mcp mcp, final String tool, final Map<String, Object> args) {
    return Mcp.json(mcp.call(tool, args));
  }

  private static String save(final Mcp mcp, final Scenarios scenarios) {
    final String id = Scenarios.unique("mcpsup");
    final JsonNode saved =
        call(
            mcp,
            "save_supervisor",
            Map.of("type", "rules", "id", id, "settings", Map.of("rules", java.util.List.of())));
    assertThat(saved.path("id").asString()).isEqualTo(id);
    scenarios.createSupervisor(
        "{\"id\":\"" + id + "\",\"type\":\"rules\",\"settings\":{\"rules\":[]}}", false);
    return id;
  }

  @Test
  void supervisorIsSavedReadVersionedAndRolledBack(final Scenarios scenarios) {
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      final String id = save(mcp, scenarios);

      assertThat(call(mcp, "get_supervisor", Map.of("id", id)).path("type").asString())
          .isEqualTo("rules");
      assertThat(call(mcp, "list_supervisors", Map.of()).toString()).contains(id);
      final JsonNode rules =
          call(
              mcp,
              "save_rules",
              Map.of(
                  "supervisorId",
                  id,
                  "rules",
                  Map.of("schemaVersion", 1, "rules", java.util.List.of())));
      assertThat(rules.path("version").asInt()).isEqualTo(2);
      assertThat(call(mcp, "get_rules", Map.of("supervisorId", id)).path("version").asInt())
          .isEqualTo(2);
      assertThat(call(mcp, "list_supervisor_versions", Map.of("supervisorId", id)).path("versions"))
          .hasSize(2);
      assertThat(
              call(mcp, "rollback_supervisor", Map.of("supervisorId", id, "version", 1))
                  .path("id")
                  .asString())
          .isEqualTo(id);
    }
  }

  @Test
  void assignmentsAreCheckedThenSet(final Scenarios scenarios) {
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      final String id = save(mcp, scenarios);
      final var assignments =
          java.util.List.of(Map.of("target", "session:" + Scenarios.unique("mcpowned")));

      assertThat(
              call(mcp, "check_assignment", Map.of("supervisorId", id, "assignments", assignments))
                  .path("valid")
                  .asBoolean())
          .isTrue();
      assertThat(
              call(mcp, "set_assignments", Map.of("supervisorId", id, "assignments", assignments))
                  .path("assignments"))
          .hasSize(1);
      assertThat(
              call(
                      mcp,
                      "set_assignments",
                      Map.of(
                          "supervisorId", id, "assignments", java.util.List.of(), "unlock", true))
                  .path("assignments"))
          .isEmpty();
    }
  }

  @Test
  void recordedRunIsReplayedAgainstDraftSettings(final Scenarios scenarios) {
    final String session = Scenarios.unique("mcpreplay");
    scenarios.applySession(Workflows.echo(session, "replayed"));
    final String execution = scenarios.startWorkflow(session, "w", Map.of());
    scenarios.awaitTerminal(execution, Duration.ofSeconds(30));
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      final String id = save(mcp, scenarios);

      assertThat(
              call(mcp, "replay_supervisor", Map.of("supervisorId", id, "executionId", execution))
                      .isArray()
                  || call(
                          mcp,
                          "replay_supervisor",
                          Map.of("supervisorId", id, "executionId", execution))
                      .isObject())
          .isTrue();
      assertThat(
              Mcp.text(
                  mcp.callExpectingError(
                      "replay_rules", Map.of("supervisorId", id, "executionId", "e2e-nope"))))
          .isNotBlank();
    }
  }

  @Test
  void theSupervisionViewsAndCatalogsAreRead(final Scenarios scenarios) {
    final String session = Scenarios.unique("mcpown");
    scenarios.applySession(Workflows.echo(session, "x"));
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      assertThat(
              call(mcp, "get_ownership", Map.of("sessionId", session, "workflowId", "w"))
                  .path("run")
                  .asString())
          .isEqualTo("default");
      assertThat(call(mcp, "get_supervision", Map.of()).path("commands")).isNotEmpty();
      assertThat(call(mcp, "get_supervision_summary", Map.of()).has("unowned")).isTrue();
      assertThat(call(mcp, "get_supervisor_contract", Map.of()).has("version")).isTrue();
      assertThat(call(mcp, "list_supervisor_engines", Map.of()).toString())
          .contains("rules", "http");
      assertThat(
              call(mcp, "get_supervisor_engine", Map.of("type", "rules")).path("type").asString())
          .isEqualTo("rules");
      assertThat(call(mcp, "list_supervisor_presets", Map.of("type", "rules")).path("presets"))
          .isNotEmpty();
    }
  }

  @Test
  void supervisorIsDeleted(final Scenarios scenarios) {
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      final String id = save(mcp, scenarios);

      assertThat(call(mcp, "delete_supervisor", Map.of("id", id)).path("deleted").asBoolean())
          .isTrue();
      assertThat(Mcp.text(mcp.callExpectingError("get_supervisor", Map.of("id", id))))
          .contains("Unknown supervisor");
    }
  }

  @Test
  void credentialIsIssuedAndRevokedForAnHttpSupervisor(final Scenarios scenarios) {
    final String id = Scenarios.unique("mcpcred");
    scenarios.createSupervisor("{\"id\":\"" + id + "\",\"type\":\"http\",\"settings\":{}}");
    try (Mcp mcp = Mcp.connect(ApiRole.ADMIN)) {
      assertThat(call(mcp, "create_credential", Map.of("supervisorId", id)).toString())
          .contains("yag_");
      assertThat(call(mcp, "revoke_credential", Map.of("supervisorId", id)).isObject()).isTrue();
    }
  }
}
