// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.lifecycle;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.Api.Reply;
import com.infenia.yukta.e2e.support.E2eTest;
import com.infenia.yukta.e2e.support.Scenarios;
import java.time.Duration;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;

/**
 * A run that asks for an approval: listed, read, refused a bad answer, answered; and the
 * one-time-token endpoint.
 */
@E2eTest
@NoArgsConstructor
@SuppressWarnings("PMD.AvoidDuplicateLiterals")
class InputRequestE2eTest {

  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();
  private static final Duration WAIT = Duration.ofSeconds(30);

  private final Api admin = Api.forRole(com.infenia.yukta.security.ApiRole.ADMIN);

  /** The OPEN request of the session once the run has asked. */
  private JsonNode awaitRequest(final String session) {
    final JsonNode[] found = new JsonNode[1];
    await()
        .atMost(WAIT)
        .until(() -> {
          final JsonNode list =
              admin.data(admin.client().get().uri("/api/sessions/{s}/inputs", session).exchange());
          if (!list.isEmpty()) {
            found[0] = list.get(0);
          }
          return found[0] != null;
        });
    return found[0];
  }

  @Test
  void approvalIsListedReadAnsweredAndTheRunContinues(final Scenarios scenarios) {
    final String session = Scenarios.unique("input");
    scenarios.applySession(Workflows.asking(session));
    final String execution = scenarios.startWorkflow(session, "w", Map.of());
    final JsonNode request = awaitRequest(session);
    final String id = request.path("id").asString();
    final String path = "/api/executions/" + execution + "/inputs/" + id;

    assertThat(request.path("state").asString()).isEqualTo("OPEN");
    assertThat(request.path("kind").asString()).isEqualTo("APPROVAL");
    assertThat(request.path("prompt").asString()).isEqualTo("ok?");
    assertThat(request.path("options").findValuesAsString("id")).contains("approve", "reject");
    assertThat(admin.call("GET", "/api/inputs", null).body()).contains(id);
    assertThat(admin.call("GET", path, null).status()).isEqualTo(200);

    assertThat(admin.call("POST", path + "/answer", "{}").status())
        .as("decision is required")
        .isEqualTo(400);
    assertThat(admin.call("POST", path + "/answer", "{\"decision\":\"maybe\"}").status())
        .as("not one of the options")
        .isEqualTo(422);
    assertThat(
            admin
                .call(
                    "POST",
                    "/api/executions/" + execution + "/inputs/e2e-nope/answer",
                    "{\"decision\":\"approve\"}")
                .status())
        .isEqualTo(404);

    final Reply answered = admin.call("POST", path + "/answer", "{\"decision\":\"approve\"}");
    assertThat(answered.status()).as(answered.body()).isEqualTo(200);
    assertThat(scenarios.awaitTerminal(execution, WAIT).path("status").asString())
        .isEqualTo("SUCCESS");
    assertThat(
            JSON.readTree(admin.call("GET", path, null).body())
                .path("data")
                .path("state")
                .asString())
        .isEqualTo("ANSWERED");
    assertThat(admin.call("POST", path + "/answer", "{\"decision\":\"approve\"}").status())
        .as("answered once")
        .isEqualTo(409);
  }

  @Test
  void rejectionIsRecordedAndTheRunContinues(final Scenarios scenarios) {
    final String session = Scenarios.unique("input");
    scenarios.applySession(Workflows.asking(session));
    final String execution = scenarios.startWorkflow(session, "w", Map.of());
    final String id = awaitRequest(session).path("id").asString();

    final Reply rejected =
        admin.call(
            "POST",
            "/api/executions/" + execution + "/inputs/" + id + "/answer",
            "{\"decision\":\"reject\"}");

    assertThat(rejected.status()).as(rejected.body()).isEqualTo(200);
    assertThat(scenarios.awaitTerminal(execution, WAIT).path("status").asString())
        .isEqualTo("SUCCESS");
    assertThat(admin.call("GET", "/api/executions/" + execution + "/inputs/" + id, null).body())
        .contains("reject");
  }

  @Test
  void requestCanBeEscalated(final Scenarios scenarios) {
    final String session = Scenarios.unique("input");
    scenarios.applySession(Workflows.asking(session));
    final String execution = scenarios.startWorkflow(session, "w", Map.of());
    final String id = awaitRequest(session).path("id").asString();

    final Reply escalated =
        admin.call(
            "POST",
            "/api/executions/" + execution + "/inputs/" + id + "/escalate",
            "{\"reason\":\"needs a person\"}");

    assertThat(escalated.status()).as(escalated.body()).isBetween(200, 299);
    admin.call("POST", "/api/workflow/" + session + "/w/stop?mode=CANCEL", null);
  }

  @Test
  void theListTakesStateAndRefusesAnUnknownOne(final Scenarios scenarios) {
    final String session = Scenarios.unique("input");
    scenarios.applySession(Workflows.asking(session));

    assertThat(
            admin
                .call("GET", "/api/sessions/" + session + "/inputs?state=OPEN,ESCALATED", null)
                .status())
        .isEqualTo(200);
    assertThat(admin.call("GET", "/api/sessions/" + session + "/inputs?state=NOPE", null).status())
        .isEqualTo(400);
  }

  @Test
  void tokenAnswerNeedsValidTokenAndNoKey() {
    final Api anonymous = Api.toServer(com.infenia.yukta.e2e.support.YuktaServer.baseUrl(), null);

    assertThat(anonymous.call("POST", "/api/inputs/token-answer", "{}").status()).isEqualTo(400);
    final Reply forged =
        anonymous.call(
            "POST",
            "/api/inputs/token-answer",
            "{\"requestId\":\"e2e-nope\",\"tier\":\"default\",\"expires\":1,"
                + "\"token\":\"forged\",\"decision\":\"approve\"}");
    assertThat(forged.status()).as(forged.body()).isEqualTo(403);
  }
}
