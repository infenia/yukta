// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.Api.Reply;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Scenarios;
import com.infenia.yukta.security.ApiRole;
import java.util.List;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * {@code POST /api/workflow/start?wait=} holds the call until the run's {@code REPLY} terminal
 * answers, so a workflow can serve as a synchronous HTTP API.
 */
@E2eTest
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class ReplyE2eTest {

  private static final JsonMapper JSON = JsonMapper.builder().build();

  private final Api admin = Api.forRole(ApiRole.ADMIN);

  private static Map<String, Object> node(
      final String id, final String type, final Map<String, Object> config) {
    return Map.of("nodeId", id, "type", type, "config", config);
  }

  /** {@code API_TRIGGER -> MAPPER (greeting) -> REPLY}. */
  private static String replying(final String session) {
    final Map<String, Object> workflow =
        Map.of(
            "workflowId",
            "w",
            "description",
            "replying",
            "nodes",
            List.of(
                node("t", "API_TRIGGER", Map.of()),
                node(
                    "m",
                    "MAPPER",
                    Map.of(
                        "mode",
                        "PROJECTION",
                        "mapping",
                        Map.of("greeting", "'hi ' + payload.name"))),
                node("r", "REPLY", Map.of())),
            "edges",
            List.of(Map.of("source", "t", "target", "m"), Map.of("source", "m", "target", "r")));
    return JSON.writeValueAsString(
        Map.of(
            "sessionId",
            session,
            "name",
            "e2e",
            "description",
            "d",
            "initiator",
            "e2e",
            "projectPath",
            "/tmp",
            "workflows",
            Map.of("w", workflow)));
  }

  private Reply start(final String session, final String query) {
    return admin.call(
        "POST",
        "/api/workflow/start" + query,
        JSON.writeValueAsString(
            Map.of("sessionId", session, "workflowId", "w", "payload", Map.of("name", "ada"))));
  }

  @Test
  void waitingStartGetsTheRunsReply(final Scenarios scenarios) {
    final String session = Scenarios.unique("reply");
    scenarios.applySession(replying(session));

    final Reply replied = start(session, "?wait=20");

    assertThat(replied.status()).as(replied.body()).isEqualTo(200);
    final JsonNode data = JSON.readTree(replied.body()).path("data");
    assertThat(data.path("executionId").asString()).isNotBlank();
    assertThat(data.path("reply").path("greeting").asString()).isEqualTo("hi ada");
  }

  @Test
  void startWithoutWaitIsAcceptedAtOnce(final Scenarios scenarios) {
    final String session = Scenarios.unique("reply");
    scenarios.applySession(replying(session));

    final Reply accepted = start(session, "");

    assertThat(accepted.status()).as(accepted.body()).isEqualTo(202);
    assertThat(JSON.readTree(accepted.body()).path("data").has("reply")).isFalse();
  }
}
