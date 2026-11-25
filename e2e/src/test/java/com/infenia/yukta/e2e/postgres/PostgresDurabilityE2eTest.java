// SPDX-License-Identifier: Apache-2.0
// SPDX-FileCopyrightText: 2026 Infenia Private Limited
package com.infenia.yukta.e2e.postgres;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.infenia.yukta.e2e.support.Api;
import com.infenia.yukta.e2e.support.DedicatedServer;
import com.infenia.yukta.e2e.support.Scenarios;
import com.infenia.yukta.e2e.support.Workflows;
import com.infenia.yukta.security.ApiRole;
import java.time.Duration;
import java.util.Map;
import lombok.NoArgsConstructor;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import tools.jackson.databind.JsonNode;

/**
 * What a server leaves in PostgreSQL is what the next server on the same database serves. Server A
 * writes through the API; server B starts afterwards on the same database and every check reads
 * through B, so nothing can come from A's memory. Runs only with {@code
 * -Pyukta.persistence=postgres}.
 */
@EnabledIfSystemProperty(named = "yukta.persistence", matches = "postgres")
@NoArgsConstructor
@SuppressWarnings({"PMD.AvoidDuplicateLiterals", "PMD.TooManyMethods"})
class PostgresDurabilityE2eTest {

  private static final Duration WAIT = Duration.ofSeconds(60);
  private static final String SESSIONS = "/api/sessions/";
  private static final int OK = 200;
  private static final int TOO_MANY = 429;
  private static final tools.jackson.databind.json.JsonMapper JSON =
      tools.jackson.databind.json.JsonMapper.builder().build();

  private static DedicatedServer serverB;
  private static Api a;
  private static Api b;

  private static String sessionId;
  private static String runId;
  private static String askSession;
  private static String askExecution;
  private static String requestId;
  private static String notifySession;
  private static String notificationTitle;
  private static String supervisor;
  private static String token;
  private static String revokedSupervisor;
  private static String revokedToken;

  @BeforeAll
  @SuppressWarnings("PMD.CloseResource") // the servers stop with the JVM
  static void seedThenStartSecondServer() {
    final DedicatedServer serverA = DedicatedServer.start(true);
    a = Api.toServer(serverA.baseUrl(), serverA.key(ApiRole.ADMIN));

    sessionId = Scenarios.unique("pg-session");
    apply(Workflows.echo(sessionId, "first"));
    apply(Workflows.echo(sessionId, "second"));
    runId = start(sessionId);
    awaitTerminal(a, runId);

    askSession = Scenarios.unique("pg-ask");
    apply(Workflows.asking(askSession));
    askExecution = start(askSession);
    requestId =
        await().atMost(WAIT).until(PostgresDurabilityE2eTest::openRequest, id -> !id.isEmpty());
    assertThat(
            a.call(
                    "POST",
                    "/api/executions/" + askExecution + "/inputs/" + requestId + "/answer",
                    "{\"decision\":\"approve\"}")
                .status())
        .isEqualTo(200);
    awaitTerminal(a, askExecution);

    notifySession = Scenarios.unique("pg-notify");
    notificationTitle = Scenarios.unique("pg-title");
    apply(
        Workflows.ending(
            notifySession,
            "notifying",
            Map.of(
                "nodeId",
                "n",
                "type",
                "NOTIFY_TERMINAL",
                "config",
                Map.of(
                    "target",
                    "inbox",
                    "title",
                    notificationTitle,
                    "severity",
                    "INFO",
                    "text",
                    "e2e notification"))));
    start(notifySession);
    await()
        .atMost(WAIT)
        .until(() ->
            a.call("GET", "/api/notifications?kind=INFO", null).body().contains(notificationTitle));

    supervisor = Scenarios.unique("pg-sup");
    token = issueCredential(supervisor);
    revokedSupervisor = Scenarios.unique("pg-revoked");
    revokedToken = issueCredential(revokedSupervisor);
    assertThat(
            a.call("DELETE", "/api/supervisors/" + revokedSupervisor + "/credential", null)
                .status())
        .isEqualTo(200);

    serverB = DedicatedServer.start(true);
    b = Api.toServer(serverB.baseUrl(), serverB.key(ApiRole.ADMIN));
  }

  @AfterAll
  static void clean() {
    for (final String session : new String[] {sessionId, askSession, notifySession}) {
      a.call("DELETE", SESSIONS + session, null);
    }
    for (final String id : new String[] {supervisor, revokedSupervisor}) {
      a.call("PUT", "/api/supervisors/" + id + "/assignments?unlock=true", "[]");
      a.call("DELETE", "/api/supervisors/" + id, null);
    }
  }

  private static void apply(final String json) {
    assertThat(a.call("POST", "/api/sessions", json).status()).isEqualTo(200);
  }

  private static String start(final String session) {
    final String body =
        JSON.writeValueAsString(
            Map.of("sessionId", session, "workflowId", "w", "payload", Map.of()));
    final String[] reply = new String[1];
    // a server that has only just started answers 429 "recovering" for a moment
    await()
        .atMost(WAIT)
        .until(() -> {
          final var response = a.call("POST", "/api/workflow/start", body);
          reply[0] = response.body();
          return response.status() != TOO_MANY;
        });
    return JSON.readTree(reply[0]).path("data").path("executionId").asString();
  }

  private static void awaitTerminal(final Api api, final String execution) {
    await()
        .atMost(WAIT)
        .pollInterval(Duration.ofMillis(250))
        .until(() -> {
          final var reply = api.call("GET", "/api/executions/" + execution, null);
          return reply.status() == OK && reply.body().contains("\"SUCCESS\"");
        });
  }

  private static String openRequest() {
    final var reply = a.call("GET", "/api/sessions/" + askSession + "/inputs", null);
    if (reply.status() != OK) {
      return "";
    }
    final JsonNode inputs = JSON.readTree(reply.body()).path("data");
    return inputs.isArray() && !inputs.isEmpty() ? inputs.get(0).path("id").asString() : "";
  }

  /** A supervisor with a session of its own assigned, and its agent token. */
  private static String issueCredential(final String id) {
    assertThat(
            a.call(
                    "POST",
                    "/api/supervisors",
                    "{\"id\":\""
                        + id
                        + "\",\"type\":\"http\","
                        + "\"settings\":{\"operatorAccess\":[\"StartWorkflowCommand\"]}}")
                .status())
        .isEqualTo(201);
    assertThat(
            a.call(
                    "PUT",
                    "/api/supervisors/" + id + "/assignments",
                    "[{\"target\":\"session:" + Scenarios.unique("pg-owned") + "\"}]")
                .status())
        .isEqualTo(200);
    return a.data(a.client().post().uri("/api/supervisors/{id}/credential", id).exchange())
        .path("token")
        .asString();
  }

  @Test
  void serverStartsOnTheMigratedDatabase() {
    assertThat(b.call("GET", "/actuator/health", null).body()).contains("\"UP\"");
  }

  @Test
  void sessionAndItsWorkflowAreStillThere() {
    assertThat(b.call("GET", "/api/sessions", null).body()).contains(sessionId);
    assertThat(b.call("GET", SESSIONS + sessionId + "/workflows/w", null).body())
        .contains("echo second");
  }

  @Test
  void workflowVersionsAreKeptAndCanBeRolledBack() {
    final JsonNode versions =
        b.data(b.client().get().uri(SESSIONS + sessionId + "/workflows/w/versions").exchange());
    assertThat(versions.size()).isGreaterThanOrEqualTo(2);
    final String oldest = versions.get(versions.size() - 1).path("id").asString();
    assertThat(
            b.call("POST", SESSIONS + sessionId + "/workflows/w/rollback?version=" + oldest, null)
                .status())
        .isEqualTo(200);
    assertThat(b.call("GET", SESSIONS + sessionId + "/workflows/w", null).body())
        .contains("echo first");
  }

  @Test
  void finishedRunKeepsItsStatusHistoryAndOutput() {
    final var run = b.call("GET", "/api/executions/" + runId, null);
    assertThat(run.status()).isEqualTo(200);
    assertThat(run.body()).contains("SUCCESS");
    assertThat(b.call("GET", "/api/executions?sessionId=" + sessionId, null).body())
        .contains(runId);
    assertThat(b.call("GET", "/api/workflow/" + sessionId + "/history", null).body())
        .contains(runId);
    assertThat(b.call("GET", "/api/executions/" + runId + "/nodes/p/output", null).status())
        .isEqualTo(200);
  }

  @Test
  void answeredInputRequestIsKept() {
    final var request =
        b.call("GET", "/api/executions/" + askExecution + "/inputs/" + requestId, null);
    assertThat(request.status()).isEqualTo(200);
    assertThat(request.body()).contains("ANSWERED", "approve");
  }

  @Test
  void notificationIsKept() {
    assertThat(b.call("GET", "/api/notifications?kind=INFO", null).body())
        .contains(notificationTitle);
  }

  @Test
  void supervisorsAndTheirVersionsAreKept() {
    assertThat(b.call("GET", "/api/supervisors/" + supervisor, null).status()).isEqualTo(200);
    assertThat(b.call("GET", "/api/supervisors/" + supervisor + "/versions", null).status())
        .isEqualTo(200);
  }

  @Test
  void issuedAgentCredentialAuthenticatesOnTheNewServerAndRevokedOneDoesNot() {
    final Api agent = Api.bearer(serverB.baseUrl(), token);
    await()
        .atMost(WAIT)
        .until(() -> agent.call("GET", "/api/agent/v1/signals", null).status() == 200);
    assertThat(
            Api.bearer(serverB.baseUrl(), revokedToken)
                .call("GET", "/api/agent/v1/signals", null)
                .status())
        .isEqualTo(401);
  }
}
